package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.util.AtlasMirror;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/**
 * M13 chunk 1 (2026-05-13): Metal-side counterpart to {@link GlViewCapture}.
 * Owns the 48×32 Shared-storage Metal bake target, the
 * {@link MetalBudgetBufferRenderer}, and the {@link AtlasMirror} that lifts
 * MC's GL atlas onto Metal. Exposes the same API shape
 * ({@link #emitToStream}) so {@link ModelTextureBakery} can dispatch to
 * either GL or Metal at run-time without per-pixel format conversion in
 * the caller.
 *
 * <p>Per-bake flow used by {@code renderToStreamMetal}:
 * <pre>
 *   capture.beginBake(mcAtlasGlId, meshAddr, quadCount);
 *   for (int face = 0; face &lt; 6; face++) {
 *       capture.renderFace(face%3, face/3, matrix);
 *   }
 *   capture.endBake();           // ends pass + submit + GPU wait
 *   capture.emitToStream(dest);  // CPU read + pack into uvec2-per-pixel
 * </pre>
 *
 * <p>Output format matches {@link GlViewCapture#emitToStream} pixel-for-pixel
 * so {@code ModelStore} consumers don't need to branch on backend:
 * <pre>
 *   outA[0..3] = packed RGBA (little-endian: R at lowest byte)
 *   outA[4..7] = (depthBits &lt;&lt; 8) | (tintBit &lt;&lt; 7)
 * </pre>
 */
public final class MetalViewCapture {
    private final int width;        // per-face cell width
    private final int height;       // per-face cell height
    private final int totalW;       // 3 * width
    private final int totalH;       // 2 * height

    private final RenderBackend backend;
    private final MetalTexture bakeTarget;
    private final MetalTexture metaTarget;
    private final AtlasMirror atlasMirror;
    private final MetalBudgetBufferRenderer renderer;
    private final long readbackBuffer;
    private final long readbackBytes;
    private final long metaReadbackBuffer;
    private final long metaReadbackBytes;
    private boolean activeBake;

    public MetalViewCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.totalW = width * 3;
        this.totalH = height * 2;
        this.backend = RenderBackendFactory.get();
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException(
                    "MetalViewCapture is Metal-only — GL goes through GlViewCapture.");
        }

        MetalTexture t = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
        t.storeRenderTargetUploadable(GL_RGBA8, 1, this.totalW, this.totalH);
        t.name("Voxy.MetalBakeTarget");
        this.bakeTarget = t;

        MetalTexture m = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
        // Bakery metadata is R8UI (1 byte per pixel)
        m.storeRenderTargetUploadable(org.lwjgl.opengl.GL30C.GL_R8UI, 1, this.totalW, this.totalH);
        m.name("Voxy.MetalBakeMetaTarget");
        this.metaTarget = m;

        this.atlasMirror = new AtlasMirror();
        this.renderer = new MetalBudgetBufferRenderer();

        this.readbackBytes = (long) this.totalW * this.totalH * 4L;
        this.readbackBuffer = MemoryUtil.nmemAllocChecked(this.readbackBytes);

        this.metaReadbackBytes = (long) this.totalW * this.totalH * 1L;
        this.metaReadbackBuffer = MemoryUtil.nmemAllocChecked(this.metaReadbackBytes);
    }

    /**
     * Clear the bake targets to transparent black / zero. Opens and closes a tiny
     * render pass that only does a CLEAR load action — no draws. Lets the
     * caller separate "clear once at start of bake" from "draw each face",
     * which is essential for the fluid path where the face draws happen in
     * per-face passes (each with LOAD) to allow per-face mesh re-uploads
     * outside the active render encoder.
     */
    public void clear() {
        if (this.activeBake) {
            throw new IllegalStateException("clear() while a bake pass is active");
        }
        // Open + immediately close a CLEAR pass. The Metal driver collapses
        // this into a single clearColor command on the bake targets.
        try (var enc = this.backend.beginRenderPass(
                me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(this.totalW, this.totalH)
                        .clearColor(this.bakeTarget, 0f, 0f, 0f, 0f)
                        .clearColor(this.metaTarget, 0f, 0f, 0f, 0f)
                        .build())) {
            enc.setViewport(0, 0, this.totalW, this.totalH, 0, 1);
        }
        this.backend.submit();
    }

    public void beginBake(int mcAtlasGlId, long meshAddr, int quadCount, boolean clear) {
        if (this.activeBake) {
            throw new IllegalStateException("beginBake while a previous bake is active");
        }
        IGpuTexture atlas = this.atlasMirror.syncMetal(mcAtlasGlId);
        if (atlas == null) {
            // MC's atlas not yet ready — leave the bake target zeroed. The
            // pack loop below will emit transparent black pixels which the
            // shader's alpha-discard will skip naturally on the next draw.
            return;
        }
        this.renderer.beginPass(this.bakeTarget, this.metaTarget, this.totalW, this.totalH, clear);
        this.renderer.setup(meshAddr, quadCount, atlas, this.atlasMirror.sampler());
        this.activeBake = true;
    }

    /**
     * Render the per-block mesh once into the 16×16 cell at
     * {@code (faceX, faceY)} of the 3×2 grid, using {@code matrix} as the
     * cube-projection transform. Mirrors the per-face draw the GL bakery
     * does at {@link ModelTextureBakery#renderToStream}.
     */
    public void renderFace(int faceX, int faceY, Matrix4f matrix) {
        if (!this.activeBake) return; // beginBake bailed (atlas not ready)
        this.renderer.setViewport(faceX * this.width, faceY * this.height,
                this.width, this.height);
        this.renderer.render(matrix);
    }

    /**
     * Close the render pass and submit. On Metal,
     * {@code RenderBackend.submit()} waits for the command buffer to
     * complete (verified at {@code MetalRenderBackend.submit():691}), so the
     * Shared bake target is CPU-readable as soon as this returns.
     */
    public void endBake() {
        if (!this.activeBake) return;
        this.renderer.endPass();
        this.activeBake = false;
    }

    /**
     * Read the bake target via {@link MetalTexture#getBytes} and pack into
     * the legacy uvec2-per-pixel layout {@link GlViewCapture#emitToStream}
     * produces.
     */
    public void emitToStream(long destAddr) {
        // CPU read from Shared storage. Backend.submit() in endBake() already
        // waited for the GPU; getBytes is then just a memcpy.
        this.bakeTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.readbackBuffer);
        this.metaTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.metaReadbackBuffer);

        // Diagnostic: alpha distribution BEFORE dilation.
        long pixels = (long) this.totalW * this.totalH;
        int nonzeroAlphaPixels = 0;
        for (long i = 0; i < pixels; i++) {
            int rgba = MemoryUtil.memGetInt(this.readbackBuffer + i * 4L);
            if ((rgba & 0xFF000000) != 0) nonzeroAlphaPixels++;
        }
        if (nonzeroAlphaPixels > 0) {
            GlViewCapture.DIAG_BAKE_NONZERO_PIXEL_INVOCATIONS.incrementAndGet();
            if (nonzeroAlphaPixels * 2L > pixels) {
                GlViewCapture.DIAG_BAKE_FULL_ALPHA_INVOCATIONS.incrementAndGet();
            }
        } else {
            GlViewCapture.DIAG_BAKE_ZERO_ALPHA_INVOCATIONS.incrementAndGet();
        }

        if (nonzeroAlphaPixels > 0) {
            GlViewCapture.DIAG_BAKE_DILATE_RUNS.incrementAndGet();
            int filled = dilateOpaqueIntoGaps();
            GlViewCapture.DIAG_BAKE_DILATE_PIXELS_FILLED.addAndGet(filled);
        }

        // Pack face-major: for each face N, copy that face's 16×16 cell from
        // the bake target into a contiguous 256-pixel run at destAddr +
        // 2048*N. Within each face, pixels are row-major (y*16 + x).
        final int cellW = this.width;       // 16
        final int cellH = this.height;      // 16
        final int rowStrideBytes = this.totalW * 4;  // 48 * 4 = 192
        final int metaRowStrideBytes = this.totalW;  // 48 * 1 = 48
        for (int face = 0; face < 6; face++) {
            int faceX = face % 3;
            int faceY = face / 3;
            long faceDst = destAddr + (long) face * cellW * cellH * 8L;
            long faceSrcBase = this.readbackBuffer
                    + (long) faceY * cellH * rowStrideBytes
                    + (long) faceX * cellW * 4L;
            long metaSrcBase = this.metaReadbackBuffer
                    + (long) faceY * cellH * metaRowStrideBytes
                    + (long) faceX * cellW;
            for (int ly = 0; ly < cellH; ly++) {
                long srcRow = faceSrcBase + (long) ly * rowStrideBytes;
                long metaRow = metaSrcBase + (long) ly * metaRowStrideBytes;
                long dstRow = faceDst + (long) ly * cellW * 8L;
                for (int lx = 0; lx < cellW; lx++) {
                    int rgba = MemoryUtil.memGetInt(srcRow + lx * 4L);
                    int meta = MemoryUtil.memGetByte(metaRow + lx) & 0xFF;
                    long dstPx = dstRow + lx * 8L;
                    MemoryUtil.memPutInt(dstPx,     rgba);
                    // The second uvec2 component carries depth and stencil/tint metadata.
                    // wasPixelWritten(..., WRITE_CHECK_STENCIL, ...) checks (depth & 0xFF) != 0.
                    // Pack the tint bit from meta (attachment 1) at bit 7, and set bit 0 
                    // if the pixel was written (alpha > 0) to prevent the face being marked empty.
                    int value = ((meta & 1) << 7) | ((rgba >>> 24) != 0 ? 1 : 0);
                    MemoryUtil.memPutInt(dstPx + 4, value);
                }
            }
        }
    }

    private int dilateOpaqueIntoGaps() {
        final int w = this.totalW;
        final int cellW = this.width;
        final int cellH = this.height;
        int totalFilled = 0;
        for (int cellRow = 0; cellRow < 2; cellRow++) {
            for (int cellCol = 0; cellCol < 3; cellCol++) {
                final int x0 = cellCol * cellW;
                final int y0 = cellRow * cellH;
                long rSum = 0, gSum = 0, bSum = 0, aSum = 0;
                long mSum = 0;
                int opaqueCount = 0;
                for (int y = y0; y < y0 + cellH; y++) {
                    for (int x = x0; x < x0 + cellW; x++) {
                        long off = ((long) y * w + x) * 4L;
                        int p = MemoryUtil.memGetInt(this.readbackBuffer + off);
                        if ((p & 0xFF000000) == 0) continue;
                        rSum += (p      ) & 0xFF;
                        gSum += (p >>  8) & 0xFF;
                        bSum += (p >> 16) & 0xFF;
                        aSum += (p >>> 24);
                        mSum += MemoryUtil.memGetByte(this.metaReadbackBuffer + (long) y * w + x) & 0xFF;
                        opaqueCount++;
                    }
                }
                if (opaqueCount == 0) continue;
                int avgR = (int) (rSum / opaqueCount);
                int avgG = (int) (gSum / opaqueCount);
                int avgB = (int) (bSum / opaqueCount);
                int avgA = Math.max(1, (int) (aSum / opaqueCount));
                int fill = (avgA << 24) | (avgB << 16) | (avgG << 8) | avgR;
                byte fillMeta = (byte) (mSum * 2 > opaqueCount ? 1 : 0);

                for (int y = y0; y < y0 + cellH; y++) {
                    for (int x = x0; x < x0 + cellW; x++) {
                        long off = ((long) y * w + x) * 4L;
                        int p = MemoryUtil.memGetInt(this.readbackBuffer + off);
                        if ((p & 0xFF000000) != 0) continue;
                        MemoryUtil.memPutInt(this.readbackBuffer + off, fill);
                        MemoryUtil.memPutByte(this.metaReadbackBuffer + (long) y * w + x, fillMeta);
                        totalFilled++;
                    }
                }
            }
        }
        return totalFilled;
    }

    public void free() {
        this.renderer.shutdown();
        this.atlasMirror.free();
        if (this.bakeTarget != null) this.bakeTarget.free();
        if (this.metaTarget != null) this.metaTarget.free();
        if (this.readbackBuffer != 0L) MemoryUtil.nmemFree(this.readbackBuffer);
        if (this.metaReadbackBuffer != 0L) MemoryUtil.nmemFree(this.metaReadbackBuffer);
    }
}
