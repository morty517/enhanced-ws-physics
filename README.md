# Realistic Water GL — a fork of Skaffy's Realistic Water for Minecraft 1.21.8 (Fabric)

> **This is a modified version, not the original mod.**
> Original mod: **Realistic Water by Skaffy** — https://github.com/sskaffy/Realistic-Water (Minecraft 26.3, Vulkan).
> Distributed under the same license as the original (see [`LICENSE`](LICENSE)); please support the original project.

## Credits

**Skaffy (original mod)** — the whole simulation and rendering design this fork is built on: the FLIP water solver
(MAC grid, multigrid-preconditioned pressure solve, whitewater), the MPM sand solver, surface meshing, the screen-space
water rendering, block syncing, commands and settings.

**This fork (Morty, morty517)** — everything below is added on top of Skaffy's engine:

- Port from Vulkan / Minecraft 26.3 to **OpenGL 4.3 / Minecraft 1.21.8** (see the table further down).
- A **material system**: milk, suspicious milk, lava, red sand, gravel, snow, sandstone, dirt, clay, suspicious curd.
- Fluids that **push and mix** with each other, granular materials that **stack and push** each other, and coupling
  between fluids and granular materials.
- **Lava** that glows and solidifies into cobblestone / obsidian where it meets water (with steam).
- **Breakable solids**: blocks that stand on their own and crumble when dropped or hit.
- The **grab tool** and **realistic snowballs**.
- Performance work for single-material areas and a number of driver fixes (NVIDIA, Windows TDR).

## Using it

1. Install Fabric Loader for 1.21.8 and Fabric API `0.136.1+1.21.8` (or newer for 1.21.8).
2. Put the jar from `build/libs/` into `mods/` (on a server too: same jar, plus Fabric API; players need the mod).
3. A GPU/driver with **OpenGL 4.3** is required (any desktop NVIDIA/AMD/Intel GPU from ~2012 on with current drivers;
   macOS is not supported, it stops at GL 4.1). On laptops with two GPUs, make sure Minecraft runs on the dedicated one.
4. Realistic buckets and blocks are in the creative inventory next to their vanilla versions, or
   `/water spawn <material> [size]`. Hold **G** to grab material. `/water remove [material|all]`, `/water stats`,
   `/water set <name> <value>`, `/water render <seconds> [fps]`.

Blocks are written back into the world only in singleplayer (the host); on a dedicated server every player sees their
own simulation.

## Building

```
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev client
```

Java 21 is enough to run Gradle (Loom 1.14).

## Port details (Vulkan / 26.3 → OpenGL / 1.21.8)

| Area | 26.3 / Vulkan original | This port |
|---|---|---|
| GPU layer | `client/vk/*` on Minecraft's Vulkan device (VMA, push descriptors, dynamic rendering) | `client/gl/*`: GL buffers/textures/samplers, compute + graphics programs, same call shape (`bind`/`push`/`dispatch`/`barrier`) |
| Push constants | Vulkan push constants | 64-byte std140 uniform block at `binding = 7`, written into a ring buffer |
| Command buffers | recorded, then submitted | `Gl.begin()`/`Gl.end()` around the frame; GL runs in order, `Cmd` is just a token |
| GL state | — | all state Minecraft caches goes through `GlStateManager`; its encoder's cached program/pipeline is reset after each batch |
| Depth | reverse-Z, depth = NDC z ∈ [0,1] | standard Z: `GREATER` tests → `LESS`, `linearDepth` reads `d*2-1`, `gl_FragDepth` writes `z*0.5+0.5` |
| Subgroup ops (`g2p`, `mpm_g2p`) | `GL_KHR_shader_subgroup_*` stream compaction | workgroup shared-memory compaction (`compact.glsl`), works on every GL 4.3 driver |
| Shaders | GLSL 450 → SPIR-V via shaderc | GLSL 430 core compiled by the driver; `gl_VertexIndex`/`gl_InstanceIndex` → `gl_VertexID`/`gl_InstanceID` |
| Where water is drawn | `LevelRenderer.executeOutline` | before translucent terrain (`ChunkSectionsToRender.renderGroup(TRANSLUCENT)`), with a fallback right after `LevelRenderer.renderLevel` for renderers that replace terrain (e.g. Sodium) |
| Camera / sky / fog | `LevelRenderState` | `Camera`, `ClientLevel` (sun angle, rain, sky colour), fog colour captured from `FogRenderer.setupFog` |
| Server side | 26.3 APIs | 1.21.8 names (`ResourceLocation`, `playC2S`, `ItemGroupEvents`, `PushReaction.DESTROY`, …) |

## Checks

* `python3 tools/validate_shaders.py src/client/resources/assets/realistic-water/shaders`
  — builds every program exactly like the mod does and validates it with `glslangValidator` for desktop OpenGL,
  including vertex/fragment link checks (`--vulkan` checks the original sources instead).
* `./gradlew runSelftest` — starts the game, creates a flat creative world, drops water and sand, logs `/water stats`
  every 15 frames, saves screenshots to `run-selftest/screenshots/` and quits. Only active with
  `-Drealisticwater.selftest=true`, which that run configuration sets; normal games never run it.
  Extra flags: `-PselftestArgs="-Drealisticwater.selftest.resolution=6 -Drealisticwater.selftest.clip=true"`.

## Materials

- Fluids: water, milk, suspicious milk. They share one FLIP simulation per area; each particle carries its mixture
  (stored in `vel.w`), so fluids push each other, blend over time (`/water set fluidMixRate <1/s>`, 0 keeps them apart)
  and are shaded per pixel from the mixture at the surface. Blocks are written as whichever fluid dominates them.
- Lava: its own glowing, viscous simulation (`lavaViscosity`, `lavaCrust`, `lavaEmission`). Where lava and water overlap
  they react (`react.comp`): lava solidifies and the host turns blocks with enough of it into cobblestone or obsidian, water
  boils off as steam. Synced lava blocks give light 15 and burn like vanilla lava.
- Breakable solids: sandstone, dirt, clay, suspicious curd (and snow). Each grain keeps a damage value (MPM state, plus a
  "broken" bit in its tag): cohesion fades as the material yields, mostly under tension, so a block stands on its own but
  cracks and crumbles when it lands hard (`<material>Toughness`). Snow and clay knit back together when pressed (`*Heal`).
- Granular: sand, red sand, gravel, snow (cohesive) and the breakable solids. They share one MPM simulation (8 slots); each grain keeps its own friction, cohesion, look and
  water drag, so piles of different materials stack and push each other.
- Granular materials and fluids are coupled (grains displace water, water drags and lifts grains).
- Regions made of a single material skip all mixture work (the slot mask is read back from the GPU), so plain water costs
  the same as before materials existed.

## Grab tool

Hold **G** (rebindable under Controls -> Realistic Water) to pick up the simulated material under the crosshair: a ball of
`grabRadius` blocks follows your view at the distance it was picked at (scroll to move it), tearing pieces off cohesive
blocks. Let go and it keeps its speed, so it can be thrown. Works on fluids, lava and every granular material. Carried
material keeps its shape (particles take on the carrier's velocity, only the outer shell is pulled in, gravity is held off).

Realistic snowballs (`realisticSnowballs`): a thrown snowball carries a lump of simulated snow along its flight (the vanilla
sprite is hidden); when it hits, the snow flies on and splats, sticks or breaks apart.
