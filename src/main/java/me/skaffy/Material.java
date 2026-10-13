package me.skaffy;

import me.skaffy.block.RealisticSandBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the simulation can be made of. Fluids run on the FLIP water solver, granular materials on the MPM sand solver;
 * the material only changes parameters (friction, grain size, colours, optics) on top of those two solvers.
 *
 * <p>Shared by server (blocks, block sync) and client (simulation). The ordinal is the network id, so only append.
 */
public enum Material {
	WATER(Kind.FLUID, "water"),
	SAND(Kind.GRANULAR, "sand"),
	MILK(Kind.FLUID, "milk"),
	RED_SAND(Kind.GRANULAR, "red_sand"),
	GRAVEL(Kind.GRANULAR, "gravel"),
	/** Very suspicious. Same solver as water, but lazier (low FLIP ratio, sticky film) and milky-translucent. */
	SUSPICIOUS_MILK(Kind.FLUID, "suspicious_milk"),
	/** Cohesive, packs and holds steep walls. */
	SNOW(Kind.GRANULAR, "snow"),
	/** Its own simulation (doesn't mix with water): glows, flows lazily, turns into stone where it meets water. */
	LAVA(Kind.FLUID, "lava");

	public enum Kind { FLUID, GRANULAR }

	public static final Material[] VALUES = values();

	public final Kind kind;
	/** Lowercase name used in commands and registry ids. */
	public final String id;

	Material(Kind kind, String id) {
		this.kind = kind;
		this.id = id;
	}

	public boolean fluid() {
		return this.kind == Kind.FLUID;
	}

	public boolean granular() {
		return this.kind == Kind.GRANULAR;
	}

	/** 1 for the MPM (granular) shaders, 0 for the FLIP (fluid) shaders; read as {@code G.matA.x} by isSand(). */
	public float shaderId() {
		return this.granular() ? 1.0F : 0.0F;
	}

	/**
	 * Materials of one group share a simulation and can mix or push each other; this is the group's region key: water for the
	 * water-like fluids, lava for lava, sand for every granular material.
	 */
	public Material base() {
		return this == LAVA ? LAVA : this.fluid() ? WATER : SAND;
	}

	/**
	 * Index of the material within its kind (0..2), used by the shaders: fluid particles carry a mixture of slots, grains carry
	 * one slot. Slot 0 is the base material.
	 */
	public int slot() {
		return switch (this) {
			case WATER, SAND, LAVA -> 0;
			case MILK, RED_SAND -> 1;
			case SUSPICIOUS_MILK, GRAVEL -> 2;
			case SNOW -> 3;
		};
	}

	public static final int SLOTS = 4;

	/** The material of {@code base}'s group in {@code slot}, or {@code base} itself when the group has no such slot. */
	public static Material bySlot(Material base, int slot) {
		for (Material m : VALUES) {
			if (m.base() == base && m.slot() == slot) {
				return m;
			}
		}
		return base;
	}

	public byte networkId() {
		return (byte) this.ordinal();
	}

	public static @Nullable Material byNetworkId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : null;
	}

	public static @Nullable Material byId(String id) {
		for (Material m : VALUES) {
			if (m.id.equals(id)) {
				return m;
			}
		}
		return null;
	}

	public Block block() {
		return ModContent.block(this);
	}

	/** Fluid level 1..8 for fluids, layer count 1..8 for granular materials, 0 if the state is not this material. */
	public int amount(BlockState state) {
		if (this.granular()) {
			return state.getBlock() == this.block() ? RealisticSandBlock.layers(state) : 0;
		}
		FluidState fluid = state.getFluidState();
		return RealisticWaterFluid.materialOf(fluid) == this ? fluid.getAmount() : 0;
	}

	/** The simulated material a block state belongs to, or null for ordinary blocks. */
	public static @Nullable Material of(BlockState state) {
		if (state.getBlock() instanceof RealisticSandBlock sand) {
			return sand.material();
		}
		return RealisticWaterFluid.materialOf(state.getFluidState());
	}
}
