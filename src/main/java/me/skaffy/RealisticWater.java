package me.skaffy;

import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.WaterSyncPayload;
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
		PayloadTypeRegistry.playC2S().register(WaterSyncPayload.TYPE, WaterSyncPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SandSyncPayload.TYPE, SandSyncPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RemoveAllWaterPayload.TYPE, RemoveAllWaterPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WaterSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SandSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RemoveAllWaterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (isAuthority(context.server(), player)) {
				context.server().execute(() -> {
					int removed = payload.apply(player);
					player.sendSystemMessage(Component.literal("Removed " + removed + " realistic " + (payload.sand() ? "sand" : "water") + " blocks"));
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
