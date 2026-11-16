package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The simulation link over the Minecraft connection, for servers running the EnhancedWSRelay plugin (channel
 * {@code realistic-water:link}). Raw bytes both ways: client -> server a link message, server -> client a relayed message,
 * settings or a notice (see the client's Link class and the plugin).
 */
public record LinkPayload(byte[] data) implements CustomPacketPayload {
	public static final int MAX_SIZE = 4096;
	public static final Type<LinkPayload> TYPE = new Type<>(RealisticWater.id("link"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LinkPayload> CODEC = StreamCodec.of(
		(buf, p) -> buf.writeBytes(p.data),
		buf -> {
			int n = buf.readableBytes();
			if (n > MAX_SIZE) {
				buf.skipBytes(n);
				return new LinkPayload(new byte[0]);
			}
			byte[] data = new byte[n];
			buf.readBytes(data);
			return new LinkPayload(data);
		});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
