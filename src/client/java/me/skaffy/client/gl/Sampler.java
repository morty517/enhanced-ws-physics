package me.skaffy.client.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;

/** Sampler object; overrides the bound texture's own filtering while it is bound to a unit. */
public final class Sampler implements AutoCloseable {
	public final int handle;

	public Sampler(boolean linear) {
		int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
		this.handle = GL33.glGenSamplers();
		GL33.glSamplerParameteri(this.handle, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL33.glSamplerParameteri(this.handle, GL11.GL_TEXTURE_MAG_FILTER, filter);
		GL33.glSamplerParameteri(this.handle, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL33.glSamplerParameteri(this.handle, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL33.glSamplerParameteri(this.handle, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
		GL33.glSamplerParameteri(this.handle, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
		GL33.glSamplerParameterf(this.handle, GL12.GL_TEXTURE_MAX_LOD, 0.0F);
	}

	@Override
	public void close() {
		GL33.glDeleteSamplers(this.handle);
	}
}
