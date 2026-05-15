package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri;

/**
 * Mirrors MC's main-FBO depth attachment into a Shared-storage Metal R32F
 * texture so the Metal HiZ pyramid build can sample it without trying to
 * reach across the GL/Metal context boundary directly.
 *
 * <p>Same shape as {@link LightMapHelper#bindMetal} (M13 chunk 2): lazy-
 * allocate the mirror + a sampler, CPU-read MC's GL depth via the bind-then-
 * {@code nglGetTexImage} legacy path (Apple's GL caps at 4.1 — no DSA
 * {@code glGetTextureImage}), and upload via {@link IGpuTexture#uploadSubImage2D}.
 * Frame-id-gated so callers that hit this helper more than once per frame
 * share a single readback.
 *
 * <p>Notes on cost: the mirror size matches MC's viewport (e.g. 1920×1080×4 ≈
 * 8 MB per frame on a Retina-ish window). On Apple Silicon with unified memory
 * the per-frame readback is a memcpy-class operation; the staging buffer is
 * a single pinned native allocation that persists across frames. M13 chunk 3
 * uses this strictly as the source for {@code HiZBuffer.buildMipChain} — the
 * real "depth-test cull" pass (raster.vert + raster.frag) needs MC's depth
 * as an actual depth attachment, which is a separate IOSurface depth-bridge
 * milestone not covered by this helper.
 *
 * <p>Format choice: we mirror into {@code GL_DEPTH_COMPONENT32F} (mapped to
 * {@code MTLPixelFormatDepth32Float} on Apple Silicon) so the texture can
 * later double as a depth attachment if the pass shape ever evolves; for the
 * sampling-only path here, R32F would also work but format symmetry with
 * the HiZ pyramid's depth format makes the {@code textureGather} signature
 * line up cleanly.
 */
public final class DepthMirror {
    private final RenderBackend backend = RenderBackendFactory.get();
    private IGpuTexture mirror;
    private IGpuSampler sampler;
    private long stagingAddr;
    private long stagingSize;
    private int width;
    private int height;
    private int lastSyncedFrame = -1;

    /**
     * Ensure the mirror + sampler exist sized to {@code (width, height)},
     * then CPU-read MC's depth from {@code sourceFramebuffer}'s depth
     * attachment into the mirror at most once per {@code frameId}. Returns
     * the mirror's {@link IGpuTexture} for the caller to bind on its encoder.
     *
     * <p>Throws if invoked on a non-Metal backend — GL goes through
     * {@code HiZBuffer.buildMipChain}'s raw-GL source-bind path.
     */
    public IGpuTexture syncFromMC(int sourceFramebuffer, int width, int height, int frameId) {
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException(
                    "DepthMirror is Metal-only — GL HiZ binds MC's depth directly.");
        }
        ensureResources(width, height);
        if (frameId == this.lastSyncedFrame) {
            return this.mirror;
        }
        this.lastSyncedFrame = frameId;

        // Apple's GL context caps at 4.1 — the DSA
        // `glGetNamedFramebufferAttachmentParameteri` (GL 4.5) is unavailable
        // even when the rest of MC's GL state lives in this context. Use the
        // legacy bind-then-query path against GL_READ_FRAMEBUFFER (GL 3.0)
        // and restore the previous binding so we don't perturb MC's compositor
        // mid-frame. Matches the LightMapHelper.syncFromMc pattern.
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int mcDepthTex;
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFramebuffer);
            mcDepthTex = glGetFramebufferAttachmentParameteri(
                    GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
        if (mcDepthTex == 0) {
            // No depth attached — leave the mirror at whatever it last had.
            // Callers should treat the HiZ pyramid as "no occlusion data"
            // for this frame (same as the ensureAllocated zero-init path).
            return this.mirror;
        }

        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, mcDepthTex);
            // MC's depth attachment is typically D32F or D24S8 depending on
            // the post-FX target. Reading as GL_DEPTH_COMPONENT + GL_FLOAT
            // normalises both into the staging buffer; the upload then
            // copies that into a Depth32Float Metal texture. The legacy
            // nglGetTexImage path is the only readback shape Apple's GL 4.1
            // exposes — DSA glGetTextureImage is GL 4.5.
            org.lwjgl.opengl.GL11C.nglGetTexImage(
                    GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, this.stagingAddr);
        } finally {
            glBindTexture(GL_TEXTURE_2D, prevBinding);
            glActiveTexture(prevActive);
        }

        this.mirror.uploadSubImage2D(0, 0, 0, width, height,
                GL_DEPTH_COMPONENT, GL_FLOAT, this.stagingAddr);
        return this.mirror;
    }

    public IGpuTexture texture() { return this.mirror; }
    public IGpuSampler sampler() { return this.sampler; }

    private void ensureResources(int width, int height) {
        if (this.mirror == null || this.width != width || this.height != height) {
            if (this.mirror != null) this.mirror.free();
            this.width = width;
            this.height = height;
            // Shared storage so uploadSubImage2D is allowed; the texture is
            // sampled-only on the HiZ build (no RenderTarget usage needed).
            MetalTexture tex = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
            tex.storeUploadable(GL_DEPTH_COMPONENT32F, 1, width, height);
            tex.name("Voxy.MCDepthMirror");
            this.mirror = tex;

            long needed = (long) width * height * 4L; // 4 bytes per pixel for R32F
            if (this.stagingAddr != 0L && needed != this.stagingSize) {
                MemoryUtil.nmemFree(this.stagingAddr);
                this.stagingAddr = 0L;
            }
            if (this.stagingAddr == 0L) {
                this.stagingAddr = MemoryUtil.nmemAllocChecked(needed);
                this.stagingSize = needed;
            }
        }
        if (this.sampler == null) {
            this.sampler = this.backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST)
                    .mipFilter(SamplerDesc.MipFilter.NEAREST)
                    .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                    .label("Voxy.MCDepthSampler")
                    .build());
        }
    }

    public void free() {
        if (this.mirror != null) { this.mirror.free(); this.mirror = null; }
        if (this.sampler != null) { this.sampler.close(); this.sampler = null; }
        if (this.stagingAddr != 0L) {
            MemoryUtil.nmemFree(this.stagingAddr);
            this.stagingAddr = 0L;
            this.stagingSize = 0L;
        }
    }
}
