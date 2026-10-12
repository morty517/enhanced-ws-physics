package me.skaffy.client.mixin;

import com.mojang.blaze3d.platform.Window;
import me.skaffy.RealisticWater;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft 1.21.8 asks GLFW for an OpenGL 3.2 core context. Mesa hands out its newest version anyway, but NVIDIA (and others)
 * create exactly what was asked for, so compute shaders and storage buffers (GL 4.3) would be missing even on a GPU that has
 * GL 4.6. Ask for 4.6 / 4.5 / 4.3 first and fall back to the vanilla 3.2 request if the driver refuses, so old hardware still
 * starts the game (the water just reports that it can't run).
 */
@Mixin(Window.class)
public class WindowMixin {
	private static final int[][] REALISTIC_WATER$VERSIONS = {{4, 6}, {4, 5}, {4, 3}};

	@Redirect(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false)
	)
	private long realisticWater$createWithModernContext(int width, int height, CharSequence title, long monitor, long share) {
		// Vanilla's boot error callback pops a "update your drivers" dialog and throws; failed attempts here must stay quiet.
		GLFWErrorCallback quiet = GLFWErrorCallback.create((error, description) ->
			RealisticWater.LOGGER.info("Realistic Water: GLFW declined a context ({}): {}", error, MemoryUtil.memUTF8(description)));
		GLFWErrorCallback previous = GLFW.glfwSetErrorCallback(quiet);
		long window = 0L;
		try {
			for (int[] v : REALISTIC_WATER$VERSIONS) {
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, v[0]);
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, v[1]);
				window = GLFW.glfwCreateWindow(width, height, title, monitor, share);
				if (window != 0L) {
					RealisticWater.LOGGER.info("Realistic Water: created an OpenGL {}.{} core context (vanilla asks for 3.2)", v[0], v[1]);
					break;
				}
			}
		} finally {
			GLFW.glfwSetErrorCallback(previous);
			quiet.free();
		}
		if (window != 0L) {
			return window;
		}
		RealisticWater.LOGGER.warn("Realistic Water: no OpenGL 4.3+ context available, using Minecraft's default 3.2 (the simulation will stay off)");
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
		return GLFW.glfwCreateWindow(width, height, title, monitor, share);
	}
}
