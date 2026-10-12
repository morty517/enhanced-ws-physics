package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.nio.ByteBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * OpenGL buffer with the same API shape as the original VMA-backed Vulkan buffer.
 *
 * <ul>
 *     <li>{@link #device} - GPU-only storage.</li>
 *     <li>{@link #upload} - a CPU copy you write through {@link #mapped()}, pushed to the GPU by {@link #flush}.</li>
 *     <li>{@link #readback} - a CPU copy that {@link #invalidate} refreshes from the GPU. The simulation only reads a readback
 *     slot {@link Gl#FRAME_RING} frames after it was written, so the driver has normally finished the copy by then.</li>
 * </ul>
 */
public final class GlBuf implements AutoCloseable {
	public static final int STORAGE = 1;
	public static final int UNIFORM = 2;
	public static final int INDIRECT = 4;
	public static final int SRC = 8;
	public static final int DST = 16;

	private static final int SCRATCH_TARGET = GL31.GL_COPY_WRITE_BUFFER;

	public final int handle;
	public final long size;
	private final @Nullable ByteBuffer cpu;
	private boolean closed;

	private GlBuf(long size, int glUsage, boolean cpuCopy) {
		this.size = size;
		this.handle = GL15.glGenBuffers();
		GlStateManager._glBindBuffer(SCRATCH_TARGET, this.handle);
		GL15.glBufferData(SCRATCH_TARGET, size, glUsage);
		// Vulkan memory from VMA is not guaranteed to be zero either, but starting from a known state makes stale counters harmless.
		GL43.glClearBufferData(SCRATCH_TARGET, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL15.GL_UNSIGNED_INT, (ByteBuffer) null);
		GlStateManager._glBindBuffer(SCRATCH_TARGET, 0);
		this.cpu = cpuCopy ? MemoryUtil.memCalloc(Math.toIntExact(size)) : null;
	}

	public static GlBuf device(long size, int usage) {
		return new GlBuf(align(size), GL15.GL_DYNAMIC_COPY, false);
	}

	public static GlBuf upload(long size, int usage) {
		return new GlBuf(align(size), GL15.GL_DYNAMIC_DRAW, true);
	}

	public static GlBuf readback(long size) {
		return new GlBuf(align(size), GL15.GL_STREAM_READ, true);
	}

	private static long align(long size) {
		return Math.max(16L, (size + 15L) & ~15L);
	}

	public ByteBuffer mapped() {
		if (this.cpu == null) {
			throw new IllegalStateException("Buffer has no CPU copy");
		}
		return this.cpu;
	}

	/** Pushes {@code length} bytes of the CPU copy starting at {@code offset} to the GPU. */
	public void flush(long offset, long length) {
		ByteBuffer c = this.mapped();
		long len = Math.min(length, this.size - offset);
		if (len <= 0) {
			return;
		}
		GlStateManager._glBindBuffer(SCRATCH_TARGET, this.handle);
		GL15.nglBufferSubData(SCRATCH_TARGET, offset, len, MemoryUtil.memAddress(c) + offset);
		GlStateManager._glBindBuffer(SCRATCH_TARGET, 0);
	}

	/** Refreshes {@code length} bytes of the CPU copy starting at {@code offset} from the GPU. */
	public void invalidate(long offset, long length) {
		ByteBuffer c = this.mapped();
		long len = Math.min(length, this.size - offset);
		if (len <= 0) {
			return;
		}
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.handle);
		GL15.nglGetBufferSubData(GL31.GL_COPY_READ_BUFFER, offset, len, MemoryUtil.memAddress(c) + offset);
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
	}

	public void fill(Cmd cmd, long offset, long length, int value) {
		long len = Math.min(length, this.size - offset);
		if (len <= 0) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			GlStateManager._glBindBuffer(SCRATCH_TARGET, this.handle);
			GL43.glClearBufferSubData(SCRATCH_TARGET, GL30.GL_R32UI, offset, len, GL30.GL_RED_INTEGER, GL15.GL_UNSIGNED_INT, stack.ints(value));
			GlStateManager._glBindBuffer(SCRATCH_TARGET, 0);
		}
	}

	public void fill(Cmd cmd, int value) {
		this.fill(cmd, 0L, this.size, value);
	}

	public void copyTo(Cmd cmd, GlBuf dst, long srcOffset, long dstOffset, long length) {
		if (length <= 0) {
			return;
		}
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.handle);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, dst.handle);
		GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, srcOffset, dstOffset, length);
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
	}

	/** Several copies between the same two buffers ({@code regions} holds srcOffset, dstOffset, size triples). */
	public void copyRegionsTo(Cmd cmd, GlBuf dst, long[] regions, int count) {
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.handle);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, dst.handle);
		for (int i = 0; i < count; i++) {
			GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, regions[i * 3], regions[i * 3 + 1], regions[i * 3 + 2]);
		}
		GlStateManager._glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
		GlStateManager._glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			GL15.glDeleteBuffers(this.handle);
			if (this.cpu != null) {
				MemoryUtil.memFree(this.cpu);
			}
		}
	}
}
