package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

public class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, FogParameters parameters, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices, this.parameters, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) clearIrisSamplers0();
    }

    private static void clearIrisSamplers0() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        return Iris.isPackInUseQuick();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }

    /**
     * EXPERIMENTAL (VOXY_IRIS_LOD_EXPERIMENT=1): composite the Metal LOD
     * bridge late (HUD time) so LODs show with an Iris pack active. Two
     * attempts regressed on-device — Iris finalizes its frame after both
     * WorldRenderEvents.END_MAIN (an Iris FBO was still bound there) and,
     * with the HUD hook, the mid-frame bridge re-bind painted the world
     * black. DEFAULT OFF: with a pack active the LODs stay hidden (Iris's
     * final image overwrites the early composite) but the world renders
     * correctly. Proper fix = rendering into Iris's deferred pipeline or a
     * present-level hook — future work.
     */
    private static final boolean IRIS_LOD_EXPERIMENT =
            "1".equals(System.getenv("VOXY_IRIS_LOD_EXPERIMENT"));

    /** True only when the experimental late-composite mode is active. */
    public static boolean irisLateCompositeMode() {
        return IRIS_LOD_EXPERIMENT && irisShaderPackEnabled();
    }
    public static void disableIrisShaders() {
        if(IRIS_INSTALLED) disableIrisShaders0();
    }
    private static void disableIrisShaders0() {
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);//Disable shaders
    }
}
