package me.skaffy.client.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the OpenGL port tell Minecraft's encoder that we changed the bound program and pipeline state behind its back. */
@Mixin(GlCommandEncoder.class)
public interface GlCommandEncoderAccessor {
	@Accessor("lastProgram")
	void realisticWater$setLastProgram(GlProgram program);

	@Accessor("lastPipeline")
	void realisticWater$setLastPipeline(RenderPipeline pipeline);
}
