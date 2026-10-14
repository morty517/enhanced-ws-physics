package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: an explosion happened here with this radius (the vanilla explosion packet doesn't carry the radius), so
 * the simulation can blast the material around it. Clients on servers without the mod guess the radius instead.
 */
public record ExplosionPayload(double x, double y, double z, float radius) implements CustomPacketPayload {
	public static final Type<ExplosionPayload> TYPE = new Type<>(RealisticWater.id("explosion"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ExplosionPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeDouble(p.x);
			buf.writeDouble(p.y);
			buf.writeDouble(p.z);
			buf.writeFloat(p.radius);
		},
		buf -> new ExplosionPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
