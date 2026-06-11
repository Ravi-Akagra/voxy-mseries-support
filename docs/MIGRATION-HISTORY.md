# Migration History — how Voxy got onto Apple Silicon

A chronological record of the work, the bugs, and the root causes. Written so
that anyone (including future maintainers) can understand not just *what* the
code does but *why* every Metal-specific decision exists. Companion to
[`METAL-MIGRATION.md`](METAL-MIGRATION.md) (architecture + reference).

## Phase 1 — Foundations (milestones M1–M11)

| Milestone | Outcome |
|---|---|
| M1–M4 | Backend abstraction (`gpu/` interfaces) carved out of upstream's raw GL; Vulkan/MoltenVK explored and parked in favor of native Metal; smoke-test harness (clear, triangle, compute) |
| M5–M8 | Metal compute pipeline: runtime shader chain (GLSL → shaderc SPIR-V → SPIRV-Cross MSL, disk-cached), buffer/encoder/fence primitives, JNI layer (`MetalNative` ⇄ `voxy_metal_*.mm`) |
| M9–M11 | The **IOSurface bridge**: Metal renders the LOD frame into an IOSurface that GL re-binds each frame (`CGLTexImageIOSurface2D`) and composites into MC's render target at the head of Sodium's SOLID pass. Verified end-to-end with solid-color tests |

## Phase 2 — Real rendering (M12–M13, May 2026)

- **M12**: the MDIC section renderer's five compute prepasses + render pass
  migrated onto the encoder abstraction; a `force_all_visible` stub replaces
  the GL depth-occlusion cull (real HiZ import parked).
- **M13 chunk 2**: MC's 16×16 lightmap mirrored to Metal every frame —
  real Minecraft lighting on LODs.
- **M13 chunk 1** (the hard one): the **Metal model bakery**. Apple GL
  SIGBUSes on the FBO readbacks upstream uses, so baking moved fully to
  Metal. Root cause that blocked it for days: the bake projection's
  `m22 = -1` clipped 4 of 6 cube faces on Metal — only NORTH/SOUTH survived
  at the z=0 clip border. Real LOD textures landed 2026-05-16.
- **M13 chunk 5**: environmental fog parity with Sodium's near terrain.
- Two interim hacks shipped during this phase (both since removed): flat
  dark-blue water, and a frustum-cull margin to mask a then-unknown culling
  bug.

## Phase 3 — Stabilization (June 2026)

Eight investigation/fix rounds, each driven by on-device verification on an
M4 (screenshots + structured log forensics). Every fix is in the table in
`METAL-MIGRATION.md`; this is the story of the root causes.

### Round 1 — the flicker root cause (`abcf14b3`)
LODs and water blinked content↔transparent even with a static camera, for
weeks, resisting texture/fog/bridge-sync theories. The actual cause:
**compute pipeline descriptors declared threadgroup size 32 while the
shaders declare 128/256**. Metal dispatches with the descriptor's value (GL
uses the shader's), so only ~25 % of visible sections received draw commands
each frame — and the render list is filled by atomic counters in
nondeterministic order, so a *different* 25 % rendered every frame. Fixed by
parsing `layout(local_size_*)` from the shader source and making it
authoritative (`ComputeLocalSizeParser`), with PSOs pinning
`maxTotalThreadsPerThreadgroup` so an under-provisioned pipeline fails loudly.
Same round: shader UB hardening (negative left-shifts, sign-extension shift
idioms, boolean-select `mix()` — all miscompilable through SPIRV-Cross/MSL),
which fixed terrain vanishing at screen edges and the spyglass blanking the
world; stream-copy command-buffer ordering (GPU readbacks had been reading
their own CPU fill patterns); and the draw-encode perf work (count-clamped
loops instead of 450k no-op JNI calls per frame).

### Round 2 — real water (`1117dca2`, `9186dfb2`)
The fluid bake produced zero-alpha textures because **five of six face quads
sat exactly on Metal's far clip plane** and float rounding deleted them; the
mesher then marked water faces nonexistent ("grey seafloor"). Fixed by
compressing the bake depth range. A border-face meshing workaround shipped in
the same round turned out to *cause* the "empty squares" lattice on the
ocean (full-height water walls blending through the surface) and was
reverted after screenshot forensics proved the geometry was never missing.

### Round 3 — the compositor lesson (`24ef75bc`)
An attempt to let MC's real sky show behind the LODs (alpha-discard
composite) failed in-game: **on MC 1.21.11 the main render target does not
contain the sky at the point where Voxy composites** — discarded pixels
exposed a cleared-black buffer (black sky at noon, clouds intact since they
draw later). Reverted to the opaque blit; the constraint is documented for
any future attempt.

### Round 4 — fixed-mip textures (`36a8ced6`)
The "paper" look (every LOD face a flat average color) was derivative-based
mip selection collapsing to the smallest mip through the Metal transpile.
Forcing mip 0 restored full detail at no measured FPS cost.

### Round 5 — fog semantics (`0c2c1a0c`, `56e02fe7`)
Underwater artifacts traced to fog handling, in three steps that each
falsified the previous theory: (a) the "X-ray caves" were *real* flooded
aquifers that vanilla hides behind dense water fog — Voxy's fog smoothing
was diluting the density (the smoothing lerped fog *distances* with the same
constant as colors); (b) the millisecond strobe survived 2-second smoothing
because MC's eye-in-fluid verdict is binary per frame and re-fogs MC's own
near field instantly — smoothing *across* the flip just made near and far
disagree longer; (c) a hard snap-on-flip strobed with verdict oscillation
(swim bob + fractional fluid heights). Final design: snap densify fast,
debounce thinning over 400 ms, hold distances while pending.

### Round 6 — the traversal's second race (`fc747ada`, `3d4f995b`)
Static-camera log forensics showed the render list still collapsing
bimodally underwater. Inside a single Metal compute encoder, memory barriers
do **not** reliably fence the command processor's *indirect-argument fetch*
— the octree walk's next `dispatchIndirect` could read its group count
before the previous iteration wrote it. Fixed with one encoder per traversal
iteration (encoder boundaries are full hazard-tracked barriers). Same round:
the **chunk-bound depth mask** ported to Metal (LOD no longer rasterizes
inside MC's loaded-chunk volume — the proper boundary fix), which also
uncovered a latent upstream bug (the bound shader's cull radius read
uninitialized uniform memory).

### Round 7 — the leaked viewport (`7cde05c6`)
The last underwater strobe: MC re-renders its 16×16 lightmap texture every
game tick and blaze3d leaves the GL viewport at 16×16; above water the
fullscreen sky pass restores it, but **Sodium skips the sky pass underwater**
— so Voxy read a 16×16 viewport on every tick frame (~20 Hz), inflating the
LOD subdivision threshold 6,400× (octree collapsed to ~16 sections) and
reallocating the IOSurface bridge to 16×16 (broken blit frames). Fixed by
sizing from MC's main render target, never `GL_VIEWPORT`. Same round: water
appearance parity (the interim darkening knobs had become the *cause* of the
water seam line — MC water is exactly alpha 0.706) and the LOD water plane
moved to the real fluid height (0.89 vs full block top).

### Round 8 — animated water (`236313a9`)
The last seam difference was motion: MC water animates (32 frames / 2 ticks),
LOD water was a frozen bake frame. `WaterAnimator` captures the CPU-resident
sprite frames once (~43 KB, no GL readbacks — the SIGBUS-prone class stays
banned) and re-uploads the current frame into the water model's atlas cells
as the animation advances (~10 Hz, 2.7 KB). **Accepted open issues**: the
animation phase can be slightly offset from MC's ticker, and flowing-water
states / `water_flow` side faces stay frozen.

## Methodology notes

What repeatedly worked, for future debugging:

1. **Structured log diagnostics over guesswork** — the `[Metal-*]` markers
   (`FLICKER` variance windows, `LayerB` draw counts, `FRUSTUM` projection
   dumps, `WATERBAKE` alpha coverage) turned "it flickers" into falsifiable
   numbers; several root causes were proven or refuted purely from logs.
2. **Screenshot forensics** — pixel-level comparison of consecutive frames
   distinguished "missing geometry" from "added translucent layer" from
   "fog density flip" more than once.
3. **Bit-exactness discipline** — every shader rewrite was re-derived
   against the Java bit-packers before shipping; every "fix" carries an env
   kill switch so on-device A/B testing is one variable at a time.
4. **Distrust inherited state** — three root causes were external state
   leaking in (GL viewport, fog record class, indirect-argument timing),
   not Voxy's own math.

## Branch / PR map

| Branch / PR | Content |
|---|---|
| PR #1, #2 (merged) | Base Metal backend + M11–M13 migration |
| PR #3 (merged) | Stabilization rounds 1–5: textures, water, flicker root cause, perf |
| PR #4 (`feature/voxy-water-issues`) | Rounds 6–8: traversal race, depth bound, viewport leak, animated water |
| `docs/voxy-mseries-docs` | This documentation set |
