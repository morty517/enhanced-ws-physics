# Realistic Water — unofficial OpenGL port for Minecraft 1.21.8 (Fabric)

**This is a modified version, not the original mod.** Original mod by **Skaffy**:
https://github.com/sskaffy/Realistic-Water (Minecraft 26.3, Vulkan backend). All simulation and rendering
techniques are Skaffy's work; this fork only moves them to OpenGL 4.3 and Minecraft 1.21.8. Same license as the
original (see `LICENSE`).

## Using it

1. Install Fabric Loader for 1.21.8 and Fabric API `0.136.1+1.21.8` (or newer for 1.21.8).
2. Put `realistic-water-1.2.1-gl+1.21.8.jar` from `build/libs/` into `mods/`.
3. No graphics-API switch needed — 1.21.8 runs on OpenGL. A GPU/driver with **OpenGL 4.3** is required
   (any desktop NVIDIA/AMD/Intel GPU from ~2012 on with current drivers; macOS is not supported, it stops at GL 4.1).
4. `/water ball`, `/water sand`, or the *Realistic Water Bucket* / *Realistic Sand* from the creative inventory.
   `/water stats`, `/water set <name> <value>`, `/water render <seconds> [fps]` work as in the original.

## Building

```
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev client
```

Java 21 is enough to run Gradle (Loom 1.14).

## What changed compared to the original

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

## Materials (additions in this fork)

- Fluids: water, milk, suspicious milk. They share one FLIP simulation per area; each particle carries its mixture
  (stored in `vel.w`), so fluids push each other, blend over time (`/water set fluidMixRate <1/s>`, 0 keeps them apart)
  and are shaded per pixel from the mixture at the surface. Blocks are written as whichever fluid dominates them.
- Granular: sand, red sand, gravel. They share one MPM simulation; each grain keeps its own friction, cohesion, look and
  water drag, so piles of different materials stack and push each other.
- Granular materials and fluids are coupled (grains displace water, water drags and lifts grains).
