package me.skaffy.client.water;

import com.mojang.blaze3d.platform.InputConstants;
import me.skaffy.Material;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only tools that need nothing from the server, so they work on any server (only you see the result): hold the hose key
 * (H) to spray the selected material from in front of you; sneak + H puts a spring of the selected fluid where you look (or
 * takes it away again). Pick the material with {@code /water hose <material>}.
 */
public final class Hose {
	private static final double REACH = 48.0;
	private static @Nullable KeyMapping key;
	private static Material material = Material.WATER;
	/** The key went down while sneaking (a spring toggle): don't spray until it is released. */
	private static boolean toggled;
	/** Self test: spray without the key. */
	private static boolean testHeld;

	private Hose() {
	}

	public static void register() {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.realistic-water.hose", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
			"key.categories.realistic-water"));
	}

	public static Material material() {
		return material;
	}

	public static void select(Material m) {
		material = m;
	}

	public static void testHold(boolean held) {
		testHeld = held;
	}

	/** Client tick. */
	public static void tick(Minecraft mc, WaterWorld world) {
		if (key == null || mc.player == null || mc.level == null || mc.player.isSpectator()) {
			return;
		}
		boolean down = testHeld || key.isDown() && mc.screen == null;
		while (key.consumeClick()) {
			if (mc.player.isShiftKeyDown() && mc.screen == null) {
				toggled = true;
				toggleSpring(mc, world);
			}
		}
		if (!down) {
			toggled = false;
			return;
		}
		if (toggled) {
			return;
		}
		Vec3 look = mc.player.getViewVector(1.0F);
		Vec3 start = mc.player.getEyePosition().add(look.scale(1.2)).add(0.0, -0.3, 0.0);
		Vec3 vel = look.scale(WaterSettings.hoseSpeed).add(mc.player.getDeltaMovement().scale(20.0));
		double h = WaterSettings.hoseSize * 0.5;
		world.addEmitter(new WaterRegion.Emitter(start.x - h, start.y - h, start.z - h, start.x + h, start.y + h, start.z + h,
			(float) vel.x, (float) vel.y, (float) vel.z), material);
	}

	/** Puts a spring of the selected fluid in the air block in front of the block you look at, or removes the one there. */
	static void toggleSpring(Minecraft mc, WaterWorld world) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (!material.fluid()) {
			mc.player.displayClientMessage(Component.literal("Springs are for fluids only (/water hose water, milk, suspicious_milk, lava)"), true);
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(mc.player.getViewVector(1.0F).scale(REACH)), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, mc.player));
		if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
			mc.player.displayClientMessage(Component.literal("Look at a block to put a spring next to it"), true);
			return;
		}
		BlockPos pos = bh.getBlockPos().relative(bh.getDirection());
		boolean added = world.toggleVirtualSpring(pos, material);
		mc.player.displayClientMessage(Component.literal((added ? "Spring of " + material.id.replace('_', ' ') + " placed" : "Spring removed")
			+ " (only you see it)"), true);
	}
}
