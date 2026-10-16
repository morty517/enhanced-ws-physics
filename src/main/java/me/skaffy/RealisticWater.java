package me.skaffy;

import me.skaffy.net.ExplosionPayload;
import me.skaffy.net.MaterialSyncPayload;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SimEchoPayload;
import me.skaffy.net.SolidifyPayload;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealisticWater implements ModInitializer {
	public static final String MOD_ID = "realistic-water";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModContent.init();
		PayloadTypeRegistry.playC2S().register(MaterialSyncPayload.TYPE, MaterialSyncPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RemoveAllWaterPayload.TYPE, RemoveAllWaterPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SolidifyPayload.TYPE, SolidifyPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ExplosionPayload.TYPE, ExplosionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SimEchoPayload.TYPE, SimEchoPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SolidifyPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(MaterialSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> {
					// Tell the other players first (block updates only go out at the end of the tick), so linked ones can ignore them.
					SimEchoPayload echo = new SimEchoPayload(payload.material(), payload.positions(), payload.amounts());
					for (ServerPlayer other : context.player().level().players()) {
						if (other != context.player() && ServerPlayNetworking.canSend(other, SimEchoPayload.TYPE)) {
							ServerPlayNetworking.send(other, echo);
						}
					}
					payload.apply(context.player());
				});
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RemoveAllWaterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (isAuthority(context.server(), player)) {
				context.server().execute(() -> {
					int removed = payload.apply(player);
					player.sendSystemMessage(Component.literal("Removed " + removed + " realistic " + payload.materialOrWater().id.replace('_', ' ') + " blocks"));
				});
			}
		});
	}

	private static boolean isAuthority(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.getGameProfile()) || server.getPlayerList().isOp(player.getGameProfile());
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
