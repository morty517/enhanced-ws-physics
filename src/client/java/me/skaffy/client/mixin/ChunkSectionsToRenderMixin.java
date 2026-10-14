package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.8 has no outline pass to hook like 26.3 does, so the simulation and water composite run inside the main level pass, right
 * after opaque terrain, entities and block entities and just before translucent terrain. The scene colour/depth the water refracts
 * is complete at that point, and vanilla translucent blocks are then depth-tested against the water.
 */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void realisticWater$renderWater(ChunkSectionLayerGroup group, CallbackInfo ci) {
		if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
			WaterWorld.get().renderFrame();
		}
	}
}
