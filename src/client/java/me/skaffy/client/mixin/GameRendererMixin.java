package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@ModifyArg(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")
	)
	private Matrix4f realisticWater$captureProjection(Matrix4f projection) {
		WaterWorld.LEVEL_PROJECTION.set(projection);
		return projection;
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void realisticWater$beginWorldFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
		WaterWorld.get().beginWorldFrame();
	}

	/**
	 * Fallback for renderers that replace vanilla terrain rendering (e.g. Sodium) so the translucent-group hook never fires:
	 * composite the water once the level is drawn instead of not at all.
	 */
	@Inject(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			shift = At.Shift.AFTER
		)
	)
	private void realisticWater$renderWaterFallback(DeltaTracker deltaTracker, CallbackInfo ci) {
		WaterWorld.get().renderFrameIfMissed();
	}
}
