package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayDeque;
import me.skaffy.RealisticWater;
import me.skaffy.client.mixin.GlCommandEncoderAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;

/**
 * OpenGL 4.3 replacement for the original Vulkan device glue.
 *
 * <p>The simulation was written against a "record commands, then submit" model. OpenGL executes commands in order on its own,
 * so a {@link Cmd} is only a token proving that we are inside {@link #begin()}/{@link #end(Cmd)}. Everything that Minecraft's
 * own GL backend caches (blend/depth/cull state, texture bindings, framebuffers, the current program) is either changed through
 * {@link GlStateManager} or invalidated in {@link #end(Cmd)}, so vanilla rendering is not disturbed.
 */
public final class Gl {
	public static final int FRAME_RING = 3;
	/** Uniform-block binding used for the 64 bytes that were push constants under Vulkan. */
	public static final int PUSH_BINDING = 7;
	/** Highest texture unit touched by our programs (exclusive). Minecraft caches units 0..11. */
	static final int MAX_UNITS = 8;

	private static final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
	private static long frame;
	private static @Nullable Boolean supported;
	private static @Nullable String unsupportedReason;
	private static String deviceInfo = "";
	private static int uboAlignment = 256;
	private static int ssboAlignment = 256;
	private static boolean inside;
	private static int vao;

	private record Deferred(long frame, AutoCloseable resource) {
	}

	private Gl() {
	}

	/** True when the current context can run the simulation (OpenGL 4.3 compute + SSBOs). Must be called on the render thread. */
	public static boolean ready() {
		if (supported == null) {
			supported = probe();
		}
		return supported;
	}

	/** GPU name and GL version string of the context the simulation runs on (empty before the first probe). */
	public static String deviceInfo() {
		return deviceInfo;
	}

	public static @Nullable String unsupportedReason() {
		return unsupportedReason;
	}

	private static boolean probe() {
		if (!RenderSystem.isOnRenderThread()) {
			return false;
		}
		GLCapabilities caps;
		try {
			caps = GL.getCapabilities();
		} catch (IllegalStateException e) {
			return false;
		}
		String version = GL11.glGetString(GL11.GL_VERSION);
		String renderer = GL11.glGetString(GL11.GL_RENDERER);
		if (!caps.OpenGL43) {
			unsupportedReason = "OpenGL 4.3 is required (compute shaders + storage buffers), this driver reports " + version;
			RealisticWater.LOGGER.error("Realistic Water: {} on {}", unsupportedReason, renderer);
			return false;
		}
		int computeSsbos = GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS);
		int fragmentSsbos = GL11.glGetInteger(GL43.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS);
		int vertexSsbos = GL11.glGetInteger(GL43.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS);
		int bindings = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
		long maxBlock = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) & 0xFFFFFFFFL;
		deviceInfo = renderer + " | GL " + version;
		uboAlignment = Math.max(16, GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
		ssboAlignment = Math.max(4, GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
		RealisticWater.LOGGER.info(
			"Realistic Water (OpenGL port): {} | {} | SSBO blocks compute={} vertex={} fragment={} bindings={} maxBlock={}MB align ubo={} ssbo={}",
			renderer, version, computeSsbos, vertexSsbos, fragmentSsbos, bindings, maxBlock >> 20, uboAlignment, ssboAlignment);
		if (computeSsbos < 11 || bindings < 12 || vertexSsbos < 3 || fragmentSsbos < 2) {
			unsupportedReason = "the driver exposes too few shader storage blocks (compute " + computeSsbos + ", vertex " + vertexSsbos
				+ ", fragment " + fragmentSsbos + "); the simulation needs 11/3/2";
			RealisticWater.LOGGER.error("Realistic Water: {}", unsupportedReason);
			return false;
		}
		return true;
	}

	static int uboAlignment() {
		return uboAlignment;
	}

	static int ssboAlignment() {
		return ssboAlignment;
	}

	public static long frame() {
		return frame;
	}

	public static int ringSlot() {
		return (int) (frame % FRAME_RING);
	}

	/** Starts a batch of simulation/render work. Pair with {@link #end(Cmd)}. */
	public static Cmd begin() {
		if (inside) {
			throw new IllegalStateException("Realistic Water GL batch already open");
		}
		inside = true;
		GlStateManager._disableScissorTest();
		GlStateManager._disableCull();
		GlStateManager._disablePolygonOffset();
		GlStateManager._disableColorLogicOp();
		GlStateManager._polygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
		GlStateManager._colorMask(true, true, true, true);
		if (vao == 0) {
			vao = GlStateManager._glGenVertexArrays();
		}
		return Cmd.INSTANCE;
	}

	/** Restores everything Minecraft's GL backend expects and forgets its cached program/pipeline so it re-applies them. */
	public static void end(Cmd cmd) {
		if (!inside) {
			return;
		}
		inside = false;
		barrier(cmd);
		GlStateManager._glUseProgram(0);
		GlStateManager._glBindVertexArray(0);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GlStateManager._glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
		GlStateManager._glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
		GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		for (int unit = 0; unit < MAX_UNITS; unit++) {
			GL33.glBindSampler(unit, 0);
		}
		GlStateManager._activeTexture(GL20.GL_TEXTURE0);
		GlStateManager._disableBlend();
		GlStateManager._depthMask(true);
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		if (RenderSystem.getDevice().createCommandEncoder() instanceof GlCommandEncoder encoder) {
			GlCommandEncoderAccessor accessor = (GlCommandEncoderAccessor) encoder;
			accessor.realisticWater$setLastProgram(null);
			accessor.realisticWater$setLastPipeline(null);
		}
	}

	static void requireInside() {
		if (!inside) {
			throw new IllegalStateException("Realistic Water GL call outside of Gl.begin()/Gl.end()");
		}
	}

	static int emptyVao() {
		return vao;
	}

	public static void barrier(Cmd cmd) {
		GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
	}

	public static void destroyLater(@Nullable AutoCloseable resource) {
		if (resource != null) {
			deferred.add(new Deferred(frame + FRAME_RING + 1, resource));
		}
	}

	public static void endFrame() {
		frame++;
		while (!deferred.isEmpty() && deferred.peek().frame <= frame) {
			closeQuietly(deferred.poll().resource);
		}
	}

	public static void waitIdle() {
		if (supported == Boolean.TRUE) {
			GL11.glFinish();
		}
	}

	public static void flushDeferredNow() {
		waitIdle();
		while (!deferred.isEmpty()) {
			closeQuietly(deferred.poll().resource);
		}
	}

	/** Frees the port's shared GL objects (push-constant ring, framebuffer, empty VAO). */
	public static void releaseShared() {
		if (supported != Boolean.TRUE) {
			return;
		}
		Program.releaseShared();
		GraphicsProgram.releaseShared();
		if (vao != 0) {
			GL30.glDeleteVertexArrays(vao);
			vao = 0;
		}
	}

	private static void closeQuietly(AutoCloseable c) {
		try {
			c.close();
		} catch (Exception e) {
			RealisticWater.LOGGER.debug("Failed to release a Realistic Water GL resource", e);
		}
	}
}
