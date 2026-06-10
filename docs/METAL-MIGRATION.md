# Voxy on Apple Silicon — Metal Migration & Implementation Notes

This document describes how Voxy's LOD renderer was ported to run on Apple
M-series GPUs (tested on an M4), the architecture that makes it possible, the
root causes that were found and fixed along the way, and the runtime knobs
available for debugging. It covers the work on `feature/voxy-lod-textures`
(PR #3) on top of the 88-commit base migration already merged into `dev`
(PR #2).

## Why a port is needed at all

Voxy upstream targets OpenGL 4.6 (compute shaders, multi-draw-indirect,
persistent-mapped buffers, `glMultiDrawElementsIndirectCount`). macOS ships a
frozen OpenGL 4.1 — none of that exists. Minecraft itself (with Sodium) runs
on Apple's GL 4.1; Voxy's renderer cannot. The port therefore runs **Voxy's
renderer on Metal** while Minecraft keeps rendering on GL, and bridges the
two worlds per frame.

## Architecture

```
┌────────────────────────────  Minecraft / Sodium (Apple GL 4.1) ───────────┐
│  vanilla terrain, entities, clouds, UI …                                  │
│        ▲                                                                  │
│        │ ④ compositor (glBlitFramebuffer at the head of                   │
│        │    Sodium's SOLID pass → MC's main RenderTarget)                 │
│  ┌─────┴──────┐                                                           │
│  │ IOSurface  │  shared, zero-copy CPU/GPU surface                        │
│  └─────▲──────┘                                                           │
└────────│──────────────────────────────────────────────────────────────────┘
         │ ③ Metal renders the LOD frame into the IOSurface
┌────────┴────────────────────  Voxy (Metal)  ──────────────────────────────┐
│ ① compute: hierarchical occlusion traversal (HOT) → render list           │
│ ② compute: prep → cull stub → cmdgen → prefix-sum → translucent gen       │
│ ③ render:  opaque → temporal → translucent (MDIC emulation)               │
│    model atlas bakery (block/fluid textures baked on Metal)               │
│    MC lightmap + atlas mirrored from GL each frame/bake                   │
└────────────────────────────────────────────────────────────────────────────┘
```

Key components (all under `me.cortex.voxy.client.core`):

- **`gpu/` abstraction** — cross-backend interfaces (`RenderBackend`,
  `IGpuPipeline`, `ComputeEncoder`, `RenderEncoder`, …) with GL and Metal
  implementations. Upstream call sites were migrated to these instead of raw
  GL.
- **`metal/` backend** — JNI bindings (`MetalNative` ⇄
  `native/metal/src/*.mm`) over MTLDevice/MTLCommandQueue. One lazily-opened
  command buffer per frame section; `submit()` commits + waits
  (synchronous M3-era model; async is future work).
- **Shader pipeline** — upstream GLSL 4.6 is compiled at runtime:
  `shaderc` (GLSL → SPIR-V, `-O`) then SPIRV-Cross (SPIR-V → MSL 3.0), with
  a content-hash disk cache in `~/.voxy/shader-cache`.
- **IOSurface bridge** (`interop/`) — Metal renders the LOD color into an
  IOSurface-backed texture; `IOSurfaceBridgeCompositor` re-binds it to a GL
  rectangle texture each frame (`CGLTexImageIOSurface2D`, required for
  cross-API coherence) and blits it into MC's main render target at the head
  of Sodium's SOLID terrain pass. Sodium then draws near terrain on top.
- **Model bakery on Metal** (`model/bakery/`) — upstream bakes block/fluid
  textures through GL FBO readbacks, which SIGBUS on Apple GL. The Metal
  bakery renders each model's 6 faces with a budget renderer
  (`MetalBudgetBufferRenderer` + `MetalViewCapture`) into the model atlas.
- **Mirrors** — MC's lightmap (16×16) is copied from GL to a Metal texture
  every frame (`LightMapHelper`); the block atlas is mirrored at bake time
  (`AtlasMirror`).

### Frame timeline (Metal path)

1. Sodium SOLID pass head (`MixinDefaultChunkRenderer`) → `setupViewport`
   (captures matrices + Sodium's `FogParameters`, temporally smoothed).
2. Node manager / cleaner compute + HOT traversal → **submit/wait #1** →
   CPU reads the LOD request queue.
3. `buildDrawCalls`: prep → cull-stub → cmdgen → prefix-sum → translucent
   gen → **submit/wait #2** → CPU reads the GPU-written draw counts.
4. Render pass into the bridge: opaque → temporal → translucent, draw counts
   clamped to the real GPU counts → **submit/wait #3**.
5. GL compositor blits the bridge into MC's main RT; Sodium renders near
   terrain over it.

## Root causes found and fixed (June 2026 stabilization)

| Symptom | Root cause | Fix | Commit |
|---|---|---|---|
| LODs/water flickering content↔transparent every few frames, even stationary | Compute pipeline descriptors declared threadgroup size 32 while the shaders declare 128/256; Metal dispatches the descriptor value (GL uses the shader's), so only ~25 % of sections got draw commands, in atomicAdd-scrambled order — a different subset every frame | `ComputeLocalSizeParser` parses the effective `layout(local_size_*)` from the preprocessed GLSL and Metal uses it as authoritative `threadsPerThreadgroup`; PSOs pin `maxTotalThreadsPerThreadgroup` so an under-provisioned pipeline fails loudly at creation | `abcf14b3` |
| Terrain vanishing at screen edges; spyglass made *everything* transparent | Undefined behavior in shared shaders that Metal's compiler exploits: left-shifts of negative ints, sign-extension `(v<<L)>>R` idioms, boolean-select `mix()` overloads | Rewritten as multiplication / `bitfieldExtract` / ternaries — bit-exact, verified against the Java bit-packers | `76159281` |
| GPU readbacks (e.g. node cleaner visibility) returned stale CPU data | Stream buffer copies committed their own command buffers ahead of the still-uncommitted frame work | Copies are encoded into the frame's active command buffer (request order = execution order); fences ride the same buffer, with deferred signaling while the bakery holds a render pass open | `abcf14b3` |
| Water rendered as "grey seafloor" / water faces missing entirely | The Metal bake placed 5 of 6 fluid face quads exactly on the far clip plane; float error clipped them → zero-alpha bakes → the mesher marked water faces nonexistent | Bake depth range compressed to NDC [0.25, 0.75] (depth is unused by the bake); `[Metal-WATERBAKE]` one-shot diagnostic logs per-face alpha coverage | `1117dca2` |
| "Empty squares" lattice on the ocean | A border-face meshing workaround emitted full-column water "walls" on both sides of every section border; with depth-write-off translucency they band through the surface | Workaround removed (the LOD-seam case it targeted never needed it — border culls already pass when the neighbour is air) | `9186dfb2` |
| Whole far field strobing light/dark when crossing the water surface | MC's eye-in-fluid verdict is binary per frame; Voxy painted the entire far field from that one captured fog record | Captured fog is smoothed (~200 ms) **within** a fog type and **snapped** on type changes so near and far field flip together; fog *distance* fields track fast (≤250 ms) so underwater murk applies immediately | `9186dfb2`, `0c2c1a0c` |
| All LOD faces flat single-colour ("paper") | Derivative-based atlas mip selection collapses to the smallest mip through the Metal transpile | Fixed-mip sampling (`textureLod(..,0)`) is the Metal default (`VOXY_LOD_FIXED_MIP=0` re-enables derivative mips) | `36a8ced6` |
| Visible brightness ring at the LOD boundary | GL runs an SSAO pass that darkens LOD ≈10 % to match Sodium's baked vertex AO; the pass is parked on Metal | Interim parity multiplier on opaque LOD (`VOXY_LOD_BRIGHTNESS`, default 0.92) until the SSAO port | `0c2c1a0c` |
| 18–20 FPS collapse | 150k–450k CPU-encoded no-op draws per frame (upper-bound loops), a 12 MB/frame buffer clear, ~8–12 throwaway command buffers per frame, duplicate uniform uploads | Draw encode clamps to the GPU-written counts; per-frame zero skipped (slices are compactly written); stream copies/fences batched into the frame buffer | `abcf14b3` |

Verified on-device after the fixes: stationary flicker gone, spyglass works,
real translucent biome-tinted water, full texture detail, ~111 FPS.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `VOXY_FORCE_METAL=1` | off | **Required** opt-in for the Metal render path |
| `VOXY_LOD_FIXED_MIP` | `1` on Metal | `0` re-enables derivative mip selection |
| `VOXY_LOD_BRIGHTNESS` | `0.92` | Opaque LOD brightness parity (1.0 disables) |
| `VOXY_FOG_SMOOTH_MS` | `200` | Fog colour smoothing time constant (0 disables) |
| `VOXY_LOD_FRUSTUM_MARGIN` | `96` | Frustum cull margin in blocks |
| `VOXY_LOD_NO_CULL=1` | off | Disable frustum culling (debug) |
| `VOXY_LOD_ZERO_DRAWBUF=1` | off | Restore the per-frame draw-buffer zero |
| `VOXY_WATER_BORDER_FACES=1` | off | Re-enable border water faces (experiment) |
| `VOXY_LOD_FLAT_WATER=1` | off | Interim flat-blue water instead of the real path |
| `VOXY_COMPOSITE_SHADER=1` | off | Experimental alpha-discard compositor (blocked: MC 1.21.11 has no sky in the RT at the composite point) |
| `VOXY_LOD_WATER_DEBUG=1` | off | Magenta water + depth-off (geometry coverage debug) |
| `VOXY_BAKERY_OFF=1` | off | Hash-colour fallback instead of the bakery |
| `VOXY_BRIDGE_SOLID_TEST=1` | off | Solid green bridge (bridge/sync isolation test) |

## Running

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

Useful log markers: `[Metal-DEFINES]` (shader variant), `[Metal-WATERBAKE]`
(fluid bake alpha coverage), `[Metal-LayerB]` (per-frame draw counts),
`IOSurfaceBridgeCompositor` (composite mode and target).

## Known limitations / backlog

- **SSAO** is not ported (interim brightness multiplier instead).
- **MC-depth bounding** (`VOXY_NO_DEPTH_BOUND`) is disabled — LOD renders
  under near terrain and relies on Sodium overdrawing it (pure overdraw,
  no visual error). The `ChunkBoundRenderer` AABB approach is the planned
  Metal-safe fix.
- **Sky-aware composite**: MC 1.21.11 does not have the sky in the main RT
  when Voxy composites, so the bridge's fog-coloured clear acts as the far
  sky and the alpha-discard composite cannot be enabled yet. Fixing this
  requires hooking the composite after MC's sky pass.
- **Synchronous GPU model**: 3 × `waitUntilCompleted` per frame. The fence
  machinery added in `abcf14b3` is the building block for going async
  (estimated to push well past the current ~111 FPS).
- **Visible LOD↔terrain transition on water** and residual underwater
  artifacts — under active investigation.
- Iris shader packs are GL-only by design.
