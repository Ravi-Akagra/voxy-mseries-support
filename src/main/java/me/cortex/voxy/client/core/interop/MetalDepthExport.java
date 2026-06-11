package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.gpu.VertexLayout;

/**
 * Metal-side depth export for the Iris gbuffer injection: a tiny fullscreen
 * pass that copies the LOD render pass's depth attachment into an R32F color
 * target (the depth {@link IOSurfaceBridge}). Needed because GL can't sample
 * a Metal depth texture directly, but an R32F color image crosses the
 * IOSurface boundary as a plain float rectangle texture which the GL-side
 * compositor unprojects back into MC clip space.
 *
 * Pipeline shape follows {@link me.cortex.voxy.client.core.rendering.util.HiZBuffer}'s
 * blit: GLSL via {@link ShaderLoader} → runtime MSL transpile, gl_VertexID
 * driven fullscreen geometry ({@code post/fullscreen2.vert}'s 4-vertex
 * strip), {@link VertexLayout#EMPTY}, NEAREST sampler. The pass is
 * color-only (CLEAR 0 then draw — no depth attachment, the fragment writes
 * no depth) and is encoded into the frame's already-open command buffer, so
 * it rides the existing end-of-frame {@code submit()} without extra waits.
 */
public final class MetalDepthExport {

    /** GL_RGBA8 — depth crosses the bridge 24-bit-packed in the proven BGRA8 surface format. */
    private static final int GL_RGBA8 = 0x8058;

    private final IGpuPipeline pipeline;
    private final IGpuSampler sampler;

    public MetalDepthExport(RenderBackend backend) {
        this.sampler = backend.createSampler(SamplerDesc.builder()
                .filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST)
                .mipFilter(SamplerDesc.MipFilter.NEAREST)
                .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                .label("metalDepthExportSampler")
                .build());
        // No depth attachment on the export pass, so depth test/write must be
        // fully off (Metal rejects encoder depth-write without an attachment);
        // PipelineState.DEFAULT is exactly that (DISABLED depth, OPAQUE blend,
        // NO_CULL).
        this.pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                ShaderLoader.parse("voxy:post/fullscreen2.vert"),
                ShaderLoader.parse("voxy:post/metal_depth_export.frag"),
                null,                       // no defines
                null, null,                  // no MSL — runtime compiler produces it
                null, null,                  // no SPIRV
                GL_RGBA8,
                VertexLayout.EMPTY,
                PipelineState.DEFAULT,
                "MetalDepthExport"));
    }

    /**
     * Encode the export pass: sample {@code depthTex} (the LOD pass's depth
     * attachment) at slot 0 and write raw depth into {@code targetR32F}
     * (the depth bridge's texture). Caller must invoke AFTER the LOD render
     * pass closed and BEFORE {@code backend.submit()} so the pass lands in
     * the same command buffer (encoder order on one buffer is the
     * cross-encoder barrier Metal gives us for free).
     */
    public void render(RenderBackend backend, IGpuTexture depthTex, IGpuTexture targetR32F, int width, int height) {
        var pass = RenderPassDesc.builder(width, height)
                .clearColor(targetR32F, 0.0f, 0.0f, 0.0f, 0.0f)
                .build();
        try (RenderEncoder enc = backend.beginRenderPass(pass)) {
            enc.setPipeline(this.pipeline);
            enc.setTexture(0, depthTex);
            enc.setSampler(0, this.sampler);
            enc.setViewport(0, 0, width, height, 0.0f, 1.0f);
            enc.draw(RenderEncoder.PRIMITIVE_TRIANGLE_STRIP, 0, 4, 1, 0);
        }
    }

    public void close() {
        this.sampler.close();
        this.pipeline.close();
    }
}
