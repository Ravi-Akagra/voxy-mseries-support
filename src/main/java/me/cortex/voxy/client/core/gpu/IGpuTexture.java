package me.cortex.voxy.client.core.gpu;

/**
 * Abstraction over a GPU texture resource.
 */
public interface IGpuTexture extends IGpuResource {
    int id();
    int getWidth();
    int getHeight();
    int getLevels();
    int getFormat();
    int getType();

    IGpuTexture store(int format, int levels, int width, int height);

    /**
     * Cross-backend variant of {@link #store} that requests CPU-uploadable
     * storage. On GL the call is identical to {@link #store} (no Private/
     * Shared distinction). On Metal it allocates with `MTLStorageModeShared`
     * and drops the `MTLTextureUsageRenderTarget` flag so subsequent
     * {@link #uploadSubImage2D} works.
     *
     * Default impl delegates to {@link #store} — only Metal needs to
     * override.
     */
    default IGpuTexture storeUploadable(int format, int levels, int width, int height) {
        return this.store(format, levels, width, height);
    }

    IGpuTexture createView();

    /**
     * M13 chunk 3: cross-backend per-mip / per-slice view. Used by
     * HiZBuffer.buildMipChain when running on Metal — each pyramid level is
     * bound as both a sampling source (level i-1) and the depth-attachment
     * target (level i), without the GL BASE/MAX_LEVEL global-state hack.
     * The default implementation falls back to the parameter-less
     * {@link #createView} so callers that only care about same-format views
     * across all mips still work; only Metal needs to override.
     *
     * {@code baseLevel} is 0-indexed; {@code levelCount} of 1 selects a
     * single mip. Slice handling is currently fixed to slice 0 / 1 — array /
     * cube views would extend this API.
     */
    default IGpuTexture createView(int baseLevel, int levelCount) {
        return this.createView();
    }

    IGpuTexture name(String name);
    void assertAllocated();

    /**
     * Upload pixel data from {@code dataAddr} into the texture region
     * {@code (x, y, w, h)} at mip {@code level}. {@code format} and
     * {@code type} use OpenGL pixel-transfer constants (e.g. GL_RGBA +
     * GL_UNSIGNED_BYTE) describing the source layout; the bytes themselves
     * must already match what the texture's storage format expects.
     *
     * GL lowers to {@code glTextureSubImage2D}. Metal lowers to
     * {@code -[MTLTexture replaceRegion:mipmapLevel:withBytes:bytesPerRow:]} —
     * which requires the texture to have been created with Shared/Managed
     * storage (see {@code MetalTexture.storeUploadable}). The default
     * implementation throws — only textures backed by mutable, CPU-writable
     * storage need to support it.
     */
    default void uploadSubImage2D(int level, int x, int y, int w, int h,
                                  int format, int type, long dataAddr) {
        throw new UnsupportedOperationException(
                "uploadSubImage2D not supported on " + this.getClass().getName());
    }
}
