package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * Phase B of the native vx contract on Metal (milestone issue #9): drives
 * the pack's own {@code #ifdef VOXY} integration from the Metal LOD
 * renderer instead of the gbuffer-injection hack.
 *
 * Per frame at the SOLID-head hook (pre-deferred):
 *  1. {@link VxIrisSideChannel} decodes the Metal depth bridge into the
 *     D32F texture Iris serves as {@code vxDepthTexOpaque/Trans} — LOD
 *     depth NEVER touches depthtex0 (both BSL and Complementary declare
 *     {@code excludeLodsFromVanillaDepth: true}), so real-terrain depth
 *     stomping is impossible by construction.
 *  2. The pre-lit LOD colour is written into the pack's declared
 *     {@code voxy.json opaqueDrawBuffers} colortexes (BSL/CR: colortex0 +
 *     colortex6) through a depth-attachment-less FBO. colortex0 gets the
 *     sqrt/scene-linear encoded colour (ALPHA_BLEND 0 convention);
 *     colortex6 gets the shadowMask seed the pack's own voxy_opaque would
 *     have written (r=1 shadow, b=1 "LOD wrote here").
 *
 * The pack's deferred chain (BSL deferred1's {@code vxZ < 1.0} branch) then
 * applies ITS fog, LOD shadows, AO and cloud-distance extension natively —
 * the VOXY define and vx* matrix uniforms already reach packs on Metal
 * (MixinStandardMacros / MixinMatrixUniforms have no backend gates).
 */
public final class VxContractInjector {

    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;

    private static int program;
    private static int vao;
    private static int uColour, uDepthTex, uInjectGamma, uInjectExposure, uInjectSqrt;
    private static int colorFbo;
    private static int[] attachedTargets = new int[0];
    private static boolean warnedFailure;
    private static boolean disabled;

    private static final float INJECT_GAMMA = parseEnvF("VOXY_IRIS_INJECT_GAMMA", 2.2f);
    private static final float INJECT_EXPOSURE = parseEnvF("VOXY_IRIS_INJECT_EXPOSURE", 1.0f);
    private static final int INJECT_SQRT = "0".equals(System.getenv("VOXY_IRIS_INJECT_SQRT")) ? 0 : 1;

    private static float parseEnvF(String name, float dflt) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private VxContractInjector() {}

    /**
     * @return true if both the side-channel depth resolve and the colour
     *         inject ran (LODs handed to the pack's native VOXY path).
     */
    public static boolean inject(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        if (disabled || viewport == null || colorBridge == null || depthBridge == null) {
            return false;
        }
        try {
            return inject0(colorBridge, depthBridge);
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                Logger.warn("VxContractInjector: inject failed — falling back to hidden LODs under pack", t);
            }
            return false;
        }
    }

    private static boolean inject0(IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IGetIrisVoxyPipelineData dataGetter)) {
            return false;
        }
        var pipeData = dataGetter.voxy$getPipelineData();
        if (pipeData == null) {
            return false;
        }

        int fbw = colorBridge.width();
        int fbh = colorBridge.height();

        // Bind/resync the bridges to their GL rect textures (the compositor
        // owns the CGL binding + per-frame resync machinery).
        int colourRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                .acquireColorRectTex(colorBridge);
        int depthRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                .acquireDepthRectTex(depthBridge);
        if (colourRect == 0 || depthRect == 0) {
            return false;
        }

        // 1. Depth side-channel: vxDepthTexOpaque/Trans become real.
        boolean depthOk = VxIrisSideChannel.getOrCreate().resolve(
                colourRect, depthRect, fbw, fbh,
                me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP);
        if (!depthOk) {
            return false;
        }

        // 2. Colour into the pack's opaque vx draw targets.
        if (program == 0 && !buildProgram()) {
            disabled = true;
            return false;
        }
        if (!ensureColorFbo(pipeData.opaqueDrawTargets)) {
            return false;
        }

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE1);
        int prevTexRect1 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler1 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler0 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);

        try {
            glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
            glDisable(GL_SCISSOR_TEST);
            glViewport(0, 0, fbw, fbh);
            // No depth attachment on this FBO: depth test irrelevant, but
            // disable for clarity. No blend — coverage handled by discard.
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);

            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
            org.lwjgl.opengl.GL33C.glBindSampler(1, 0);
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_RECTANGLE, colourRect);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(uColour, 0);
            glUniform1i(uDepthTex, 1);
            glUniform1f(uInjectGamma, INJECT_GAMMA);
            glUniform1f(uInjectExposure, INJECT_EXPOSURE);
            glUniform1i(uInjectSqrt, INJECT_SQRT);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            return true;
        } finally {
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
            org.lwjgl.opengl.GL33C.glBindSampler(0, prevSampler0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect1);
            org.lwjgl.opengl.GL33C.glBindSampler(1, prevSampler1);
            glActiveTexture(prevActiveTex);
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            if (prevDepthTest) glEnable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND);
            if (prevScissor) glEnable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    /**
     * (Re)attach the pack's opaque draw-target colortexes. Texture ids
     * change on pack reload and buffer flips — re-attach only when they
     * differ. Writes are restricted to the first two targets (colortex0 +
     * colortex6 for BSL/CR); a third MCBL target is left unwritten.
     */
    private static boolean ensureColorFbo(int[] targets) {
        int n = Math.min(targets.length, 2);
        if (colorFbo == 0) {
            colorFbo = glGenFramebuffers();
            attachedTargets = new int[0];
        }
        boolean dirty = attachedTargets.length != n;
        for (int i = 0; !dirty && i < n; i++) {
            dirty = attachedTargets[i] != targets[i];
        }
        if (!dirty) return true;

        int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
        for (int i = 0; i < n; i++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                    GL_TEXTURE_2D, targets[i], 0);
        }
        int[] bufs = new int[n];
        for (int i = 0; i < n; i++) bufs[i] = GL_COLOR_ATTACHMENT0 + i;
        glDrawBuffers(bufs);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("VxContractInjector: colour FBO incomplete: 0x" + Integer.toHexString(status));
            return false;
        }
        attachedTargets = java.util.Arrays.copyOf(targets, n);
        Logger.info("VxContractInjector: bound pack vx draw targets " + java.util.Arrays.toString(attachedTargets));
        return true;
    }

    private static boolean buildProgram() {
        String vs = """
                #version 150 core
                out vec2 vUV;
                void main() {
                    vec2 p = vec2((gl_VertexID & 1) * 2, (gl_VertexID & 2));
                    vUV = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        String fs = """
                #version 150 core
                uniform sampler2DRect uColour;
                uniform sampler2DRect uDepthTex;
                uniform float uInjectGamma;
                uniform float uInjectExposure;
                uniform int uInjectSqrt;
                in vec2 vUV;
                out vec4 outColor0;
                out vec4 outColor1;
                void main() {
                    ivec2 sz = textureSize(uColour);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    vec4 c = texture(uColour, texel);
                    vec3 dEnc = texture(uDepthTex, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (c.a <= 0.001 || d <= 0.0 || d >= 0.9999999) discard;
                    // Pack colour convention (BSL ALPHA_BLEND 0): colortex0
                    // carries sqrt-encoded scene-linear radiance at
                    // pre-exposure magnitudes (tonemap multiplies by 4).
                    vec3 lin = pow(c.rgb, vec3(uInjectGamma)) * (uInjectExposure * 0.25);
                    outColor0 = vec4((uInjectSqrt == 1) ? sqrt(max(lin, vec3(0.0))) : lin, 1.0);
                    // colortex6 seed: r = shadowMask (fully lit; deferred1's
                    // GetLODShadows refines), b = "LOD wrote here" mask the
                    // pack's own voxy_opaque writes as float(z < 1).
                    outColor1 = vec4(1.0, 0.0, 1.0, 1.0);
                }
                """;
        program = VxIrisSideChannel.compile(vs, fs, "VxContractInjector");
        if (program == 0) return false;
        uColour = glGetUniformLocation(program, "uColour");
        uDepthTex = glGetUniformLocation(program, "uDepthTex");
        uInjectGamma = glGetUniformLocation(program, "uInjectGamma");
        uInjectExposure = glGetUniformLocation(program, "uInjectExposure");
        uInjectSqrt = glGetUniformLocation(program, "uInjectSqrt");
        vao = glGenVertexArrays();
        return vao != 0;
    }
}
