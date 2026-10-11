# CraftWater

CraftWater is a performance fork of **CraftBukkit 1.2.5 (R5.0)**. This repository
contains only the standalone patcher that builds the server jar — no Mojang or
Bukkit source code is stored or redistributed here.

The patcher downloads the original CraftBukkit base archive from an official URL,
verifies its SHA-256, applies the CraftWater patch pack and writes a ready-to-run
`CraftWater.jar`.

> Patch data lives in a separate repository:
> **https://github.com/99965bs/CraftWater-patches**

## Requirements

- Windows with PowerShell 5.1
- JDK 21 or newer (`javac` and `jar` on `PATH`)
- Internet access for the base archive, Maven and the patch pack

## Build the patcher

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Output: `build\WaterBuild.jar`.

## Run

```powershell
java -jar build\WaterBuild.jar
```

A Swing GUI lets you pick the base, a release (`R1.0` … `R4.0`) and individual
modules. Command-line helpers:

```powershell
java -jar build\WaterBuild.jar --selftest R4.0   # apply a release without building
java -jar build\WaterBuild.jar --list            # list releases/modules
java -jar build\WaterBuild.jar --release R4.0    # build a release non-interactively
```

## How the patch pack is fetched

The patcher reads `base.properties` (embedded, overridable by the one next to
the jar) and loads the patch index from GitHub:

1. **GitHub Releases** — each release tag is a version (`R4.0`), assets are
   `manifest.yml` plus the `*.cw` modules.
2. **Fallback** — `pack.yml` committed in the patch repository tree
   (`pack.owner` / `pack.repo` / `pack.ref`).

Downloaded modules are cached under `tools\pack\<repo>\<release>\`. Set
`pack.token` in `base.properties` to raise the GitHub API rate limit
(60/h → 5000/h).

## Layout

| Path              | Purpose                                              |
|-------------------|------------------------------------------------------|
| `src/`            | Patcher source (`CraftWaterPatcher.java`)            |
| `base.properties` | Base URL + SHA-256, Maven deps, patch-repo settings  |
| `build.ps1`       | Build script                                         |

## Version history

- **R1.0** — initial port: Java 21 runtime, Netty 4.2.15, Bukkit API 1.2.5.
- **R2.0–R2.2** — EAR, Merge Radius, Chunk GC, Anti-Xray, Optimized Tick Loop,
  Random Light Updates, built-in commands (`/restart`, `/pl`, `/tps`, `/lag`),
  fixes for pathfinding leaks, join lag, fall-through-world and item dupe.
- **R3.0** — mob AI engine (VANILLA/CRAFTWATER), per-player mob spawning,
  redstone engine, hybrid Bukkit+Spigot API, chat spam control, NoLagg addon,
  FoxStudios branding; fixes for the busy-wait TPS leak, inventory wipe on
  reconnect and phantom player clones (`/ghosts`).
- **R4.0** — parallel world ticking, per-thread AxisAlignedBB/Vec3D pools,
  async chunk IO and chunk generation, multi-threaded plugin scheduler with a
  watchdog; further mob-AI work (real fleeing animals, 1.8-style spider AI,
  skeleton aiming animation, correct player step height).

## License

The CraftWater patcher itself is released under the **MIT License** (see
`LICENSE`). The generated server jar is a derivative of CraftBukkit and is
governed by the license of the patch pack (GNU GPLv3) — see the
`CraftWater-patches` repository.
