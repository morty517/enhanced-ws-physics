package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers the world fog colour of the frame (26.3 exposes it through LevelRenderState, 1.21.8 only returns it from setupFog). */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void realisticWater$captureFogColor(CallbackInfoReturnable<Vector4f> cir) {
		Vector4f color = cir.getReturnValue();
		if (color != null) {
			WaterWorld.FOG_COLOR.set(color);
		}
	}
}
