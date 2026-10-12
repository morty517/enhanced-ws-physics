package me.skaffy.fluid;

import java.util.Optional;
import me.skaffy.Material;
import me.skaffy.ModContent;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/** A simulated fluid (realistic water, realistic milk, ...). Which one is decided by {@link #material}. */
public abstract class RealisticWaterFluid extends FlowingFluid {
	protected final Material material;

	protected RealisticWaterFluid(Material material) {
		this.material = material;
	}

	public Material material() {
		return this.material;
	}

	@Override
	public Fluid getFlowing() {
		return ModContent.flowingFluid(this.material);
	}

	@Override
	public Fluid getSource() {
		return ModContent.sourceFluid(this.material);
	}

	@Override
	public Item getBucket() {
		return ModContent.bucket(this.material);
	}

	public static boolean isRealistic(FluidState state) {
		return !state.isEmpty() && state.getType() instanceof RealisticWaterFluid;
	}

	public static @Nullable Material materialOf(FluidState state) {
		return !state.isEmpty() && state.getType() instanceof RealisticWaterFluid fluid ? fluid.material : null;
	}

	@Override
	public void tick(final ServerLevel level, final BlockPos pos, BlockState blockState, FluidState fluidState) {
	}

	@Override
	public @Nullable ParticleOptions getDripParticle() {
		return ParticleTypes.DRIPPING_WATER;
	}

	@Override
	protected boolean canConvertToSource(final ServerLevel level) {
		return false;
	}

	@Override
	protected void beforeDestroyingBlock(final LevelAccessor level, final BlockPos pos, final BlockState state) {
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		Block.dropResources(state, level, pos, blockEntity);
	}

	@Override
	protected void entityInside(final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier) {
		effectApplier.apply(InsideBlockEffectType.EXTINGUISH);
	}

	@Override
	public int getSlopeFindDistance(final LevelReader level) {
		return 4;
	}

	@Override
	public BlockState createLegacyBlock(final FluidState fluidState) {
		return this.material.block().defaultBlockState()
			.setValue(LiquidBlock.LEVEL, getLegacyLevel(fluidState))
			.setValue(RealisticWaterBlock.INFINITE, fluidState.isSource());
	}

	@Override
	public boolean isSame(final Fluid other) {
		return other == this.getSource() || other == this.getFlowing();
	}

	@Override
	public int getDropOff(final LevelReader level) {
		return 1;
	}

	@Override
	public int getTickDelay(final LevelReader level) {
		return 5;
	}

	@Override
	public boolean canBeReplacedWith(final FluidState state, final BlockGetter level, final BlockPos pos, final Fluid other, final Direction direction) {
		return direction == Direction.DOWN && !other.is(FluidTags.WATER);
	}

	@Override
	protected float getExplosionResistance() {
		return 100.0F;
	}

	@Override
	public Optional<SoundEvent> getPickupSound() {
		return Optional.of(SoundEvents.BUCKET_FILL);
	}

	public static class Flowing extends RealisticWaterFluid {
		public Flowing(Material material) {
			super(material);
		}

		@Override
		protected void createFluidStateDefinition(final StateDefinition.Builder<Fluid, FluidState> builder) {
			super.createFluidStateDefinition(builder);
			builder.add(LEVEL);
		}

		@Override
		public int getAmount(final FluidState fluidState) {
			return fluidState.getValue(LEVEL);
		}

		@Override
		public boolean isSource(final FluidState fluidState) {
			return false;
		}
	}

	public static class Source extends RealisticWaterFluid {
		public Source(Material material) {
			super(material);
		}

		@Override
		public int getAmount(final FluidState fluidState) {
			return 8;
		}

		@Override
		public boolean isSource(final FluidState fluidState) {
			return true;
		}
	}
}
