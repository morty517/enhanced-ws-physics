package me.skaffy.client.water;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.phys.Vec3;

/**
 * Things that carry simulated material around: the grab tool and flying snowballs. Each frame they become spheres in the
 * globals (applyCarriers in common.glsl); material inside one moves with it as a piece, and keeps going when it ends.
 *
 * <p>Realistic snowballs: when a vanilla snowball appears, a lump of simulated snow is spawned on it and carried along its
 * flight. When the snowball hits something it disappears and the snow flies on, splatting, sticking or breaking apart.
 */
final class Carriers {
	static final int MAX = 8;

	/** blast = 0: a carrier (material moves with it). blast > 0: an explosion, pushing material outwards at up to this speed. */
	record Carrier(Vec3 center, Vec3 velocity, float radius, float blast) {
		Carrier(Vec3 center, Vec3 velocity, float radius) {
			this(center, velocity, radius, 0.0F);
		}
	}

	/** How long an explosion keeps pushing material out (seconds of real time). */
	private static final double BLAST_TIME = 0.12;

	private record Blast(Vec3 center, float radius, long endNanos) {
	}

	private final List<Blast> blasts = new ArrayList<>();
	/** The last explosion the server announced (exact radius), so the vanilla packet for the same one isn't counted twice. */
	private Vec3 lastExact;
	private long lastExactNanos;

	void explode(Vec3 center, float power, boolean exact) {
		long now = System.nanoTime();
		if (exact) {
			this.lastExact = center;
			this.lastExactNanos = now;
		} else if (this.lastExact != null && now - this.lastExactNanos < 2_000_000_000L && this.lastExact.distanceToSqr(center) < 1e-4) {
			return;
		}
		if (WaterSettings.explosionStrength <= 0.0F || power <= 0.0F) {
			return;
		}
		// Vanilla pushes entities up to twice the power away; material gets thrown within ~1.5x.
		this.blasts.add(new Blast(center, power * 1.5F, now + (long) (BLAST_TIME * 1e9)));
		if (this.blasts.size() > MAX) {
			this.blasts.remove(0);
		}
	}

	private final IntOpenHashSet launched = new IntOpenHashSet();
	private final List<Carrier> frame = new ArrayList<>();

	/** Client tick: start the snow of new snowballs (also at low frame rates, where a snowball may live between two frames). */
	void tick(Minecraft mc, ClientLevel level, WaterWorld world) {
		if (!WaterSettings.realisticSnowballs) {
			return;
		}
		Vec3 eye = mc.player != null ? mc.player.getEyePosition() : null;
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof Snowball && !e.isRemoved() && !this.launched.contains(e.getId())
				&& (eye == null || e.position().distanceTo(eye) >= 1.3)) {
				this.launch(e, e.position(), world);
			}
		}
	}

	/** A snowball left the world (hit something) before its snow was started: start it where the snowball ended. */
	void onRemoved(Entity e, WaterWorld world) {
		if (WaterSettings.realisticSnowballs && e instanceof Snowball && !this.launched.contains(e.getId())) {
			this.launch(e, e.position(), world);
		}
		this.launched.remove(e.getId());
	}

	private void launch(Entity e, Vec3 pos, WaterWorld world) {
		Vec3 vel = e.getDeltaMovement().scale(20.0);
		double h = WaterSettings.snowballSize * 0.5;
		world.addEmitter(new WaterRegion.Emitter(pos.x - h, pos.y - h, pos.z - h, pos.x + h, pos.y + h, pos.z + h,
			(float) vel.x, (float) vel.y, (float) vel.z), Material.SNOW);
		this.launched.add(e.getId());
	}

	List<Carrier> update(Minecraft mc, ClientLevel level, WaterWorld world) {
		this.frame.clear();
		long now = System.nanoTime();
		this.blasts.removeIf(b -> now > b.endNanos);
		for (Blast b : this.blasts) {
			// Thrown out at up to ~3x the radius in blocks per second (TNT: ~18 blocks/s right next to it).
			this.frame.add(new Carrier(b.center, Vec3.ZERO, b.radius, b.radius * 2.0F * WaterSettings.explosionStrength));
		}
		Grabber.update(mc);
		if (Grabber.active()) {
			this.frame.add(new Carrier(Grabber.center(), Grabber.velocity(), Grabber.radius()));
		}
		if (!WaterSettings.realisticSnowballs) {
			this.launched.clear();
			return this.trimmed();
		}
		float partialTick = mc.gameRenderer.getMainCamera().getPartialTickTime();
		Vec3 eye = mc.player != null ? mc.player.getEyePosition(partialTick) : null;
		IntOpenHashSet alive = new IntOpenHashSet();
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof Snowball) || e.isRemoved()) {
				continue;
			}
			Vec3 pos = e.getPosition(partialTick);
			Vec3 vel = e.getDeltaMovement().scale(20.0);
			// Wait until it is clear of the thrower, or the snow would be pushed around by the player's own hitbox.
			if (!this.launched.contains(e.getId())) {
				if (eye != null && pos.distanceTo(eye) < 1.3) {
					continue;
				}
				this.launch(e, pos, world);
			}
			alive.add(e.getId());
			if (this.frame.size() < MAX) {
				this.frame.add(new Carrier(pos, vel, WaterSettings.snowballSize * 0.75F));
			}
		}
		return this.trimmed();
	}

	private List<Carrier> trimmed() {
		while (this.frame.size() > MAX) {
			this.frame.remove(this.frame.size() - 1);
		}
		return this.frame;
	}

	List<Carrier> current() {
		return this.frame;
	}
}
