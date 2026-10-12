package me.skaffy.client;

import me.skaffy.RealisticWater;
import me.skaffy.client.water.Material;
import me.skaffy.client.water.WaterRegion;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Development smoke test, off unless the JVM is started with {@code -Drealisticwater.selftest=true}.
 *
 * <p>Creates a flat creative world, drops a water ball and a sand cube in front of the player, lets the simulation run for a
 * number of rendered frames, logs {@code /water stats}, saves screenshots and quits. Used to exercise the OpenGL port end to end
 * (shader compilation, buffer plumbing, rendering into Minecraft's targets) without a person at the keyboard.
 */
public final class SelfTest {
	private static final boolean ENABLED = Boolean.getBoolean("realisticwater.selftest");
	private static final int SETTLE_TICKS = 60;

	private enum Stage { TITLE, LOADING, SETTLE, WATER, TURN, SAND, DONE }

	private static Stage stage = Stage.TITLE;
	private static int ticks;
	private static int frameMark;
	private static int lastLoggedFrame = -1;

	private SelfTest() {
	}

	public static void init() {
		if (!ENABLED) {
			return;
		}
		RealisticWater.LOGGER.warn("[selftest] Realistic Water self test enabled");
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static int frames() {
		return WaterWorld.get().framesRendered();
	}

	private static void tick(Minecraft mc) {
		ticks++;
		switch (stage) {
			case TITLE -> {
				// First launches open the accessibility onboarding screen instead of the title screen; any menu will do.
				if (mc.level == null && mc.getOverlay() == null && mc.screen != null && ticks > 40) {
					String name = "rw-selftest-" + System.currentTimeMillis();
					RealisticWater.LOGGER.warn("[selftest] creating flat world {}", name);
					LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
						new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(42L, false, false),
						lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
						new TitleScreen());
					stage = Stage.LOADING;
					ticks = 0;
				}
			}
			case LOADING -> {
				if (mc.player != null && mc.level != null && mc.screen == null) {
					stage = Stage.SETTLE;
					ticks = 0;
				} else if (ticks > 20 * 120) {
					fail(mc, "world did not load in time");
				}
			}
			case SETTLE -> {
				mc.player.setYRot(0.0F);
				mc.player.setXRot(25.0F);
				if (ticks == 1) {
					// Changing the resolution makes WaterWorld rebuild (and drop) every region on its next tick, so do it well before
					// spawning anything.
					set("resolution", System.getProperty("realisticwater.selftest.resolution", "4"));
					set("sandResolution", System.getProperty("realisticwater.selftest.sandResolution", "6"));
				}
				if (ticks >= SETTLE_TICKS) {
					drop(mc, Material.WATER, Double.parseDouble(System.getProperty("realisticwater.selftest.waterSize", "2")), 0.0);
					RealisticWater.LOGGER.warn("[selftest] dropped water ball");
					stage = Stage.WATER;
					frameMark = frames();
					ticks = 0;
				}
			}
			case WATER -> {
				mc.player.setYRot(0.0F);
				mc.player.setXRot(25.0F);
				logProgress();
				if (frames() - frameMark >= Integer.getInteger("realisticwater.selftest.frames", 90)) {
					shot(mc, "rw-selftest-water.png");
					stage = Stage.TURN;
					frameMark = frames();
					ticks = 0;
				} else if (ticks > 20 * 600) {
					fail(mc, "water stage timed out at " + (frames() - frameMark) + " frames");
				}
			}
			case TURN -> {
				// Same water seen from a turned camera: screen-space artefacts stay put, world features move.
				mc.player.setYRot(25.0F);
				mc.player.setXRot(25.0F);
				if (frames() - frameMark >= 3) {
					shot(mc, "rw-selftest-water-turned.png");
					mc.player.setYRot(0.0F);
					if (Boolean.getBoolean("realisticwater.selftest.clip")) {
						RealisticWater.LOGGER.warn("[selftest] clip: {}", me.skaffy.client.water.ClipRecorder.get().start(0.5, 10));
					}
					drop(mc, Material.SAND, 1.5, 3.0);
					RealisticWater.LOGGER.warn("[selftest] dropped sand cube");
					stage = Stage.SAND;
					frameMark = frames();
					ticks = 0;
				} else if (ticks > 20 * 600) {
					fail(mc, "turn stage timed out");
				}
			}
			case SAND -> {
				mc.player.setYRot(0.0F);
				mc.player.setXRot(25.0F);
				logProgress();
				if (frames() - frameMark >= Integer.getInteger("realisticwater.selftest.frames", 90)) {
					shot(mc, "rw-selftest-sand.png");
					RealisticWater.LOGGER.warn("[selftest] RESULT {} | {}", WaterWorld.get().failed() ? "FAILED" : "OK", WaterWorld.get().statsLine());
					RealisticWater.LOGGER.warn("[selftest] clip status: {}", me.skaffy.client.water.ClipRecorder.get().statusLine());
					stage = Stage.DONE;
					ticks = 0;
				} else if (ticks > 20 * 600) {
					fail(mc, "sand stage timed out at " + (frames() - frameMark) + " frames");
				}
			}
			case DONE -> {
				if (ticks > 40) {
					mc.stop();
				}
			}
		}
		if (WaterWorld.get().failed() && stage != Stage.DONE) {
			fail(mc, "simulation reported a failure");
		}
	}

	private static void logProgress() {
		int f = frames();
		if (f / 15 != lastLoggedFrame / 15) {
			lastLoggedFrame = f;
			RealisticWater.LOGGER.warn("[selftest] frame {} | {}", f, WaterWorld.get().statsLine());
		}
	}

	private static void drop(Minecraft mc, Material material, double size, double sideways) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 center = eye.add(0.0, 0.5, 6.0).add(sideways, 0.0, 0.0);
		double half = size * 0.5;
		WaterWorld.get().addEmitter(new WaterRegion.Emitter(center.x - half, center.y - half, center.z - half,
			center.x + half, center.y + half, center.z + half, 0, 0, 0), material);
	}

	private static void set(String name, String value) {
		try {
			WaterSettings.set(name, value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			RealisticWater.LOGGER.error("[selftest] could not set {}={}", name, value, e);
		}
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
			message -> RealisticWater.LOGGER.warn("[selftest] screenshot {}: {}", name, message.getString()));
	}

	private static void fail(Minecraft mc, String why) {
		RealisticWater.LOGGER.error("[selftest] RESULT FAILED: {} | {}", why, WaterWorld.get().statsLine());
		shot(mc, "rw-selftest-failure.png");
		stage = Stage.DONE;
		ticks = 0;
	}
}
