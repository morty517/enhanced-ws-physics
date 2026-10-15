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
	/** -Drealisticwater.selftest.scene=mix: water and milk dropped against each other, gravel dropped onto sand and red sand. */
	private static final boolean MIX = "mix".equals(System.getProperty("realisticwater.selftest.scene"));
	/** -Drealisticwater.selftest.scene=solids: breakable materials resting on the ground, then the same ones dropped from height. */
	private static final boolean SOLIDS = "solids".equals(System.getProperty("realisticwater.selftest.scene"));
	/** -Drealisticwater.selftest.grab=true (solids scene): grab the middle block and lift it by looking up. */
	private static final boolean GRAB = Boolean.getBoolean("realisticwater.selftest.grab");
	private static int grabFrame = -1;
	/** -Drealisticwater.selftest.throw=true (solids scene): throw snowballs at the blocks. */
	private static boolean tntFired;
	private static int linkStage;
	private static me.skaffy.client.link.LinkNet linkBot;

	/** A SPAWN link message (see Link), built by hand like a peer would send it. */
	private static byte[] linkSpawn(Minecraft mc, double x, double y, double z, float edge) {
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
			out.writeByte(1);
			out.writeUTF(mc.level.dimension().location().toString());
			out.writeByte(Material.WATER.networkId());
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(z);
			out.writeFloat(edge);
			out.writeFloat(edge);
			out.writeFloat(edge);
			out.writeFloat(0.0F);
			out.writeFloat(0.0F);
			out.writeFloat(0.0F);
			return bytes.toByteArray();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}
	private static boolean hoseStarted;
	private static int tntLogged = -1;
	private static final boolean THROW = Boolean.getBoolean("realisticwater.selftest.throw");
	private static final Material[] SOLID_MATERIALS = {Material.SANDSTONE, Material.DIRT, Material.CLAY, Material.SUSPICIOUS_CURD, Material.SNOW};
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
					for (String kv : System.getProperty("realisticwater.selftest.set", "").split(",")) {
						int eq = kv.indexOf('=');
						if (eq > 0) {
							set(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
						}
					}
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
					} else if (SOLIDS) {
						for (int k = 0; k < SOLID_MATERIALS.length; k++) {
							dropAt(mc, SOLID_MATERIALS[k], 1.0, groundAhead(mc, 7.5, (k - 2) * 1.8).add(0.0, 0.52, 0.0));
						}
					} else if (MIX) {
						drop(mc, Material.WATER, size, -1.05);
						Material second = Material.byId(System.getProperty("realisticwater.selftest.second", "milk"));
							drop(mc, second != null ? second : Material.MILK, size, 1.05);
					} else {
						drop(mc, Material.WATER, size, -2.5);
						drop(mc, Boolean.getBoolean("realisticwater.selftest.sus") ? Material.SUSPICIOUS_MILK : Material.MILK, size, 2.5);
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
				// -Drealisticwater.selftest.blockIn=true (pit scene): fill stone into the middle of the water once it settled.
				if (PIT && Boolean.getBoolean("realisticwater.selftest.blockIn") && frames() - frameMark == 60 && pitOrigin != null) {
					RealisticWater.LOGGER.warn("[selftest] before blocks | {}", WaterWorld.get().statsLine());
					mc.player.connection.sendCommand(String.format("fill %d %d %d %d %d %d minecraft:stone",
						pitOrigin.getX() - 1, pitOrigin.getY() - 3, pitOrigin.getZ() + 5, pitOrigin.getX() + 1, pitOrigin.getY() - 2, pitOrigin.getZ() + 7));
				}
				// -Drealisticwater.selftest.piston=true (pit scene): a piston in the pit wall pushes into the water, twice.
				if (PIT && Boolean.getBoolean("realisticwater.selftest.piston") && pitOrigin != null) {
					int f = frames() - frameMark;
					int px = pitOrigin.getX() - 3;
					int py = pitOrigin.getY() - 1;
					int pz = pitOrigin.getZ() + 6;
					if (f == 60 && ticks % 4 == 0) {
						RealisticWater.LOGGER.warn("[selftest] before piston | {}", WaterWorld.get().statsLine());
						mc.player.connection.sendCommand(String.format("setblock %d %d %d minecraft:piston[facing=east]", px, py, pz));
						mc.player.connection.sendCommand(String.format("setblock %d %d %d minecraft:redstone_block", px - 1, py, pz));
					}
					if (f == 80 && ticks % 4 == 0) {
						mc.player.connection.sendCommand(String.format("setblock %d %d %d minecraft:air", px - 1, py, pz));
					}
					if (f == 95 && ticks % 4 == 0) {
						mc.player.connection.sendCommand(String.format("setblock %d %d %d minecraft:redstone_block", px - 1, py, pz));
					}
				}
				if (SOLIDS && Boolean.getBoolean("realisticwater.selftest.blockIn") && frames() - frameMark == 60) {
					RealisticWater.LOGGER.warn("[selftest] before blocks | {}", WaterWorld.get().statsLine());
					for (int k = 1; k <= 3; k++) {
						Vec3 g = groundAhead(mc, 7.5, (k - 2) * 1.8);
						mc.player.connection.sendCommand(String.format("setblock %d %d %d minecraft:stone", (int) Math.floor(g.x), (int) Math.floor(g.y), (int) Math.floor(g.z)));
					}
				}
				// -Drealisticwater.selftest.link=true: host the simulation link, join it from a bot in this JVM and send a cube of
				// water (accepted) and a far too big one (must be rejected).
				if (Boolean.getBoolean("realisticwater.selftest.link")) {
					int f = frames() - frameMark;
					if (f >= 10 && linkStage == 0) {
						linkStage = 1;
						RealisticWater.LOGGER.warn("[selftest] {}", me.skaffy.client.water.Link.host(me.skaffy.client.link.LinkNet.DEFAULT_PORT, "test"));
						linkBot = me.skaffy.client.link.LinkNet.join("127.0.0.1", me.skaffy.client.link.LinkNet.DEFAULT_PORT, "test", "Bot",
							new me.skaffy.client.link.LinkNet.Listener() {
								public void onMessage(int sender, String name, byte[] message) {
								}

								public void onEvent(String text) {
									RealisticWater.LOGGER.warn("[selftest] bot: {}", text);
								}
							});
					}
					if (f >= 30 && linkStage == 1 && linkBot.isUp()) {
						linkStage = 2;
						RealisticWater.LOGGER.warn("[selftest] before link spawn | {}", WaterWorld.get().statsLine());
						Vec3 g = groundAhead(mc, 6.0, 0.0);
						linkBot.send(linkSpawn(mc, g.x - 1.5, g.y + 2.0, g.z - 1.5, 3.0F));
						linkBot.send(linkSpawn(mc, g.x - 20, g.y + 2.0, g.z - 20, 40.0F));
					}
					if (linkStage == 2 && (f == 60 || f == 100)) {
						RealisticWater.LOGGER.warn("[selftest] after link spawn ({}) | {}", me.skaffy.client.water.Link.status(), WaterWorld.get().statsLine());
					}
				}
				// -Drealisticwater.selftest.hose=true: spray water for a while and put a hose spring up in the air ahead.
				if (Boolean.getBoolean("realisticwater.selftest.hose")) {
					int f = frames() - frameMark;
					if (f == 20 && !hoseStarted) {
						hoseStarted = true;
						me.skaffy.client.water.Hose.testHold(true);
						Vec3 g = groundAhead(mc, 5.0, -3.0);
						WaterWorld.get().toggleVirtualSpring(net.minecraft.core.BlockPos.containing(g.x, g.y + 4.0, g.z), Material.WATER);
					}
					if (f == 80) {
						me.skaffy.client.water.Hose.testHold(false);
					}
					if (f == 79 || f == 140) {
						shot(mc, "rw-selftest-hose-" + f + ".png");
					}
				}
				// -Drealisticwater.selftest.tnt=true (solids scene): TNT goes off next to the middle block once it settled.
				if (SOLIDS && Boolean.getBoolean("realisticwater.selftest.tnt")) {
					int f = frames() - frameMark;
					if (f >= 60 && !tntFired) {
						tntFired = true;
						RealisticWater.LOGGER.warn("[selftest] before tnt | {}", WaterWorld.get().statsLine());
						Vec3 g = groundAhead(mc, 7.5, 0.9);
						mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "summon tnt %.2f %.2f %.2f {fuse:0}", g.x, g.y + 0.5, g.z));
					}
					if ((f == 110 || f == 150) && f != tntLogged) {
						tntLogged = f;
						StringBuilder items = new StringBuilder();
						for (var e : mc.level.entitiesForRendering()) {
							if (e instanceof net.minecraft.world.entity.item.ItemEntity item) {
								items.append(item.getItem()).append(' ');
							}
						}
						shot(mc, "rw-selftest-tnt-" + f + ".png");
						RealisticWater.LOGGER.warn("[selftest] after tnt: items [{}] | {}", items, WaterWorld.get().statsLine());
					}
				}
				if (SOLIDS && THROW && mc.player != null && ticks % 6 == 0 && ticks >= 30 && ticks <= 60) {
					var bp = mc.player.blockPosition();
					double side = ((ticks / 6) % 3 - 1) * 1.8;
					mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "summon snowball %.2f %.2f %.2f {Motion:[0.0,0.06,1.1]}",
						bp.getX() + 0.5 + side, bp.getY() + 0.9, bp.getZ() + 2.0));
				}
				if (SOLIDS && GRAB) {
					int f = frames() - frameMark;
					if (f == 40) {
						// The clay block in the middle: aim at it first, then look up to drag a piece off.
						Vec3 at = groundAhead(mc, 7.5, 0.0).add(0.0, 0.8, 0.0);
						Vec3 eye = mc.player.getEyePosition();
						Vec3 d = at.subtract(eye);
						grabFrame = f;
						mc.player.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
						me.skaffy.client.water.Grabber.testHold(at);
					}
					if (grabFrame >= 0) {
						Vec3 at = groundAhead(mc, 7.5, 0.0).add(0.0, 0.8, 0.0);
						Vec3 d = at.subtract(mc.player.getEyePosition());
						float aim = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
						boolean stay = Boolean.getBoolean("realisticwater.selftest.grabStay");
						mc.player.setXRot(aim - (stay ? 0.0F : Math.min(f - grabFrame, 40) * 0.5F));
						if (stay && f - grabFrame > 50) {
							me.skaffy.client.water.Grabber.testRelease();
						}
					}
				}
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
						Material into = Material.byId(System.getProperty("realisticwater.selftest.pitDrop", "sand"));
						dropAt(mc, into != null ? into : Material.SAND, 1.5, pitCenter(mc).add(0.0, 3.5, 0.0));
					} else if (SOLIDS) {
						for (int k = 0; k < SOLID_MATERIALS.length; k++) {
							dropAt(mc, SOLID_MATERIALS[k], 1.0, groundAhead(mc, 3.5, (k - 2) * 1.6).add(0.0, 6.0, 0.0));
						}
					} else if (MIX) {
						drop(mc, Material.SAND, 1.5, 0.8, 0.0);
						drop(mc, Material.RED_SAND, 1.5, -0.8, 0.0);
						drop(mc, Material.GRAVEL, 1.5, 0.0, 2.5);
						drop(mc, Material.SNOW, 1.5, -2.6, 0.5);
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

	/** A point on the ground (the player's feet level) {@code forward} blocks ahead and {@code sideways} to the side. */
	private static Vec3 groundAhead(Minecraft mc, double forward, double sideways) {
		Vec3 feet = mc.player.position();
		return new Vec3(Math.floor(feet.x) + 0.5 + sideways, Math.floor(feet.y), Math.floor(feet.z) + 0.5 + forward);
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
		drop(mc, material, size, sideways, 0.0);
	}

	private static void drop(Minecraft mc, Material material, double size, double sideways, double up) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 center = eye.add(0.0, 0.5 + up, 6.0).add(sideways, 0.0, 0.0);
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
