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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> water.clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> water.shutdown());
		me.skaffy.client.water.Grabber.register();
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
