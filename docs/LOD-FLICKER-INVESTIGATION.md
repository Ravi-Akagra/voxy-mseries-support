# Voxy Metal LOD — Texture Flicker: Status & Investigation

**Date:** 2026-05-25
**Branch:** `feature/voxy-lod-textures` (draft PR #3 → `dev`)
**Base:** M11–M13 Metal migration (PR #2, merged to `dev`)

---

## ✅ RESOLUTION (2026-05-25)

**Root cause FOUND: the HOT traversal's frustum cull was dropping IN-VIEW
sections on the Metal backend** — that is the view-dependent LOD-texture
flicker. Confirmed by disabling the frustum cull (`VOXY_LOD_FORCE_ALL_VISIBLE`
test): **~90% of the flicker stopped.** The likely underlying bug is an
NDC-z / projection-convention error in the frustum-plane extraction on Metal
(same class as the bakery `m22` GL-vs-Metal fix at `99aad877`).

**Interim fix applied (default on Metal):** `HierarchicalOcclusionTraverser
.setFrustum` uploads "pass-all" planes on Metal so no in-view section is
dropped — frustum culling is effectively OFF on Metal. Perf cost: renders
sections around/behind the camera too (~2× more sections). Re-enable the
(buggy) real frustum cull for debugging with `VOXY_LOD_FRUSTUM_CULL=1`.

**Proper fix (TODO):** correct the frustum-plane math for Metal's `[0,1]`
NDC-z so real frustum culling can be re-enabled without dropping in-view
sections.

**Water LOD (2026-05-25):** rendered as a **solid dark blue** in the
translucent LOD pass (`quads.frag`, gated on `TRANSLUCENT` →
`outColour = vec4(0.0, 0.2, 1.0, 1.0)`). The Metal fluid bake produced
unusable output (water showed the grey seafloor); a per-face-cull fix attempt
was reverted. At LOD distance a flat blue reads as water, so this flat blue is
the chosen interim. **Deferred:** real per-block water texture (fix the fluid
bake). Colour is easily tweaked at that one line. Also still open: white
empty-bake quads in the sky (separate, low priority).

> The sections below are the historical investigation log that led here.

---

## TL;DR

On the Metal (Apple M-series) backend, LOD chunks render with real baked
textures, but the **LOD textures flicker** — they alternate between their
colour and "transparent" (revealing the fog-coloured sky behind). After a
systematic elimination campaign this session the flicker's **root cause is
still open**, but a large set of candidates has been ruled out and the
problem is heavily narrowed. This doc records the current state, what's been
eliminated (and how), the remaining hypotheses, and the diagnostic flags
left in the code.

---

## Current state

- **LODs render** with real biome textures on Metal:
  `VOXY_FORCE_METAL=1 ./gradlew runClient` (world auto-loads near
  `/tp 4000 100 4000`).
- **Fix A reverted.** The alpha-discard IOSurface composite (bridge clear
  alpha=0 + a GL shader that `discard`s alpha≤0.001) was reverted to the
  **blit + alpha=1** path (matches `dd20bad2`). The shader-composite made
  LODs *invisible* — a known-bad approach also tried & reverted on
  2026-05-14. With the blit, LODs are visible. The shader-composite code is
  preserved in git history at `1a339ea5`.
- **Open bug:** LOD textures **flicker** (details below). Two related
  artifacts: (a) white quads floating in the sky, (b) water not rendering in
  LOD (you see the grey seafloor instead).

---

## The symptom

- LOD textures flicker between colour and "transparent."
- **"Transparent" = the LOD fragment is ABSENT** at that pixel, revealing the
  bridge's clear colour — which equals the **fog colour** (sky-blue) when fog
  is on (`AbstractRenderPipeline.java:455-458`). So it looks like the sky
  shows through the LOD.
- **View-dependent & deterministic:** rotate the camera a little → a texture
  goes transparent; return to the exact angle → it reappears.
- Biome/block-correlated: dense green vegetation is more stable; desert /
  sparse areas flicker more.
- Worse through the **spyglass** (zoom) — "cave-line"-like fragmentation.

---

## Ruled OUT (with the experiment that eliminated each)

| Hypothesis | Experiment | Result |
|---|---|---|
| IOSurface bridge / composite / Metal↔GL sync | `VOXY_BRIDGE_SOLID_TEST=1` (solid-green clear, skip LOD draws) | Green **stable** → bridge path sound. Per-frame IOSurface re-specify also changed nothing. |
| Compute-pass race (prep / cull / commandGen) | `VOXY_COMPUTE_SERIALIZE=1` (submit+wait after each compute pass) | Flicker **persisted** → not a compute race. |
| Alpha discard (`quads.frag:259`) | `VOXY_LOD_NO_DISCARD=1` | Flicker **persisted** → not the alpha discard. |
| Raster backface culling | Code review: pipeline = `NO_CULL` → `MTLCullModeNone` (confirmed applied at `MetalRenderEncoder:50`) | Not it. |
| View-dependent face culling in vertex shader | Code review: `quads3.vert` has none (only a TODO) | Not it. |
| Depth / z-fighting in the LOD's own depth buffer | `VOXY_LOD_NO_DEPTH=1` (disable opaque depth test/write) | Flicker **persisted** (worse / continuous) → not depth/z-fight. |
| Fog | Code review: fog only modifies `outColour.rgb`, never alpha, and runs *after* the discard | Not it. |

Also: with the camera fixed for 85 s, `[Metal-BAKE]`, `[Metal-PIPE]
atlasUpload`, `[Metal-DIAG] sections` and `realQ` are all **flat** → the
atlas/bake/geometry inputs are static while it flickers.

---

## Key deduction

With **NO_DISCARD + NO_CULL + NO_DEPTH** all active, a *drawn* quad ALWAYS
produces a visible fragment (nothing can discard, cull, or depth-reject it).
Yet the LOD texture still goes absent depending on view angle. Therefore the
quad is **not being rasterized at that pixel** for certain angles — the cause
is **upstream of the fragment shader and the raster fixed-function**: in the
**draw-list generation / geometry / clipping**, not in shading, discard,
cull, or depth.

---

## Open hypotheses (next to investigate)

1. **View-dependent section/face culling in the draw-list**
   (`commandGen` / `force_all_visible.comp`). The Metal cull stub marks
   frustum-visible sections; if the frustum test is too tight or uses the
   wrong NDC-z / projection convention (recall the bakery had an `m22`
   GL-vs-Metal NDC-z bug, fixed at `99aad877` — the cull/render projection
   may have the same latent issue), sections get dropped view-dependently.
   **Cheapest test:** make `force_all_visible.comp` mark *every* section
   visible regardless of frustum; if the flicker stops, it's section culling.

2. **Clipping / degenerate geometry at certain view angles** in the LOD
   vertex/projection path (a quad clipped or collapsed at some angles).

3. **White sky quads** — LOD geometry rendering in the sky as flat
   white/empty-bake quads (persists across ALL tests). Likely mis-positioned
   LOD geometry or empty bakes; may be the same elements that flicker. Trace
   where that geometry/UV originates.

4. **Water / translucent LOD** not rendering (shows grey seafloor) —
   translucent bake/render quality; part of the broader sparse-bake issue
   (most non-cube blocks fill <50% of their bake cell; ~52% `zeroAlpha` per
   earlier `[Metal-BAKE]` diagnostics).

---

## Diagnostic flags (all OFF by default — set the env var to enable)

Base run: `VOXY_FORCE_METAL=1 ./gradlew runClient`

| Env var | Effect | Where |
|---|---|---|
| `VOXY_BRIDGE_SOLID_TEST=1` | Fill the bridge solid green + skip LOD draws (isolates bridge vs render) | `AbstractRenderPipeline` |
| `VOXY_COMPUTE_SERIALIZE=1` | `submit()`+wait after each compute prepass (serialize prep/cull/commandGen) | `MDICSectionRenderer.buildDrawCalls` |
| `VOXY_LOD_NO_DISCARD=1` | Skip the LOD alpha discard | `quads.frag` + `MDICSectionRenderer` |
| `VOXY_LOD_FIXED_MIP=1` | Sample the atlas at LOD 0 instead of the derivative mip | `quads.frag` + `MDICSectionRenderer` |
| `VOXY_LOD_NO_DEPTH=1` | Disable the opaque LOD depth test/write | `MDICSectionRenderer` |

Each logs a `[Metal-LODTEST]` / `[Metal-SOLID-TEST]` / `[Metal-SERIALIZE]`
line at startup to confirm it's active. Logs: `run/logs/latest.log` +
`debug.log`. The compositor's per-frame IOSurface re-specify logs
`+IOSurface-resync`.

---

## Recommended next steps

1. **Frustum/section-cull test** (directly tests hypothesis #1, cheap): force
   every section visible in `force_all_visible.comp`; re-run. Flicker stops →
   it's section culling → audit the frustum / NDC math on Metal.
2. **Trace the white sky quads** (hypothesis #3) — empty bakes vs projection.
3. **Audit the LOD render projection** for an NDC-z / `m22`-style Metal issue
   causing view-dependent clipping (hypothesis #2).
4. Longer-term: **M13 chunk 3** (import MC's depth → real HiZ + proper depth
   test) is still parked and underlies several of these — today the LOD
   renders against its own self-contained depth buffer.

---

## Files touched this session

- `AbstractRenderPipeline.java` — Fix A revert (clear alpha 1.0) +
  `VOXY_BRIDGE_SOLID_TEST`.
- `IOSurfaceBridgeCompositor.java` — Fix A revert (blit path) + per-frame
  IOSurface re-specify (`CGLTexImageIOSurface2D` each frame).
- `MDICSectionRenderer.java` — `VOXY_COMPUTE_SERIALIZE`, `VOXY_LOD_FIXED_MIP`,
  `VOXY_LOD_NO_DISCARD`, `VOXY_LOD_NO_DEPTH` flags.
- `quads.frag` — `VOXY_LOD_FIXED_MIP` + `VOXY_LOD_NO_DISCARD` guards.
