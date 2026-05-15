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
 * The MVP doesn't write a metadata attachment (single RGBA8 colour target
 * only), so the second uvec2 component is zero — depth-test-driven downstream
 * features (e.g. alpha-discard variant flags) are lost in this round. Real
 * RGBA colour pixels DO flow through, which is the big visual win versus the
 * VOXY_NO_ATLAS hash-colour placeholder.
 *
 * <p>The single-attachment shape sidesteps a Metal-side
 * {@code RenderPassDesc.colorAttachment(N, ...)} multi-binding investigation
 * — the bakery's MVP can ship without it, and a follow-up can swap to a
 * two-attachment variant once we verify the descriptor supports it on Metal.
 */
public final class MetalViewCapture {
    private final int width;        // per-face cell width
    private final int height;       // per-face cell height
    private final int totalW;       // 3 * width
    private final int totalH;       // 2 * height

    private final RenderBackend backend;
    private final MetalTexture bakeTarget;
    private final AtlasMirror atlasMirror;
    private final MetalBudgetBufferRenderer renderer;
    private final long readbackBuffer;
    private final long readbackBytes;
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

        this.atlasMirror = new AtlasMirror();
        this.renderer = new MetalBudgetBufferRenderer();

        this.readbackBytes = (long) this.totalW * this.totalH * 4L;
        this.readbackBuffer = MemoryUtil.nmemAllocChecked(this.readbackBytes);
    }

    /**
     * Open the render pass with a clear-to-zero load and stage the mesh +
     * atlas + sampler bindings. {@code meshAddr} must point at packed
     * quad vertex data in the same {@link BudgetBufferRenderer#VERTEX_FORMAT_SIZE}-stride
     * layout the GL path uses; {@code mcAtlasGlId} is MC's
     * {@code textures/atlas/blocks.png} GL texture id (the
     * {@link AtlasMirror} CPU-reads it via {@code nglGetTexImage} and uploads
     * to the Metal mirror only when the id changes).
     */
    /**
     * Clear the bake target to transparent black. Opens and closes a tiny
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
        // this into a single clearColor command on the bake target.
        try (var enc = this.backend.beginRenderPass(
                me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(this.totalW, this.totalH)
                        .clearColor(this.bakeTarget, 0f, 0f, 0f, 0f)
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
        this.renderer.beginPass(this.bakeTarget, this.totalW, this.totalH, clear);
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
     * produces. The metadata (second uvec2 component) is zero — see class
     * Javadoc for the MVP trade-off.
     */
    public void emitToStream(long destAddr) {
        // CPU read from Shared storage. Backend.submit() in endBake() already
        // waited for the GPU; getBytes is then just a memcpy.
        this.bakeTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.readbackBuffer);
        // Pack RGBA bytes (already little-endian RGBA8 in memory) as uvec2.x;
        // uvec2.y is zero — no depth/tint metadata in this MVP.
        long src = this.readbackBuffer;
        long dst = destAddr;
        long pixels = (long) this.totalW * this.totalH;
        for (long i = 0; i < pixels; i++) {
            int rgba = MemoryUtil.memGetInt(src);
            src += 4;
            MemoryUtil.memPutInt(dst,     rgba);
            MemoryUtil.memPutInt(dst + 4, 0);
            dst += 8;
        }
    }

    public void free() {
        this.renderer.shutdown();
        this.atlasMirror.free();
        if (this.bakeTarget != null) this.bakeTarget.free();
        if (this.readbackBuffer != 0L) MemoryUtil.nmemFree(this.readbackBuffer);
    }
}
