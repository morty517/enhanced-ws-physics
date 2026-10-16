package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> other players: the host's simulation is about to write these blocks (a copy of its MaterialSyncPayload), sent
 * before the block updates themselves. Players whose simulations are linked to the host's (simulation link) get the material
 * through the link already, so they ignore these block changes instead of adding the material a second time.
 */
public record SimEchoPayload(byte material, long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<SimEchoPayload> TYPE = new Type<>(RealisticWater.id("sim_echo"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SimEchoPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeByte(p.material);
			buf.writeVarInt(p.positions.length);
			for (int i = 0; i < p.positions.length; i++) {
				buf.writeLong(p.positions[i]);
				buf.writeByte(p.amounts[i]);
			}
		},
		buf -> {
			byte material = buf.readByte();
			int n = Math.min(buf.readVarInt(), MaterialSyncPayload.MAX_ENTRIES);
			long[] positions = new long[n];
			byte[] amounts = new byte[n];
			for (int i = 0; i < n; i++) {
				positions[i] = buf.readLong();
				amounts[i] = buf.readByte();
			}
			return new SimEchoPayload(material, positions, amounts);
		});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
