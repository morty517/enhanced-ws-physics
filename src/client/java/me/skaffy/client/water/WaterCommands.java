package me.skaffy.client.water;

import me.skaffy.Material;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class WaterCommands {
	private WaterCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("water")
			.then(literal("stats").executes(c -> {
				c.getSource().sendFeedback(Component.literal(WaterWorld.get().statsLine()));
				return 1;
			}))
			.then(literal("pause").executes(c -> {
				boolean paused = WaterWorld.get().togglePause();
				c.getSource().sendFeedback(Component.literal(paused ? "Simulation paused" : "Simulation running"));
				return 1;
			}))
			.then(literal("reload").executes(c -> {
				WaterWorld.get().reload(c.getSource().getWorld());
				c.getSource().sendFeedback(Component.literal("Re-reading realistic water blocks around you into fresh simulations"));
				return 1;
			}))
			.then(literal("ball")
				.executes(c -> ball(c, 3, Material.WATER))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.WATER))))
			.then(literal("sand")
				.executes(c -> ball(c, 2, Material.SAND))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.SAND))))
			.then(literal("spawn")
				.then(argument("material", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(materialIds(false), b))
					.executes(c -> spawn(c, -1))
					.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "size"))))))
			.then(literal("remove")
				.executes(c -> remove(c, Material.WATER))
				.then(argument("material", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(materialIds(true), b))
					.executes(c -> {
						String id = StringArgumentType.getString(c, "material");
						if (id.equals("all")) {
							for (Material m : Material.VALUES) {
								remove(c, m);
							}
							return 1;
						}
						Material m = Material.byId(id);
						if (m == null) {
							c.getSource().sendError(Component.literal("Unknown material " + id + " (" + String.join(", ", materialIds(true)) + ")"));
							return 0;
						}
						return remove(c, m);
					})))
			.then(literal("hose")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal("Hose: " + Hose.material().id + ". Hold H to spray, sneak + H for a spring (only you see it)"));
					return 1;
				})
				.then(argument("material", StringArgumentType.word())
					.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(materialIds(false), b))
					.executes(c -> {
						String id = StringArgumentType.getString(c, "material");
						Material m = Material.byId(id);
						if (m == null) {
							c.getSource().sendError(Component.literal("Unknown material " + id + " (" + String.join(", ", materialIds(false)) + ")"));
							return 0;
						}
						Hose.select(m);
						c.getSource().sendFeedback(Component.literal("Hose: " + m.id.replace('_', ' ') + ". Hold H to spray"
							+ (m.fluid() ? ", sneak + H for a spring" : "") + " (only you see it)"));
						return 1;
					})))
			.then(literal("springs")
				.then(literal("clear").executes(c -> {
					int n = WaterWorld.get().clearVirtualSprings(c.getSource().getWorld());
					c.getSource().sendFeedback(Component.literal("Removed " + n + " hose springs"));
					return 1;
				})))
			.then(literal("set")
				.then(argument("name", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(WaterSettings.fields().keySet(), b))
					.then(argument("value", StringArgumentType.greedyString()).executes(WaterCommands::set))))
			.then(literal("settings").executes(WaterCommands::listSettings))
			.then(literal("render")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().statusLine()));
					return 1;
				})
				.then(literal("stop").executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().stop("stopped")));
					return 1;
				}))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05))
					.executes(c -> render(c, 60))
					.then(argument("fps", IntegerArgumentType.integer(1, 240)).executes(c -> render(c, IntegerArgumentType.getInteger(c, "fps")))))));
	}

	private static java.util.List<String> materialIds(boolean withAll) {
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (Material m : Material.VALUES) {
			ids.add(m.id);
		}
		if (withAll) {
			ids.add("all");
		}
		return ids;
	}

	private static int spawn(CommandContext<FabricClientCommandSource> c, int size) {
		String id = StringArgumentType.getString(c, "material");
		Material m = Material.byId(id);
		if (m == null) {
			c.getSource().sendError(Component.literal("Unknown material " + id + " (" + String.join(", ", materialIds(false)) + ")"));
			return 0;
		}
		return ball(c, size > 0 ? size : (m.granular() ? 2 : 3), m);
	}

	private static int remove(CommandContext<FabricClientCommandSource> c, Material m) {
		WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, m);
		c.getSource().sendFeedback(Component.literal("Removing all realistic " + m.id.replace('_', ' ') + " around you" + (m.fluid() ? " (springs included)" : "")));
		return 1;
	}

	private static int ball(CommandContext<FabricClientCommandSource> c, int size, Material material) {
		var player = c.getSource().getPlayer();
		Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(size + 3.0));
		double half = size * 0.5;
		WaterWorld.get().addEmitter(new WaterRegion.Emitter(
			center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half, 0, 0, 0), material);
		c.getSource().sendFeedback(Component.literal("Dropped a " + size + "^3 block cube of " + material.id.replace('_', ' ')));
		return 1;
	}

	private static int render(CommandContext<FabricClientCommandSource> c, int fps) {
		double seconds = DoubleArgumentType.getDouble(c, "seconds");
		c.getSource().sendFeedback(Component.literal(ClipRecorder.get().start(seconds, fps)));
		return 1;
	}

	private static int set(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name");
		String value = StringArgumentType.getString(c, "value");
		try {
			c.getSource().sendFeedback(Component.literal(name + " = " + WaterSettings.set(name, value)));
		} catch (ReflectiveOperationException | NumberFormatException e) {
			c.getSource().sendError(Component.literal("Unknown setting or bad value: " + name));
		}
		return 1;
	}

	private static int listSettings(CommandContext<FabricClientCommandSource> c) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Field> e : WaterSettings.fields().entrySet()) {
			try {
				sb.append(e.getKey()).append('=').append(e.getValue().get(null)).append("  ");
			} catch (IllegalAccessException ignored) {
			}
		}
		c.getSource().sendFeedback(Component.literal(sb.toString()));
		return 1;
	}
}
