package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetTexLevelParameteri;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;

/**
 * M13 chunk 1 foundation (2026-05-13): mirrors MC's block atlas GL texture
 * into a Shared-storage Metal texture so the future Metal-native bakery can
 * sample it without crossing the GL ↔ Metal context boundary.
 *
 * <p>The bakery currently auto-gates off on Metal because Apple's GL stack
 * crashes inside the pixel-processor on FBO readback (see
 * {@code project_m12_closed_m13_in_progress} memory). The replacement path
 * keeps the entire bake on the Metal side: the bake target is a Metal
 * texture; this helper provides the source.
 *
 * <p>Lifecycle: lazy on first call to {@link #syncMetal}, single CPU readback
 * of MC's atlas via {@code nglGetTexImage} (Apple GL 4.1 — no DSA available),
 * upload to a Shared Metal texture via {@link IGpuTexture#uploadSubImage2D}.
 * Atlas dimensions are queried at first sync. Re-sync is gated by the source
 * GL texture id — if a resource pack reload swaps the atlas, the mirror gets
 * rebuilt; otherwise it's a free hot path. Mipmap levels are out of scope
 * for the MVP — level 0 only; the bakery's `textureGrad` samples will lose
 * a small amount of distance smoothness, traded for not having to plumb
 * level dimensions through `glGetTexLevelParameteri` per level.
 *
 * <p>Not stenciled for thread-safety — the bakery runs on the render thread
 * inside MC's GL context, same as this helper.
 *
 * <p>Memory: the atlas is typically 1024×1024 or 2048×2048 RGBA8 — 4–16 MB.
 * The staging buffer is a single pinned native allocation; resizes on atlas
 * dimension change.
 */
public final class AtlasMirror {
    private final RenderBackend backend = RenderBackendFactory.get();
    private IGpuTexture mirror;
    private IGpuSampler sampler;
    private long stagingAddr;
    private long stagingSize;
    private int width;
    private int height;
    private int lastSyncedGlId = -1;

    /**
     * Sync the mirror from MC's GL atlas texture {@code mcAtlasGlId}. Returns
     * the Metal-side {@link IGpuTexture} the bakery should bind. Cheap when
     * the source id hasn't changed since the last call (single int compare).
     *
     * <p>Throws on non-Metal backends — the GL bakery samples MC's atlas
     * directly, no mirror needed.
     */
    public IGpuTexture syncMetal(int mcAtlasGlId) {
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException(
                    "AtlasMirror is Metal-only — GL bakery samples MC's atlas directly.");
        }
        if (mcAtlasGlId == 0) {
            return this.mirror; // possibly null on the very first call before MC is ready
        }
        if (mcAtlasGlId == this.lastSyncedGlId && this.mirror != null) {
            return this.mirror;
        }

        // Capture MC's current 2D binding so we can restore it.
        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, mcAtlasGlId);
            int w = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int h = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) {
                return this.mirror; // atlas not yet ready
            }
            ensureResources(w, h);
            // Read level 0 only. The bakery shaders use textureGrad on the
            // atlas; without mipmaps the LOD selection effectively snaps to
            // the base level. For the MVP this matches what the per-block
            // bake actually needs (each face samples a small UV region at
            // close to 1:1 texel-to-pixel ratio inside the 16×16 bake cell).
            org.lwjgl.opengl.GL11C.nglGetTexImage(
                    GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, this.stagingAddr);
        } finally {
            glBindTexture(GL_TEXTURE_2D, prevBinding);
            glActiveTexture(prevActive);
        }

        this.mirror.uploadSubImage2D(0, 0, 0, this.width, this.height,
                GL_RGBA, GL_UNSIGNED_BYTE, this.stagingAddr);
        this.lastSyncedGlId = mcAtlasGlId;
        return this.mirror;
    }

    public IGpuTexture texture() { return this.mirror; }
    public IGpuSampler sampler() { return this.sampler; }
    public int width()  { return this.width; }
    public int height() { return this.height; }

    private void ensureResources(int w, int h) {
        if (this.mirror == null || this.width != w || this.height != h) {
            if (this.mirror != null) this.mirror.free();
            this.width = w;
            this.height = h;
            MetalTexture tex = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
            tex.storeUploadable(GL_RGBA8, 1, w, h);
            tex.name("Voxy.MCBlockAtlasMirror");
            this.mirror = tex;

            long needed = (long) w * h * 4L;
            if (this.stagingAddr != 0L && needed != this.stagingSize) {
                MemoryUtil.nmemFree(this.stagingAddr);
                this.stagingAddr = 0L;
            }
            if (this.stagingAddr == 0L) {
                this.stagingAddr = MemoryUtil.nmemAllocChecked(needed);
                this.stagingSize = needed;
            }
            // Mirror dimensions changed: force a re-upload on next call.
            this.lastSyncedGlId = -1;
        }
        if (this.sampler == null) {
            // Match MC's block-atlas sampler conventions: NEAREST mip filter
            // (Voxy's terrain shader uses textureGrad which needs derivatives
            // but the bakery shaders typically use textureLod). MAX/MIN are
            // LINEAR so the bake doesn't look pixelated when projected
            // through the 6-face cube transforms.
            this.sampler = this.backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.LINEAR, SamplerDesc.Filter.LINEAR)
                    .mipFilter(SamplerDesc.MipFilter.NEAREST)
                    .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                    .label("Voxy.MCBlockAtlasSampler")
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
