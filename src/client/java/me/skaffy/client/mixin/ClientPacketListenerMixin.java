package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explosions blast simulated material. The vanilla packet has no radius: guess it from the particle (big ones are TNT-sized). */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void realisticWater$blast(ClientboundExplodePacket packet, CallbackInfo ci) {
		float guess = packet.explosionParticle().getType() == ParticleTypes.EXPLOSION_EMITTER ? 4.0F : 2.0F;
		WaterWorld.get().onExplosion(packet.center(), guess, false);
	}
}
