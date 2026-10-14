package me.skaffy.mixin;

import me.skaffy.net.ExplosionPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells nearby modded clients about every explosion with its exact radius (sent before the vanilla explosion packet). */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
	@Shadow @Final private ServerLevel level;

	@Shadow
	public abstract Vec3 center();

	@Shadow
	public abstract float radius();

	@Inject(method = "explode", at = @At("HEAD"))
	private void realisticWater$announce(CallbackInfo ci) {
		Vec3 c = this.center();
		ExplosionPayload payload = new ExplosionPayload(c.x, c.y, c.z, this.radius());
		for (ServerPlayer player : this.level.players()) {
			if (player.distanceToSqr(c) < 96.0 * 96.0 && ServerPlayNetworking.canSend(player, ExplosionPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}
}
