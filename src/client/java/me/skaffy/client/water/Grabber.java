package me.skaffy.client.water;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Grab tool: hold the key (G by default) to pick up the simulated material under the crosshair; it follows the view at the
 * distance it was picked at (scroll to move it closer/further) and keeps its speed when released, so it can be thrown.
 * The shaders do the work (applyGrab in common.glsl); this only tracks where the grab point is.
 */
public final class Grabber {
	private static final double REACH = 8.0;
	private static @Nullable KeyMapping key;

	private static boolean active;
	private static Vec3 center = Vec3.ZERO;
	private static Vec3 velocity = Vec3.ZERO;
	private static double distance;
	private static long lastNanos;
	/** Self test: hold without the key, starting at a given point. */
	private static @Nullable Vec3 testStart;
	private static boolean testHeld;

	public static void testHold(Vec3 at) {
		testStart = at;
		testHeld = true;
	}

	public static void testRelease() {
		testHeld = false;
	}

	private Grabber() {
	}

	public static void register() {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.realistic-water.grab", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
			"key.categories.realistic-water"));
	}

	/** Called once per rendered frame before the simulation records. */
	static void update(Minecraft mc) {
		boolean held = (testHeld || key != null && key.isDown() && mc.screen == null) && mc.player != null && mc.level != null && !mc.player.isSpectator();
		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 1.0F / 60.0F : Math.clamp((now - lastNanos) / 1.0e9F, 1.0F / 500.0F, 0.1F);
		lastNanos = now;
		if (!held) {
			active = false;
			return;
		}
		Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		if (!active) {
			// Pick the point under the crosshair, fluids included; the grab ball starts there.
			HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
			Vec3 at = testHeld && testStart != null ? testStart : hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(4.0)) : hit.getLocation();
			if (testHeld && testStart != null) {
				distance = at.distanceTo(eye);
				center = at;
				velocity = Vec3.ZERO;
				active = true;
				return;
			}
			distance = Math.max(1.5, at.distanceTo(eye) - 0.2);
			center = eye.add(look.scale(distance));
			velocity = Vec3.ZERO;
			active = true;
			return;
		}
		Vec3 target = eye.add(look.scale(distance));
		Vec3 v = target.subtract(center).scale(1.0 / dt);
		velocity = velocity.lerp(v, 0.5);
		center = target;
	}

	/** Scroll while grabbing moves the grab point; returns true when the scroll was used. */
	public static boolean onScroll(double amount) {
		if (!active) {
			return false;
		}
		distance = Math.clamp(distance + amount * 0.5, 1.5, REACH + 4.0);
		return true;
	}

	static boolean active() {
		return active;
	}

	static Vec3 center() {
		return center;
	}

	static Vec3 velocity() {
		return velocity;
	}

	static float radius() {
		return WaterSettings.grabRadius;
	}
}
