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
| Persistent underwater flashing (survived all fog fixes) | Inside a single Metal compute encoder, memory barriers don't fence the command processor's **indirect-argument fetch** — the octree walk's next `dispatchIndirect` could read its group count before the previous iteration wrote it, truncating the walk at a random depth | One compute encoder per traversal iteration (encoder boundaries are full hazard-tracked barriers); `VOXY_HOT_SERIALIZE=1` diagnostic | `fc747ada` |
| Seafloor "turns transparent" ~3 s after submerging | Sodium's fog-occlusion culling de-renders real seafloor beyond the underwater fog wall; the culled pixels fall through the opaque blit to the LOD field behind | Submersion far-field skip: when the fog is submersion-class and render distance ≫ fog end, the LOD draws are skipped — the fog clear is identical murk by construction (`VOXY_UNDERWATER_LOD=1` forces draws) | `fc747ada` |
| Hard ring between LOD and real terrain | LOD rasterized inside MC's loaded-chunk volume; pixel ownership decided by blit overdraw | **Chunk-bound depth mask ported to Metal**: loaded-chunk AABBs rendered into a depth mask each frame; `quads.frag` discards LOD inside it. Also fixed a latent upstream std140 bug (uninitialized cull radius). `VOXY_NO_DEPTH_BOUND=1` kill switch, `VOXY_BOUND_DEBUG=1` red-tint visualization | `3d4f995b` |
| Bimodal render-list collapse / two-tone underwater strobe (~20 Hz) | **Leaked 16×16 GL viewport**: MC re-renders its lightmap every tick, blaze3d never restores the viewport, and Sodium skips the sky pass underwater (the only pass that restored it) — Voxy read 16×16 as the screen size on every tick frame | Frame size taken from MC's main render target, never `GL_VIEWPORT` (warn-once `[Metal-VIEWPORT]` diagnostic) | `7cde05c6` |
| Visible texture line at the LOD↔MC water boundary | The interim water darkening/opacity knobs (tuned pre-depth-bound) made LOD water darker and more opaque than MC's (alpha is exactly 0.706); the LOD water plane also sat at full block height vs MC's 8/9 | Knobs neutral by default; fluid UP-face indentation set from the fluid's real height (plane 0.8906 vs MC 0.8889) | `7cde05c6` |
| LOD water frozen while MC water animates | The LOD atlas held one `water_still` frame from bake time | `WaterAnimator`: CPU-resident sprite frames re-uploaded into the water model's atlas cells as the animation advances (~10 Hz, 2.7 KB; no GL readbacks). `VOXY_WATER_ANIMATE=0` kill switch | `236313a9` |

Verified on-device after the fixes: stationary flicker gone, spyglass works,
real translucent biome-tinted animated water, stable underwater rendering,
full texture detail, ~111 FPS.

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
| `VOXY_NO_DEPTH_BOUND=1` | off | Kill switch: disable the chunk-bound depth mask |
| `VOXY_BOUND_DEBUG=1` | off | Tint chunk-bound-discarded fragments red (mask visualization) |
| `VOXY_UNDERWATER_LOD=1` | off | Force LOD draws even when submersion fog saturates the far field |
| `VOXY_WATER_ANIMATE` | `1` on Metal | `0` freezes LOD water at the baked frame |
| `VOXY_WATER_SHADE` / `VOXY_WATER_MIN_ALPHA` | `1.0` / `0.0` (neutral) | LOD water appearance tuning (experiments) |
| `VOXY_HOT_SERIALIZE=1` | off | Submit+wait per traversal iteration (race diagnostic, slow) |

## Running

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

Useful log markers: `[Metal-DEFINES]` (shader variant), `[Metal-WATERBAKE]`
(fluid bake alpha coverage), `[Metal-LayerB]` (per-frame draw counts),
`IOSurfaceBridgeCompositor` (composite mode and target).

## Known limitations / backlog

- **Water animation residuals (accepted open issue)**: the LOD water
  animation phase can be slightly offset from MC's ticker, and
  flowing-water states / `water_flow` side faces stay frozen at their baked
  frame (visible only side-on, up close).
- **SSAO** is not ported (interim brightness multiplier instead) — the last
  contributor to the land-side boundary look.
- **Sky-aware composite**: MC 1.21.11 does not have the sky in the main RT
  when Voxy composites, so the bridge's fog-coloured clear acts as the far
  sky (white clouds can be low-contrast against it at day). Fixing this
  requires hooking the composite after MC's sky pass.
- **Synchronous GPU model**: 3 × `waitUntilCompleted` per frame. The fence
  machinery added in `abcf14b3` is the building block for going async
  (estimated to push well past the current ~111 FPS). "FPS phase 2."
- Iris shader packs are GL-only by design — LOD terrain is not shaded by
  packs; Iris-alongside-Voxy on macOS is being compatibility-tested.

Note: rounds 6–8 of the fix table (`fc747ada`…`236313a9`) live on
`feature/voxy-water-issues` (PR #4) until it merges into `dev`.
