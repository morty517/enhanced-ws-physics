package me.skaffy.client;

import me.skaffy.Material;
import me.skaffy.ModContent;
import me.skaffy.client.water.WaterCommands;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import com.mojang.blaze3d.vertex.VertexConsumer;

public class RealisticWaterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		registerInvisibleFluid();
		WaterWorld water = WaterWorld.get();
		ClientCommandRegistrationCallback.EVENT.register(WaterCommands::register);
		ClientTickEvents.END_CLIENT_TICK.register(water::tick);
		ClientChunkEvents.CHUNK_LOAD.register(water::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(water::onChunkUnload);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> water.onEntityUnload(entity));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> water.clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> water.shutdown());
		me.skaffy.client.water.Grabber.register();
		// Snowballs: the simulation draws the snow they carry, so the flat vanilla sprite is hidden (unless turned off).
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(net.minecraft.world.entity.EntityType.SNOWBALL,
			ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<net.minecraft.world.entity.projectile.Snowball>(ctx) {
				@Override
				public void render(net.minecraft.client.renderer.entity.state.ThrownItemRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
					net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
					if (!me.skaffy.client.water.WaterSettings.realisticSnowballs || !me.skaffy.client.water.WaterWorld.get().active()) {
						super.render(state, pose, buffers, light);
					}
				}
			});
		SelfTest.init();
	}

	private static void registerInvisibleFluid() {
		// Water sprites/tint are still provided (break particles, item models), but the block mesh is never drawn:
		// the simulation renders the surface itself.
		SimpleFluidRenderHandler invisible = new SimpleFluidRenderHandler(
			SimpleFluidRenderHandler.WATER_STILL, SimpleFluidRenderHandler.WATER_FLOWING, SimpleFluidRenderHandler.WATER_OVERLAY, 0x3F76E4) {
			@Override
			public void renderFluid(BlockPos pos, BlockAndTintGetter level, VertexConsumer vertexConsumer, BlockState blockState, FluidState fluidState) {
			}
		};
		for (Material m : Material.VALUES) {
			if (m.fluid()) {
				FluidRenderHandlerRegistry.INSTANCE.register(ModContent.sourceFluid(m), ModContent.flowingFluid(m), invisible);
			}
		}
	}
}
