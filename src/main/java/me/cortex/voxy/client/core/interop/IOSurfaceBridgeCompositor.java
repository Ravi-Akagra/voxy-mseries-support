package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FALSE;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glAttachShader;
import static org.lwjgl.opengl.GL20C.glCompileShader;
import static org.lwjgl.opengl.GL20C.glCreateProgram;
import static org.lwjgl.opengl.GL20C.glCreateShader;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glDeleteShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

/**
 * Composites a Voxy IOSurfaceBridge's contents into the currently bound
 * GL_DRAW_FRAMEBUFFER. Lazy-binds the IOSurface to a GL_TEXTURE_RECTANGLE
 * once (via {@code CGLTexImageIOSurface2D}) and reuses a transient source
 * FBO with that texture as COLOR_ATTACHMENT0.
 *
 * Caller responsibility: must invoke from a code path where MC's main
 * render target FBO is the active GL_DRAW_FRAMEBUFFER. Verified call
 * sites are inside Sodium's chunk render (mixin into
 * {@code DefaultChunkRenderer.render} BEFORE its end() call) — there MC
 * has bound the main RT for chunk drawing. Calling from
 * {@code LevelRenderer.renderLevel}'s RETURN doesn't work because by
 * then MC has already unbound to FBO 0, so a blit there ends up in the
 * window backbuffer (which MC then overwrites with its own RT→window
 * blit, hiding our output).
 */
public final class IOSurfaceBridgeCompositor {

    private static int compositeFbo;
    private static int compositeGlTex;
    private static int compositeProgram;
    private static int compositeVao;
    private static int uniformBridge;
    private static int uniformSize;
    private static long boundIoSurface;
    private static int blitFrameCounter;
    private static boolean disabled;
    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;

    /**
     * Default is the opaque glBlitFramebuffer composite: the bridge (fog
     * clear + LOD) replaces MC's far field wholesale. The 2026-06-09 attempt
     * to default the alpha-discard shader composite ("Fix A": undrawn pixels
     * keep MC's backdrop) FAILED in-game on MC 1.21.11: at the head of
     * Sodium's SOLID pass the main RT does NOT contain the rendered sky —
     * discarded pixels exposed the cleared-black buffer (black sky at noon,
     * clouds intact since they draw later) and opaque land LOD vanished too.
     * Until the composite is depth/sky-order aware, the blit + temporally
     * smoothed fog (VoxyRenderSystem.smoothFogParameters — the actual fix
     * for the eye-crossing fog strobe) is the stable combination.
     * VOXY_COMPOSITE_SHADER=1 opts back into the experimental shader path.
     */
    public static final boolean USE_BLIT = !"1".equals(System.getenv("VOXY_COMPOSITE_SHADER"));

    private IOSurfaceBridgeCompositor() {}

    /** Composite the bridge's contents into the currently bound DRAW framebuffer. */
    public static void composite(IOSurfaceBridge bridge) {
        if (disabled || bridge == null || bridge.ioSurfaceHandle() == 0) return;

        // (Re)bind on first use or after the bridge re-allocated (resize).
        if (compositeGlTex == 0 || boundIoSurface != bridge.ioSurfaceHandle()) {
            if (!rebind(bridge)) {
                disabled = true;
                return;
            }
        }

        // Fix (2026-05-25, Step 1): re-specify the GL texture from the
        // IOSurface every frame so GL picks up Metal's latest completed write.
        // The IOSurface↔GL texture is bound once (CGLTexImageIOSurface2D in
        // rebind); after that GL serves it from its own texture cache, which
        // does NOT reliably observe Metal's external writes. submit()'s
        // waitUntilCompleted already guarantees Metal finished writing the
        // IOSurface before we get here, but without re-specifying, GL
        // alternately sees the fresh content and a stale/previous copy
        // frame-to-frame — that incoherence is the LOD "flicker between
        // texture and transparent" the user sees even with a static camera
        // and atlas (all bake/atlas/geometry counters flat). Re-calling
        // CGLTexImageIOSurface2D invalidates GL's cache and re-points the
        // texture at the IOSurface's current bytes. Save/restore the
        // rectangle-texture binding because composite() runs inside Sodium's
        // chunk render and must not leak GL state.
        int prevActiveTex0 = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        bridge.bindToGlTexture(compositeGlTex);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
        glActiveTexture(prevActiveTex0);

        var mc = Minecraft.getInstance();
        var mainRT = mc.getMainRenderTarget();
        int fbw = mainRT.width;
        int fbh = mainRT.height;

        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        // 2026-05-14: at HEAD of Sodium's render(), DRAW_FRAMEBUFFER is
        // typically FBO 0 (the system default), NOT MC's main RT. With
        // MC 1.21+'s blaze3d, the level renders into MC's RenderTarget,
        // not the system FB. So a blit-to-FBO-0 lands somewhere the user
        // never sees (or gets overwritten by MC's final present). Resolve
        // the GL FBO id from MC's RenderTarget.colorTexture (a AbstractTexture)
        // via reflection on the private `firstFboId` field — public API
        // doesn't expose it on this MC version.
        int mcDrawFbo = resolveMcMainFbo(mainRT);
        if (mcDrawFbo <= 0) {
            // Fall back to whatever DRAW_FBO was bound — preserves M12
            // behaviour if resolution fails.
            mcDrawFbo = prevDrawFb;
        }

        if (USE_BLIT) {
            glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, mcDrawFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, compositeFbo);
            // Y-flip: Metal textures are top-left origin, GL framebuffers bottom-left.
            org.lwjgl.opengl.GL30C.glBlitFramebuffer(0, 0, fbw, fbh,
                              0, fbh, fbw, 0,
                              GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
            glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFb);

            blitFrameCounter++;
            if ((blitFrameCounter % 600) == 1) {
                Logger.info("IOSurfaceBridgeCompositor: blit to MC mainRT +IOSurface-resync (fbo=" + mcDrawFbo + ", prevDraw=" + prevDrawFb + ") size " + fbw + "x" + fbh + " frame=" + blitFrameCounter);
            }
            return;
        }

        // Alpha-discard shader composite. composite() runs inside Sodium's
        // chunk render, so every state we touch is saved and restored
        // bit-for-bit or Sodium's SOLID pass would draw with our program.
        if (!ensureCompositeProgram()) {
            disabled = true;
            return;
        }
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevCull  = glIsEnabled(GL_CULL_FACE);
        int prevBlendSrcRgb   = glGetInteger(GL_BLEND_SRC_RGB);
        int prevBlendDstRgb   = glGetInteger(GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);

        glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, mcDrawFbo);
        // Y-flip happens in the vertex shader's (1.0 - uv.y). Sampler2DRect
        // takes texel coords (0..size), so uSize is the size in pixels.
        glViewport(0, 0, fbw, fbh);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        // The shader hard-discards alpha<=0.001 pixels and writes the rest
        // straight to colour — no blending: water pixels already blended
        // against the fog-coloured clear in the bridge, so re-blending here
        // would double-darken them. Disabling BLEND also sidesteps Sodium's
        // previous BLEND state polluting our writes.
        glDisable(GL_BLEND);

        glUseProgram(compositeProgram);
        glBindVertexArray(compositeVao);
        glBindTexture(GL_TEXTURE_RECTANGLE, compositeGlTex);
        glUniform1i(uniformBridge, 0);
        glUniform2f(uniformSize, (float) fbw, (float) fbh);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Restore — order matters: rebind the texture target before
        // switching active unit back, restore blend func before re-enabling
        // BLEND so Sodium's next draw sees the right state.
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect);
        glActiveTexture(prevActiveTex);
        glUseProgram(prevProgram);
        glBindVertexArray(prevVao);
        glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb,
                            prevBlendSrcAlpha, prevBlendDstAlpha);
        if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (prevDepth) glEnable(GL_DEPTH_TEST);
        if (prevCull)  glEnable(GL_CULL_FACE);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFb);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);

        blitFrameCounter++;
        if ((blitFrameCounter % 600) == 1) {
            Logger.info("IOSurfaceBridgeCompositor: shader-composite to MC mainRT (fbo=" + mcDrawFbo + ", prevDraw=" + prevDrawFb + ") size " + fbw + "x" + fbh + " frame=" + blitFrameCounter);
        }
    }

    /**
     * Resolve the GL framebuffer id backing MC's main RenderTarget. MC 1.21+
     * doesn't expose this directly; we go through the color GpuTexture which
     * on the GL backend is a {@code net.minecraft.client.renderer.texture.AbstractTexture}
     * with a private {@code firstFboId}. Now accessible via access widener.
     */
    private static int cachedMcMainFbo = -1;
    private static int resolveMcMainFbo(com.mojang.blaze3d.pipeline.RenderTarget mainRT) {
        return mainRT.frameBufferId;
    }

    private static boolean rebind(IOSurfaceBridge bridge) {
        if (compositeGlTex != 0) {
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
        }
        if (compositeFbo != 0) {
            glDeleteFramebuffers(compositeFbo);
            compositeFbo = 0;
        }
        if (MetalNative.cglGetCurrentContext() == 0) {
            Logger.warn("IOSurfaceBridgeCompositor: no current CGL context — composite disabled");
            return false;
        }
        compositeGlTex = glGenTextures();
        if (compositeGlTex == 0) {
            Logger.error("IOSurfaceBridgeCompositor: glGenTextures returned 0");
            return false;
        }
        glBindTexture(GL_TEXTURE_RECTANGLE, compositeGlTex);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!bridge.bindToGlTexture(compositeGlTex)) {
            Logger.error("IOSurfaceBridgeCompositor: bindToGlTexture failed");
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
            return false;
        }
        boundIoSurface = bridge.ioSurfaceHandle();

        compositeFbo = glGenFramebuffers();
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, compositeFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_RECTANGLE, compositeGlTex, 0);
        int status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("IOSurfaceBridgeCompositor: source FBO incomplete (status=0x"
                    + Integer.toHexString(status) + ")");
            glDeleteFramebuffers(compositeFbo);
            compositeFbo = 0;
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
            return false;
        }

        Logger.info("IOSurfaceBridgeCompositor: bridge bound to GL tex " + compositeGlTex
                + ", composite FBO " + compositeFbo + " (status=COMPLETE), ready");
        return true;
    }

    private static boolean ensureCompositeProgram() {
        if (compositeProgram != 0) {
            return true;
        }
        compositeVao = glGenVertexArrays();
        if (compositeVao == 0) {
            Logger.error("IOSurfaceBridgeCompositor: failed to allocate composite VAO");
            return false;
        }

        int vs = compileShader(GL_VERTEX_SHADER, """
                #version 150 core
                uniform vec2 uSize;
                out vec2 vTexCoord;
                void main() {
                    vec2 pos;
                    if (gl_VertexID == 0) pos = vec2(-1.0, -1.0);
                    else if (gl_VertexID == 1) pos = vec2(1.0, -1.0);
                    else if (gl_VertexID == 2) pos = vec2(-1.0, 1.0);
                    else pos = vec2(1.0, 1.0);
                    vec2 uv = (pos + vec2(1.0)) * 0.5;
                    vTexCoord = vec2(uv.x * uSize.x, (1.0 - uv.y) * uSize.y);
                    gl_Position = vec4(pos, 0.0, 1.0);
                }
                """);
        int fs = compileShader(GL_FRAGMENT_SHADER, """
                #version 150 core
                uniform sampler2DRect uBridge;
                in vec2 vTexCoord;
                out vec4 fragColor;
                void main() {
                    vec4 c = texture(uBridge, vTexCoord);
                    if (c.a <= 0.001) discard;
                    fragColor = c;
                }
                """);
        if (vs == 0 || fs == 0) {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            return false;
        }

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            Logger.error("IOSurfaceBridgeCompositor: composite program link failed: "
                    + glGetProgramInfoLog(program));
            glDeleteProgram(program);
            return false;
        }
        compositeProgram = program;
        uniformBridge = glGetUniformLocation(program, "uBridge");
        uniformSize = glGetUniformLocation(program, "uSize");
        return true;
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            Logger.error("IOSurfaceBridgeCompositor: composite shader compile failed: "
                    + glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
