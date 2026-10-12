package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Shared bind/push logic for compute and graphics programs.
 *
 * <p>Vulkan push constants become a 64-byte uniform block at {@link Gl#PUSH_BINDING}. Each {@link #push} writes a fresh slot of a
 * ring buffer so in-flight draws keep their own values; like Vulkan push constants, the last pushed values stay visible to
 * every program bound afterwards until the next push.
 */
public abstract class Program implements AutoCloseable {
	public static final int PUSH_SIZE = 64;
	private static final int PUSH_SLOTS = 8192;

	private static int pushBuffer;
	private static int pushStride;
	private static int pushCursor;
	private static long pushOffset = -1L;
	private static final int[] currentPush = new int[PUSH_SIZE / 4];

	protected final String name;
	protected final int[] types;
	protected int program;

	protected Program(String name, int... types) {
		this.name = name;
		this.types = types;
	}

	public void bind(Cmd cmd, Desc... descs) {
		Gl.requireInside();
		if (descs.length != this.types.length) {
			throw new IllegalArgumentException(this.name + " expects " + this.types.length + " bindings, got " + descs.length);
		}
		GlStateManager._glUseProgram(this.program);
		for (int i = 0; i < descs.length; i++) {
			Desc d = descs[i];
			if (d.type() != this.types[i]) {
				throw new IllegalArgumentException(this.name + " binding " + i + " has wrong descriptor type");
			}
			switch (d.type()) {
				case Desc.UBO -> GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, i, d.buffer(), d.offset(), d.range());
				case Desc.SSBO -> {
					if (d.range() == Desc.WHOLE) {
						GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, d.buffer());
					} else {
						GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, i, d.buffer(), d.offset(), d.range());
					}
				}
				case Desc.TEX -> {
					if (i >= Gl.MAX_UNITS) {
						throw new IllegalArgumentException(this.name + " uses texture unit " + i + ", the port only manages " + Gl.MAX_UNITS);
					}
					GlStateManager._activeTexture(GL20.GL_TEXTURE0 + i);
					GlStateManager._bindTexture(d.texture());
					GL33.glBindSampler(i, d.sampler());
				}
				default -> throw new IllegalArgumentException("Unknown descriptor type " + d.type());
			}
		}
		bindPush();
	}

	public void push(Cmd cmd, int... values) {
		if (values.length > currentPush.length) {
			throw new IllegalArgumentException(this.name + " pushes " + values.length * 4 + " bytes, max " + PUSH_SIZE);
		}
		java.util.Arrays.fill(currentPush, 0);
		System.arraycopy(values, 0, currentPush, 0, values.length);
		writePushSlot();
	}

	private static void ensurePushRing() {
		if (pushBuffer != 0) {
			return;
		}
		int align = Gl.uboAlignment();
		pushStride = (PUSH_SIZE + align - 1) / align * align;
		pushBuffer = GL15.glGenBuffers();
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, pushBuffer);
		GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) pushStride * PUSH_SLOTS, GL15.GL_STREAM_DRAW);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
		pushCursor = 0;
		pushOffset = -1L;
	}

	private static void writePushSlot() {
		ensurePushRing();
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, pushBuffer);
		if (pushCursor >= PUSH_SLOTS) {
			// Orphan the storage instead of waiting for the GPU to finish with old slots.
			GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) pushStride * PUSH_SLOTS, GL15.GL_STREAM_DRAW);
			pushCursor = 0;
		}
		pushOffset = (long) pushCursor++ * pushStride;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = stack.malloc(PUSH_SIZE);
			for (int i = 0; i < currentPush.length; i++) {
				data.putInt(i * 4, currentPush[i]);
			}
			GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, pushOffset, data);
		}
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
		GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, Gl.PUSH_BINDING, pushBuffer, pushOffset, PUSH_SIZE);
	}

	private static void bindPush() {
		if (pushOffset < 0L) {
			writePushSlot();
		} else {
			GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, Gl.PUSH_BINDING, pushBuffer, pushOffset, PUSH_SIZE);
		}
	}

	public static int f(float v) {
		return Float.floatToRawIntBits(v);
	}

	static void releaseShared() {
		if (pushBuffer != 0) {
			GL15.glDeleteBuffers(pushBuffer);
			pushBuffer = 0;
			pushOffset = -1L;
		}
	}

	@Override
	public void close() {
		if (this.program != 0) {
			GL20.glDeleteProgram(this.program);
			this.program = 0;
		}
	}
}
