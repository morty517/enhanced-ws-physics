package me.skaffy.net;

import me.skaffy.Material;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * Host client -> server: lava solidified in these blocks (it met water). Kind 0 = cobblestone, 1 = obsidian. Only replaces air
 * and simulated fluids (never springs or other blocks).
 */
public record SolidifyPayload(long[] positions, byte[] kinds) implements CustomPacketPayload {
	public static final Type<SolidifyPayload> TYPE = new Type<>(RealisticWater.id("solidify"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SolidifyPayload> CODEC = StreamCodec.of(SolidifyPayload::write, SolidifyPayload::read);
	public static final int MAX_ENTRIES = 4096;

	private static void write(RegistryFriendlyByteBuf buf, SolidifyPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.kinds[i]);
		}
	}

	private static SolidifyPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] kinds = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			kinds[i] = buf.readByte();
		}
		return new SolidifyPayload(positions, kinds);
	}

	@Override
	public Type<SolidifyPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		LegacyRandomSource random = new LegacyRandomSource(level.getGameTime());
		int hiss = 0;
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isInfinite(current) || !(current.isAir() || Material.of(current) != null && Material.of(current).fluid())) {
				continue;
			}
			BlockState stone = this.kinds[i] == 1 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
			level.setBlock(pos, stone, Block.UPDATE_ALL);
			if (hiss++ < 4) {
				level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F);
				level.levelEvent(1501, pos, 0); // the vanilla lava-meets-water smoke puff
			}
		}
	}
}
