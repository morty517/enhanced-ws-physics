package me.skaffy.client;

import me.skaffy.Material;
import me.skaffy.RealisticWater;

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
	/** -Drealisticwater.selftest.scene=pit: dig a 5x5x3 pit in front of the player, fill it with water and drop sand into it. */
	private static final boolean PIT = "pit".equals(System.getProperty("realisticwater.selftest.scene"));
	private static final float PITCH = PIT ? 62.0F : 25.0F;
	private static boolean splashShot;
	private static net.minecraft.core.BlockPos pitOrigin;

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
				mc.player.setXRot(PITCH);
				if (ticks == 1) {
					// Changing the resolution makes WaterWorld rebuild (and drop) every region on its next tick, so do it well before
					// spawning anything.
					set("resolution", System.getProperty("realisticwater.selftest.resolution", "4"));
					set("sandResolution", System.getProperty("realisticwater.selftest.sandResolution", "6"));
					set("coupling", System.getProperty("realisticwater.selftest.coupling", "true"));
				}
				if (PIT && ticks == 5) {
					var bp = mc.player.blockPosition();
					pitOrigin = bp;
					mc.player.connection.sendCommand(String.format("fill %d %d %d %d %d %d minecraft:air",
						bp.getX() - 2, bp.getY() - 3, bp.getZ() + 4, bp.getX() + 2, bp.getY() - 1, bp.getZ() + 8));
					// Spectators float and are ignored by the water, so the camera can hang right above the pit.
					mc.player.connection.sendCommand("gamemode spectator");
				}
				if (PIT && ticks == 10) {
					mc.player.connection.sendCommand(String.format("tp @s %d.5 %d %d.5 0 %d",
						pitOrigin.getX(), pitOrigin.getY() + 3, pitOrigin.getZ() + 3, (int) PITCH));
				}
				if (ticks >= SETTLE_TICKS) {
					double size = Double.parseDouble(System.getProperty("realisticwater.selftest.waterSize", PIT ? "3.5" : "2"));
					if (PIT) {
						dropAt(mc, Material.WATER, size, pitCenter(mc).add(0.0, 2.5, 0.0));
					} else {
						drop(mc, Material.WATER, size, -2.5);
						drop(mc, Material.MILK, size, 2.5);
					}
					RealisticWater.LOGGER.warn("[selftest] dropped water ball");
					stage = Stage.WATER;
					frameMark = frames();
					ticks = 0;
				}
			}
			case WATER -> {
				mc.player.setYRot(0.0F);
				mc.player.setXRot(PITCH);
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
				mc.player.setXRot(PITCH);
				if (frames() - frameMark >= 3) {
					shot(mc, "rw-selftest-water-turned.png");
					mc.player.setYRot(0.0F);
					if (Boolean.getBoolean("realisticwater.selftest.clip")) {
						RealisticWater.LOGGER.warn("[selftest] clip: {}", me.skaffy.client.water.ClipRecorder.get().start(0.5, 10));
					}
					if (PIT) {
						dropAt(mc, Material.SAND, 1.5, pitCenter(mc).add(0.0, 3.5, 0.0));
					} else {
						drop(mc, Material.SAND, 1.5, 3.5);
						drop(mc, Material.RED_SAND, 1.5, 0.0);
						drop(mc, Material.GRAVEL, 1.5, -3.5);
					}
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
				mc.player.setXRot(PITCH);
				logProgress();
				if (!splashShot && frames() - frameMark >= 12) {
					splashShot = true;
					shot(mc, "rw-selftest-splash.png");
				}
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

	private static Vec3 pitCenter(Minecraft mc) {
		var bp = pitOrigin != null ? pitOrigin : mc.player.blockPosition();
		return new Vec3(bp.getX() + 0.5, bp.getY() - 1.5, bp.getZ() + 6.5);
	}

	private static void dropAt(Minecraft mc, Material material, double size, Vec3 center) {
		double half = size * 0.5;
		WaterWorld.get().addEmitter(new WaterRegion.Emitter(center.x - half, center.y - half, center.z - half,
			center.x + half, center.y + half, center.z + half, 0, 0, 0), material);
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
