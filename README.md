# Voxy — Apple M-Series Support

> **Status: ALPHA** — playable on Apple Silicon, under active development.

A fork of [Voxy](https://github.com/MCRcortex/voxy) (the LOD rendering mod for
Minecraft) ported to run on **Apple M-series GPUs**. Upstream Voxy requires
OpenGL 4.6; macOS ships a frozen OpenGL 4.1, so this fork runs Voxy's entire
LOD renderer on **Metal** while Minecraft itself keeps rendering on GL, and
bridges the two worlds every frame through an IOSurface.

Verified on an Apple M4 at **~110 FPS** with 32-chunk render distance and LOD
terrain to the horizon.

## Quick start (development environment)

Requirements: macOS on Apple Silicon, JDK 21+, this repository.

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

`VOXY_FORCE_METAL=1` is the explicit opt-in for the Metal render path —
without it Voxy disables itself on macOS and you get plain Sodium rendering.

To use the mod in a launcher profile, build the jar and drop it in `mods/`
together with the matching Sodium version:

```bash
./gradlew build    # output: build/libs/voxy-<version>.jar
```

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.18.2+ |
| Fabric API | 0.116.0+ |
| Sodium (required) | mc1.21.1-0.6.13 |

## What works (alpha)

- LOD terrain to the horizon with **real baked block textures**, biome
  tinting, Minecraft lighting, and fog parity with Sodium's near terrain
- **Translucent, animated water** with correct underwater behavior (fog murk,
  no X-ray, stable visuals while swimming)
- Correct LOD↔terrain boundary (chunk-bound depth masking)
- Spyglass / zoomed FOV, screenshots, render-distance changes
- ~110 FPS on an M4 (synchronous GPU model — more headroom planned)

## Known issues / not yet done

- **LOD water animation phase** can be slightly offset from MC's water, and
  flowing water (rivers/waterfalls seen up close) uses a static frame —
  accepted open issue.
- **SSAO** is not ported (an interim brightness compensation matches LOD
  terrain to Sodium's ambient-occlusion look).
- **Sky at the horizon** is the fog color rather than MC's real sky, and
  white clouds can be hard to see against it at day (MC 1.21.11 renders the
  sky in a way the compositor cannot yet preserve).
- **Performance phase 2** pending: the Metal frame currently uses 3
  synchronous GPU waits; collapsing them should push FPS well past the
  current ~110.
- **Iris shader packs do not apply to LOD terrain** (Voxy's Iris integration
  is OpenGL-only by design); Iris-alongside-Voxy compatibility on macOS is
  being tested.

## Documentation

- [`docs/METAL-MIGRATION.md`](docs/METAL-MIGRATION.md) — architecture, the
  root-cause/fix table, the full environment-variable reference, backlog.
- [`docs/MIGRATION-HISTORY.md`](docs/MIGRATION-HISTORY.md) — the complete
  journey: every milestone, bug, root cause, and fix that got this working.
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — building, running,
  debugging workflow, log markers, and contribution conventions.
- [`docs/LOD-FLICKER-INVESTIGATION.md`](docs/LOD-FLICKER-INVESTIGATION.md) —
  historical investigation notes (May 2026).

## Credits

- [MCRcortex](https://github.com/MCRcortex) — Voxy, the upstream mod this
  fork builds on.
- Port and stabilization work: see `docs/MIGRATION-HISTORY.md`.


# Voxy - NeoForge 1.21.1 Port

> **Far-distance LoD rendering for Minecraft 1.21.1 - NeoForge build**

Voxy renders distant terrain using Level-of-Detail (LoD) chunks, allowing you to see far beyond Minecraft's normal render distance without the performance cost of loading full chunks. This is the **NeoForge 1.21.1** port (`v0.2.14-NeoForge-1.21.1`), based on the original Fabric mod by [Cortex](https://github.com/CortexMC).


> **Works best with:**
> 🌍 [Voxy World Gen V2](https://github.com/realBritakee/voxy_worldgen_v2) - background chunk pre-generation for 1.20.1/1.21.1 · [other versions](https://modrinth.com/mod/voxy-worldgen)
> 🎨 [Photon Shaders - Reimagined](https://github.com/realBritakee/photon) - custom Photon fork with Physics Mod ocean support & Colorwheel (Create)

---

## Features

- Far-distance LoD rendering using Metal compute shaders
- Hierarchical chunk culling for performance
- Iris shader support (compatible with shaderpack pipelines)
- Nvidium compatibility
- SSAO (Screen Space Ambient Occlusion) in the distance
- Configurable via in-game settings screen (Sodium options integration)
- Includes **Physics Mod ocean compatibility** - ocean waves render correctly alongside Voxy LoD terrain within your render distance

  > **⚠️ Physics Mod ocean limitation:** Wave simulation only applies within your default Minecraft render distance. Chunks further out rendered by Voxy's LoD system will show as normal flat water without physics - this is a Physics Mod limitation, not a Voxy issue.
- Includes **Fix-sodium-ShaderLoader** - fixes Sodium's `ShaderLoader.getResourceAsStream` on NeoForge so Voxy and Nvidium load shaders correctly (based on [coco875/Fix-sodium-ShaderLoader](https://github.com/coco875/Fix-sodium-ShaderLoader), merged directly into this build)

---

## Requirements

### Fabric

| Dependency | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | |
| Fabric Loader | ≥ 0.17.2 | |
| Fabric API | 0.116.6+1.21.1 | |
| Sodium | ≥ 0.6.13 | Required - Voxy hooks into Sodium's renderer |
| Java | 17+ | Shipped with Minecraft 1.21.1 |
| OpenGL | 4.6 | GPU must support OpenGL 4.6 (most GPUs from 2017+) |

### NeoForge

| Dependency | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.x | |
| Sinytra Connector | latest | Bridges Fabric mods to NeoForge |
| Forgified Fabric API | latest | NeoForge port of Fabric API, required by Connector |
| Sodium (NeoForge) | ≥ 0.6.13 | Required - use the NeoForge build of Sodium |
| Java | 17+ | Shipped with Minecraft 1.21.1 |


## Installation

### Fabric
1. Install [Fabric Loader](https://fabricmc.net/use/) ≥ 0.17.2 for Minecraft 1.21.1
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) 0.116.6+1.21.1
3. Install [Sodium for Fabric](https://modrinth.com/mod/sodium) (≥ 0.6.13)
4. Drop `voxy-<version>.jar` into your `mods/` folder
5. Launch and configure via **Options → Video Settings → Voxy**

### NeoForge
1. Install [NeoForge 21.1.x](https://neoforged.net/) for Minecraft 1.21.1
2. Install [Sinytra Connector](https://modrinth.com/mod/connector)
3. Install [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api)
4. Install [Sodium for NeoForge](https://modrinth.com/mod/sodium) (≥ 0.6.13)
5. Drop `voxy-<version>.jar` into your `mods/` folder
6. Launch and configure via **Options → Video Settings → Voxy**

---
