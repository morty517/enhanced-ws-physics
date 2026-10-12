package me.skaffy.net;

import me.skaffy.Material;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Host client -> server: "the simulation now has this much of {@code material} in these blocks" (fluid level or layer count
 * 1..8, 0 to clear). Replaces the original separate water/sand sync packets.
 */
public record MaterialSyncPayload(byte material, long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<MaterialSyncPayload> TYPE = new Type<>(RealisticWater.id("material_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MaterialSyncPayload> CODEC = StreamCodec.of(MaterialSyncPayload::write,
		MaterialSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;
	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	public MaterialSyncPayload(Material material, long[] positions, byte[] amounts) {
		this(material.networkId(), positions, amounts);
	}

	private static void write(RegistryFriendlyByteBuf buf, MaterialSyncPayload payload) {
		buf.writeByte(payload.material);
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static MaterialSyncPayload read(RegistryFriendlyByteBuf buf) {
		byte material = buf.readByte();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new MaterialSyncPayload(material, positions, amounts);
	}

	@Override
	public Type<MaterialSyncPayload> type() {
		return TYPE;
	}

	public static BlockState stateFor(Material material, int amount) {
		BlockState base = material.block().defaultBlockState();
		if (material.granular()) {
			return base.setValue(RealisticSandBlock.LAYERS, Math.clamp(amount, 1, RealisticSandBlock.MAX_LAYERS));
		}
		BlockState full = base.setValue(RealisticWaterBlock.INFINITE, false);
		return amount >= 8 ? full : full.setValue(LiquidBlock.LEVEL, 8 - Math.max(1, amount));
	}

	public void apply(ServerPlayer player) {
		Material m = Material.byNetworkId(this.material);
		if (m == null) {
			return;
		}
		ServerLevel level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isInfinite(current)) {
				continue;
			}
			boolean ours = current.getBlock() == m.block();
			int amount = this.amounts[i];
			if (amount <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || current.isAir()) {
				BlockState target = stateFor(m, amount);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
				}
			}
		}
	}
}
