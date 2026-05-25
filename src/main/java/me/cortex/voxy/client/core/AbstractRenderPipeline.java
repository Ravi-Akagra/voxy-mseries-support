package me.cortex.voxy.client.core;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL42.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static me.cortex.voxy.client.core.gl.GLCompat.bindTextureUnit;

public abstract class AbstractRenderPipeline extends TrackedObject {
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthMaskBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
    private final FullscreenBlit depthSetBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth0.frag");
    private final FullscreenBlit depthCopy = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth_copy.frag");

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    protected final boolean deferTranslucency;

    /**
     * Whether this pipeline wants environmental fog mixed into LOD terrain.
     * On GL the fog pass lives in {@link #finish(Viewport, int, int, int)};
     * on Metal it's applied per-fragment by quads.frag's USE_ENV_FOG branch
     * (M13 chunk 5) using fog params packed into the SceneUniform SSBO.
     * Subclasses that drive an env-fog config flag override this. Defaults
     * to false so unrelated pipelines (e.g. Iris) don't inject the define.
     */
    public boolean useEnvFog() {
        return false;
    }

    private static final int DEPTH_SAMPLER = glGenSamplers();
    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }

    protected AbstractRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;
    }

    //Allows pipelines to configure model baking system
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {//Stupid java ordering not allowing something pre super
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    //Called before the pipeline starts running, used to update uniforms etc
    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport);
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    /** IOSurface bridge for the Metal render path. Lazy-allocated on first non-GL frame. */
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge;
    private int metalBridgeWidth;
    private int metalBridgeHeight;
    /**
     * Depth texture for {@code runPipelineMetal}'s render pass. Lazy-allocated
     * to match the bridge size so depth-tested LOD terrain self-occludes correctly.
     * Lives in Metal-side memory (the bridge's color is shared with GL via
     * IOSurface; the depth has no GL consumer so it stays Metal-private).
     */
    private me.cortex.voxy.client.core.gpu.IGpuTexture metalDepthTex;
    private int metalDepthWidth;
    private int metalDepthHeight;
    /**
     * M13 chunk 3: Shared-storage mirror of MC's main-FBO depth, refreshed
     * per frame via {@code glGetTexImage}. Sourced by
     * {@code HiZBuffer.buildMipChain(IGpuTexture, ...)} so the HiZ pyramid
     * carries real occlusion data instead of the zero-init stub from M12.
     * Lazy — allocated on the first Metal frame that has a non-zero sized
     * source framebuffer.
     */
    private me.cortex.voxy.client.core.rendering.util.DepthMirror metalDepthMirror;
    /** Animation counter for the placeholder Metal render — replaced by real Voxy output incrementally. */
    private int metalFrame;

    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            // Metal path — M12 chunk 6 step 1 runs the migrated compute side
            // of the pipeline (DownloadStream + AsyncNodeManager.tick +
            // NodeCleaner.tick + HOT.doTraversal + MDIC.buildDrawCalls) but
            // still clears the bridge for visual feedback instead of issuing
            // real LOD draws. Subsequent steps migrate renderTerrain /
            // renderTranslucent and replace the clear with encoder draws so
            // Voxy's actual LOD content reaches the IOSurface bridge.
            this.runPipelineMetal(viewport, sourceFrameBuffer);
            return;
        }
        int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        rs.renderOpaque(viewport);
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            this.innerPrimaryWork(viewport, depthTexture);
        }
        if (occlusionDebug<=1) {
            rs.buildDrawCalls(viewport);
        }
        rs.renderTemporal(viewport);

        this.postOpaquePreTranslucent(viewport);

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }

        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    /** Push-block binding for depth_copy.frag's scaleFactor (see Push struct in the shader). */
    private static final int DEPTH_COPY_PUSH_BINDING = 14;
    /** Push-block binding for blit_texture_depth_cutout.frag's PushMats (invProj + proj). */
    private static final int BLIT_DEPTH_MATS_PUSH_BINDING = 14;
    private static final int BLIT_DEPTH_MATS_PUSH_SIZE = 4 * 4 * 4 * 2; // two mat4s

    protected void initDepthStencil(int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, 1.0f, 1);
        // using blit to copy depth from mismatched depth formats is not portable so instead a full screen pass is performed for a depth copy
        // the mismatched formats in this case is the d32 to d24s8
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        this.depthCopy.bind();
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        bindTextureUnit(0, depthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        // Push scaleFactor (vec2) into the Push UBO declared at DEPTH_COPY_PUSH_BINDING.
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long addr = stack.nmalloc(8);
            MemoryUtil.memPutFloat(addr,     ((float) width) / srcWidth);
            MemoryUtil.memPutFloat(addr + 4, ((float) height) / srcHeight);
            this.depthCopy.setBytes(DEPTH_COPY_PUSH_BINDING, addr, 8);
        }
        glColorMask(false,false,false,false);
        this.depthCopy.blit();

        /*
        if (Capabilities.INSTANCE.isMesa){
            glClearStencil(1);
            glClear(GL_STENCIL_BUFFER_BIT);
        }*/

        //This whole thing is hell, we basicly want to create a mask stenicel/depth mask specificiclly
        // in theory we could do this in a single pass by passing in the depth buffer from the sourceFrambuffer
        // but the current implmentation does a 2 pass system
        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_NOTEQUAL);//If != 1 pass
        //We do here
        this.depthMaskBlit.blit();
        glDisable(GL_DEPTH_TEST);

        //Blit depth 0 where stencil is 0
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 0, 0xFF);

        this.depthSetBlit.blit();

        glDepthFunc(GL_LEQUAL);
        glColorMask(true,true,true,true);

        //Make voxy terrain render only where there isnt mc terrain
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        // at this point the dst frame buffer doesn't have a stencil attachment so we don't need to keep the stencil test on for the blit
        // in the worst case the dstFB does have a stencil attachment causing this pass to become 'corrupted'
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        bindTextureUnit(0, srcDepthTex);

        // Push PushMats { mat4 invProjMat; mat4 projMat; } into the UBO at BLIT_DEPTH_MATS_PUSH_BINDING.
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long addr = stack.nmalloc(BLIT_DEPTH_MATS_PUSH_SIZE);
            new Matrix4f(viewport.MVP).invert().getToAddress(addr);                 // invProjMat
            targetTransform.getToAddress(addr + 4 * 4 * 4);                          // projMat
            blitShader.setBytes(BLIT_DEPTH_MATS_PUSH_BINDING, addr, BLIT_DEPTH_MATS_PUSH_SIZE);
        }

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {

        //Compute the mip chain
        viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            //Tick download stream
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            //glFlush();

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());//Probably do this here??

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.fb.free();
        this.sectionRenderer.free();
        this.depthMaskBlit.delete();
        this.depthSetBlit.delete();
        this.depthCopy.delete();
        if (this.metalBridge != null) {
            this.metalBridge.close();
            this.metalBridge = null;
        }
        if (this.metalDepthTex != null) {
            this.metalDepthTex.free();
            this.metalDepthTex = null;
        }
        if (this.metalDepthMirror != null) {
            this.metalDepthMirror.free();
            this.metalDepthMirror = null;
        }
        super.free0();
    }

    /**
     * Metal-path runPipeline (M12 chunk 6, evolving). Today runs the migrated
     * compute side end-to-end on Metal — every stage in this method is either
     * already encoder-backed or skipped with a Metal-aware substitute — and
     * still clears the IOSurface bridge as visual confirmation that the
     * pipeline executed. Real LOD draws land in subsequent chunk 6 steps when
     * renderTerrain / renderTranslucent migrate to RenderEncoder.
     * <p>
     * Compared to the GL {@link #runPipeline}, the Metal path currently
     * skips: {@link #setup} (depth-stencil FBO copy uses raw GL),
     * {@link me.cortex.voxy.client.core.rendering.util.HiZBuffer#buildMipChain}
     * (partial GL — see chunk 6 step 2), the FrEx work loop wrapper, the
     * raw {@code glMemoryBarrier} inside {@link #innerPrimaryWork}, the
     * post-opaque SSAO compute, and {@link #finish}. The compute pipeline
     * (HOT traversal + buildDrawCalls' 5 prepasses) runs in full.
     */
    private void runPipelineMetal(Viewport<?> viewport, int sourceFrameBuffer) {
        int fbw = viewport.width;
        int fbh = viewport.height;
        if (fbw <= 0 || fbh <= 0) return;
        var backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();
        if (!(backend instanceof me.cortex.voxy.client.core.metal.MetalRenderBackend mrb)) return;

        // 1) Allocate the IOSurface bridge sized to MC's framebuffer. The
        //    bridge is the cross-context handle: Metal renders into the
        //    backing MTLTexture, IOSurfaceBridgeCompositor blits it into MC's
        //    main RT via a CGL-bound GL_TEXTURE_RECTANGLE source FBO.
        if (this.metalBridge == null || this.metalBridgeWidth != fbw || this.metalBridgeHeight != fbh) {
            if (this.metalBridge != null) this.metalBridge.close();
            this.metalBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                    mrb.device(), fbw, fbh,
                    me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
            this.metalBridgeWidth  = fbw;
            this.metalBridgeHeight = fbh;
        }

        // 2) Ensure the HiZ texture is allocated so HOT can bind it. The
        //    encoder-driven mip-chain build is wired up but parked: an
        //    initial integration test (2026-05-13) showed LOD chunks
        //    disappearing at the horizon when HOT samples the populated
        //    pyramid — likely a Depth32Float_Stencil8 sampling-as-sampler2D
        //    mismatch versus the GL path's pre-processed depth (see
        //    initDepthStencil's stencil-mask dance that zeros sky regions).
        //    Revert to M12's zero-init pyramid until that's debugged so
        //    HOT trivially passes every frustum-visible section. The
        //    DepthMirror class + MetalNative.mtlTextureNewSubresourceView
        //    JNI + IGpuTexture.createView(level, count) all stay committed
        //    for the follow-up.
        viewport.hiZBuffer.ensureAllocated(viewport.width, viewport.height);

        // 2b) Lazy-allocate the Metal-side depth texture for our render pass.
        //     D24S8 matches AbstractRenderPipeline.fb's GL format; the encoder
        //     pass clears it to 1.0 (far plane) each frame.
        if (this.metalDepthTex == null || this.metalDepthWidth != fbw || this.metalDepthHeight != fbh) {
            if (this.metalDepthTex != null) this.metalDepthTex.free();
            this.metalDepthTex = backend.createTexture()
                    .store(org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8, 1, fbw, fbh)
                    .name("VoxyMetalDepth");
            this.metalDepthWidth = fbw;
            this.metalDepthHeight = fbh;
        }

        // 3) Compute side — copy of innerPrimaryWork's body minus the GL bits
        //    (HiZBuffer.buildMipChain, raw glMemoryBarrier, FrEx loop). Each
        //    sub-stage is already encoder-backed (commits 0b963825, 68734b78,
        //    5dcbc645, 89b35814 for HOT's last raw-GL gaps).
        me.cortex.voxy.client.core.rendering.util.DownloadStream.INSTANCE.tick();
        this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
        this.nodeCleaner.tick(this.traversal.getNodeBuffer());
        this.traversal.doTraversal(viewport);

        // 4) Per-frame draw-command generation — all 5 MDIC compute prepasses
        //    (prep / cull-stub / commandGen / prefixSum / translucentGen) now
        //    flow through ComputeEncoder (chunks 1–5). Raw cast matches the
        //    GL path (line 119) — the renderer's viewport generic is set at
        //    construction by RenderPipelineFactory and we trust the pairing.
        @SuppressWarnings({"rawtypes", "unchecked"})
        AbstractSectionRenderer rs = (AbstractSectionRenderer) this.sectionRenderer;
        rs.buildDrawCalls(viewport);

        // M13 2026-05-14 baseInstance workaround: flush + wait so the compute
        // prepasses (commandGen writes drawCallBuffer's baseInstance field)
        // complete before the render pass starts. MetalRenderEncoder.
        // drawIndexedIndirect needs to CPU-read drawCallBuffer per draw to
        // push baseInstance via setVertexBytes (drawIndexedPrimitives:
        // indirectBuffer: doesn't propagate it natively). Without this
        // submit() the CPU sees stale data from the prior frame.
        backend.submit();

        // M13 2026-05-15 Layer B diagnostic — read back the renderList and
        // drawCountCallBuffer values so we can see exactly how many sections
        // HOT enqueued for rendering, and what dispatch parameters prep.comp
        // wrote for cmdgen. If sectionCount is small, the upstream traversal
        // is the bottleneck. If sectionCount is big but cmdGenDispatchX is
        // small, prep.comp's read of sectionCount is racing or stale.
        // (Logged once every 1800 frames ≈ 30s @60fps so it doesn't spam.)
        if (this.metalFrame % 1800 == 1
                && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
            int renderListSectionCount = -1;
            int cmdGenDispatchX = -1;
            int cmdGenDispatchY = -1;
            int cmdGenDispatchZ = -1;
            int opaqueDrawCount = -1;
            int translucentDrawCount = -1;
            int temporalOpaqueDrawCount = -1;
            if (mv.getRenderList() instanceof me.cortex.voxy.client.core.metal.MetalBuffer rl) {
                renderListSectionCount = org.lwjgl.system.MemoryUtil.memGetInt(rl.getContentsPtr());
            }
            if (mv.drawCountCallBuffer instanceof me.cortex.voxy.client.core.metal.MetalBuffer dc) {
                long p = dc.getContentsPtr();
                cmdGenDispatchX        = org.lwjgl.system.MemoryUtil.memGetInt(p +  0);
                cmdGenDispatchY        = org.lwjgl.system.MemoryUtil.memGetInt(p +  4);
                cmdGenDispatchZ        = org.lwjgl.system.MemoryUtil.memGetInt(p +  8);
                opaqueDrawCount        = org.lwjgl.system.MemoryUtil.memGetInt(p + 12);
                translucentDrawCount   = org.lwjgl.system.MemoryUtil.memGetInt(p + 16);
                temporalOpaqueDrawCount = org.lwjgl.system.MemoryUtil.memGetInt(p + 20);
            }
            int topNodeCount = this.traversal.getTopNodeCount();
            int firstDispatchSize = (topNodeCount + 127) >> 7;
            Logger.info(String.format(
                    "[Metal-LayerB f=%d] topNodeCount=%d firstDispatchSize=%d renderList.sectionCount=%d cmdGenDispatch=(%d,%d,%d) draws opaque=%d translucent=%d temporal=%d",
                    this.metalFrame, topNodeCount, firstDispatchSize,
                    renderListSectionCount,
                    cmdGenDispatchX, cmdGenDispatchY, cmdGenDispatchZ,
                    opaqueDrawCount, translucentDrawCount, temporalOpaqueDrawCount));
        }

        // 5) Render pass against bridge color + Voxy-owned depth. Clears both
        //    each frame (no MC-depth import on Metal yet, so we render every
        //    LOD chunk against a fresh depth buffer — they self-occlude but
        //    don't z-test against MC's foreground terrain). Inside the pass
        //    we call MDIC's Metal-aware renderOpaque equivalent to issue
        //    the actual LOD draws via the RenderEncoder API. 2026-05-14
        //    revert: alpha back to 1.0 (M12-stable) since the alpha-composite
        //    shader path made LOD invisible in-game. With the blit compositor
        //    the clear colour shows through in non-LOD areas (sky no longer
        //    visible through them); Sodium overdraws its near terrain on top.
        // 2026-05-14 diagnostic finding: with magenta clear, user reports
        // "Veo magenta en todo (excepto terreno MC cercano)" — confirming
        // the IOSurface bridge + blit-to-MC-mainRT path works end-to-end.
        // The reason LOD chunks are invisible is the MDIC opaque/temporal/
        // translucent draws are NOT producing visible pixels in the bridge.
        // Likely causes: drawIndexedIndirect counts are zero (HOT culling /
        // commandGen prepass), or vertex shader clips all geometry. Restored
        // dark clear so day-to-day play isn't magenta-flooded; the rendering
        // pipeline diagnosis continues in MDIC + buildDrawCalls.
        float clearR = 0.02f;
        float clearG = 0.02f;
        float clearB = 0.04f;
        if (viewport.fogParameters != null) {
            clearR = viewport.fogParameters.red();
            clearG = viewport.fogParameters.green();
            clearB = viewport.fogParameters.blue();
        }
        // Fix A (2026-05-17): clear bridge alpha to 0 so pixels we never
        // touched stay transparent. The composite shader's discard then drops
        // them and MC's sky / Sodium's near terrain show through where Voxy
        // didn't draw — fixes the underwater "todo se vuelve color de agua"
        // and the sky-overwrite that came with the alpha=1 blit path.
        // Pixels Voxy actually draws still get alpha=1 via VOXY_FORCE_OPAQUE_ALPHA
        // (opaque pipeline) or the natural translucent alpha (translucent pipe),
        // so they survive the discard and overwrite MC's framebuffer as before.
        var pass = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh)
                .clearColor(this.metalBridge.asGpuTexture(), clearR, clearG, clearB, 0.0f)
                .clearDepth(this.metalDepthTex, 1.0f)
                .build();
        try (var enc = backend.beginRenderPass(pass)) {
            enc.setViewport(0, 0, fbw, fbh, 0.0f, 1.0f);
            // M12 close — invoke MDIC's Metal-aware draws in the same order
            // GL runPipeline uses (opaque → temporal → translucent). Iris is
            // GL-gated upstream so on non-GL the section renderer is always
            // an MDICSectionRenderer (and its viewport an MDICViewport —
            // typing follows from the RenderPipelineFactory pairing).
            // postOpaquePreTranslucent (SSAO) is skipped on Metal — SSAO
            // is M13 polish; the LOD result is intelligible without it.
            if (this.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdic
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
                mdic.renderOpaqueMetal(enc, mv);
                mdic.renderTemporalMetal(enc, mv);
                if (!this.deferTranslucency) {
                    mdic.renderTranslucentMetal(enc, mv);
                }
            }
        }
        backend.submit();
        this.metalFrame++;

        // M13 diagnostic logging: every ~10s (600 frames at 60fps) report what
        // the Metal render path is actually doing — section count loaded into
        // the GPU geometry buffer, MB used, whether AsyncNodeManager has
        // pending work, and the camera position the LOD ring follows. This
        // is the equivalent of the F3 voxy panel for users who can't easily
        // capture it. Drops to silent once the data lines up cleanly.
        if (this.metalFrame % 600 == 1) {
            int sectionCount = -1;
            var geomData = this.sectionRenderer.getGeometryManager();
            if (geomData instanceof me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData bgd) {
                sectionCount = bgd.getSectionCount();
            }
            long usedMb = this.nodeManager.getUsedGeometryCapacity() / (1L << 20);
            long capMb  = this.nodeManager.getGeometryCapacity()    / (1L << 20);
            boolean hasWork = this.nodeManager.hasWork();
            Logger.info(String.format(
                    "[Metal-DIAG f=%d] sections=%d  geom=%d/%d MB  nodeMgr.hasWork=%s  cam=(%.0f, %.0f, %.0f)",
                    this.metalFrame, sectionCount, usedMb, capMb, hasWork,
                    viewport.cameraX, viewport.cameraY, viewport.cameraZ));
            Logger.info(String.format(
                    "[Metal-CHAIN f=%d] ingestCall=%d  ingestNoLight=%d  ingestQ=%d  ingestProc=%d  rawIngest=%d  worldEvt=%d  topLvlAdd=%d  geomResult=%d",
                    this.metalFrame,
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_CALL_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_NO_LIGHTING_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_ENQUEUE_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_PROCESS_COUNT.get(),
                    me.cortex.voxy.common.world.service.VoxelIngestService.DIAG_RAW_INGEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_WORLD_EVENT_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TOP_LEVEL_ADD_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_GEOMETRY_RESULT_COUNT.get()));
            Logger.info(String.format(
                    "[Metal-TICK  f=%d] tickWithResults=%d  tickWithUploads=%d  lastResultSectionCount=%d  basicSectionCount=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TICK_WITH_RESULTS_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_TICK_WITH_UPLOADS_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager.DIAG_LAST_TICK_SECTION_COUNT.get(),
                    sectionCount));
            Logger.info(String.format(
                    "[Metal-PGR   f=%d] notInMap=%d  reqSingle=%d  reqChild=%d  innerLeaf=%d  notWatched=%d  uploadEmpty=%d  emptyKids=%d  emptyNoKids=%d  uploadReal=%d  topNoDataDeferred=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_NOT_IN_MAP.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_REQUEST_SINGLE.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_REQUEST_CHILD.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_INNER_LEAF.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_PGR_NOT_WATCHED.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY_WITH_CHILDREN.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_EMPTY_NO_CHILDREN.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_UPLOAD_REAL.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.NodeManager.DIAG_TOP_LEVEL_NO_DATA_DEFER.get()));
            Logger.info(String.format(
                    "[Metal-GEN   f=%d] called=%d  prepThrow=%d  faceThrow=%d  zeroQ=%d  realQ=%d  lastQ=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_CALLED.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_PREPARE_THROW.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_FACE_THROW.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_ZERO_QUADS.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_REAL_QUADS.get(),
                    me.cortex.voxy.client.core.rendering.building.RenderDataFactory.DIAG_GEN_LAST_QUADCOUNT.get()));
            Logger.info(String.format(
                    "[Metal-BAKE  f=%d] invocations=%d  nonzeroPixels=%d  fullAlpha=%d  zeroAlpha=%d  dilateRuns=%d  dilateFilled=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_NONZERO_PIXEL_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_FULL_ALPHA_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_ZERO_ALPHA_INVOCATIONS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_DILATE_RUNS.get(),
                    me.cortex.voxy.client.core.model.bakery.GlViewCapture.DIAG_BAKE_DILATE_PIXELS_FILLED.get()));
            Logger.info(String.format(
                    "[Metal-PIPE  f=%d] addEntry=%d  cpyBuf=%d  procModel=%d  atlasUpload=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_ADDENTRY_CALLS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_CPYBUF_CALLBACKS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_PROCESS_MODEL_RESULTS.get(),
                    me.cortex.voxy.client.core.model.ModelFactory.DIAG_ATLAS_UPLOADS.get()));
            Logger.info(String.format(
                    "[Metal-REQ   f=%d] last=%d  total=%d  directRead=%d  downloadRead=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_LAST_REQUEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_TOTAL_REQUEST_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_REQUEST_DIRECT_READ_COUNT.get(),
                    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.DIAG_REQUEST_DOWNLOAD_COUNT.get()));
        }
    }

    /** Accessor for the compositing mixin so it can grab the bridge's GL texture name. */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge() {
        return this.metalBridge;
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    //Binds the framebuffer and any other bindings needed for rendering
    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    //null means no function, otherwise return the taa injection function
    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    //null means dont transform the shader
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Returning null means apply the same patch as the opaque
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Null means no scaling factor
    public float[] getRenderScalingFactor() {return null;}

}
