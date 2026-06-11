package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.MetalMvpUtil;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL20C.GL_DRAW_BUFFER0;
import static org.lwjgl.opengl.GL20C.GL_MAX_DRAW_BUFFERS;
import static org.lwjgl.opengl.GL20C.glDrawBuffers;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

/**
 * Injects the Metal LOD bridge into Iris's terrain gbuffer so Voxy's LODs
 * render WITH a shader pack active. At the HEAD-of-render(SOLID) hook Iris
 * has NOT yet bound its terrain framebuffer (it binds inside
 * {@code ShaderChunkRenderer.begin} via redirect), so we bind it explicitly:
 * the pack's SOLID {@code GlFramebuffer} has the pack colortex at attachment
 * 0 (per its DRAWBUFFERS — glDrawBuffers is per-FBO state, hence the
 * save/restore dance) and MC's main-RT depth — the pack's depthtex0 — as its
 * depth attachment. The pack sky is already in colortex0 at depth 1.0 before
 * SOLID, so LOD pixels written with depth &lt; 1.0 survive the pack's
 * deferred/composite/final chain, and Iris's own terrain draws then
 * depth-test over them.
 *
 * The framebuffer is re-fetched EVERY frame — Iris recreates it on pack
 * reload, and {@code GlFramebuffer.bind()} binds GL_FRAMEBUFFER (draw+read).
 *
 * Iris classes are referenced ONLY here (and in IrisUtil) and every step is
 * guarded — any null / instanceof / GL failure degrades to returning false,
 * leaving the frame exactly as the no-inject path would.
 */
public final class IrisGbufferInjector {

    private static boolean warnedFailure;

    private IrisGbufferInjector() {}

    /**
     * @return true if the LOD bridge was drawn into the pack's gbuffer.
     */
    public static boolean inject(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        if (!IrisUtil.IRIS_INSTALLED
                || viewport == null || colorBridge == null || depthBridge == null) {
            return false;
        }
        try {
            return inject0(viewport, colorBridge, depthBridge);
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                Logger.warn("IrisGbufferInjector: inject failed — LODs will be hidden under the pack", t);
            }
            return false;
        }
    }

    private static boolean inject0(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irisPipeline)) {
            return false;
        }
        var programs = irisPipeline.getSodiumPrograms();
        if (programs == null) {
            return false;
        }
        net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer =
                programs.getFramebuffer(DefaultTerrainRenderPasses.SOLID);
        if (framebuffer == null) {
            return false;
        }

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        framebuffer.bind(); // binds GL_FRAMEBUFFER (draw + read)

        // Save the FBO's full draw-buffer list. glDrawBuffers is per-FBO
        // state and the pack's DRAWBUFFERS directive may route SOLID to
        // several colortexes; we restrict to attachment 0 (the main color
        // colortex) for the inject and restore the exact list after so the
        // pack's own terrain draws fan out as authored.
        int maxDrawBuffers = Math.min(glGetInteger(GL_MAX_DRAW_BUFFERS), 8);
        int[] savedDrawBuffers = new int[maxDrawBuffers];
        for (int i = 0; i < maxDrawBuffers; i++) {
            savedDrawBuffers[i] = glGetInteger(GL_DRAW_BUFFER0 + i);
        }
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});

        boolean drawn;
        try {
            // invVoxyMVP unprojects the stored LOD depth (encoded with
            // viewport.MVP — MDIC uploads it raw, applying the NDC remap only
            // when VOXY_LOD_METAL_NDC=1, which the flag mirrors); mcMVP
            // reprojects into the vanilla clip space the pack's depthtex0 uses.
            Matrix4f invVoxyMVP = new Matrix4f(viewport.MVP).invert();
            Matrix4f mcMVP = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);
            drawn = IOSurfaceBridgeCompositor.compositeIrisGbuffer(
                    colorBridge, depthBridge, invVoxyMVP, mcMVP,
                    MetalMvpUtil.METAL_NDC_REMAP);
        } finally {
            glDrawBuffers(savedDrawBuffers);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
        return drawn;
    }
}
