package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** 2D texture used as a render target or sampled input. */
public final class GlTex implements AutoCloseable {
	public static final int R32F = GL30.GL_R32F;
	public static final int R16F = GL30.GL_R16F;
	public static final int RGBA16F = GL30.GL_RGBA16F;
	public static final int RGBA32F = GL30.GL_RGBA32F;
	public static final int RGBA8 = GL11.GL_RGBA8;
	/** Matches Minecraft's DEPTH32 main-target format (GL_DEPTH_COMPONENT32) so glCopyImageSubData accepts it. */
	public static final int D32 = GL14.GL_DEPTH_COMPONENT32;
	public static final int NONE = 0;

	public final int id;
	public final int format;
	public final int width;
	public final int height;
	private boolean closed;

	public GlTex(int format, int width, int height) {
		this.format = format;
		this.width = width;
		this.height = height;
		this.id = GlStateManager._genTexture();
		int previous = GlStateManager._getActiveTexture();
		GlStateManager._activeTexture(GL30.GL_TEXTURE0);
		GlStateManager._bindTexture(this.id);
		GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, format, width, height);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		if (format == D32) {
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
		}
		GlStateManager._bindTexture(0);
		GlStateManager._activeTexture(previous);
	}

	public boolean isDepth() {
		return this.format == D32;
	}

	/** Copies a whole mip-0 image between two compatible 2D textures (raw GL texture names). */
	public static void copy(Cmd cmd, int srcTexture, int dstTexture, int width, int height) {
		GL43.glCopyImageSubData(srcTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, dstTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			GlStateManager._deleteTexture(this.id);
		}
	}
}
