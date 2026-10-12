package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;

public final class GraphicsProgram extends Program {
	public static final int NEVER = GL11.GL_NEVER;
	public static final int LESS = GL11.GL_LESS;
	public static final int LEQUAL = GL11.GL_LEQUAL;
	public static final int GREATER = GL11.GL_GREATER;
	public static final int ALWAYS = GL11.GL_ALWAYS;

	public enum Blend { NONE, ADD }

	/** colorFormat/depthFormat are kept for parity with the Vulkan pipelines; GL only needs the depth/blend state. */
	public record State(int colorFormat, int depthFormat, boolean depthTest, boolean depthWrite, int depthCompare, Blend blend) {
	}

	private static int fbo;

	private final State state;

	public GraphicsProgram(String vert, String frag, Map<String, String> defines, State state, int... types) {
		super(vert + "+" + frag, types);
		this.state = state;
		int vs = Shaders.compile(vert, Shaders.VERTEX, defines);
		int fs;
		try {
			fs = Shaders.compile(frag, Shaders.FRAGMENT, defines);
		} catch (RuntimeException e) {
			org.lwjgl.opengl.GL20.glDeleteShader(vs);
			throw e;
		}
		this.program = Shaders.link(this.name, vs, fs);
	}

	/** Binds {@code colorTexture} (and optionally {@code depthTexture}, 0 for none) as the render target. */
	public static void begin(Cmd cmd, int width, int height, int colorTexture, float[] clearColor, int depthTexture) {
		Gl.requireInside();
		if (fbo == 0) {
			fbo = GL30.glGenFramebuffers();
		}
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Realistic Water framebuffer incomplete: 0x" + Integer.toHexString(status));
		}
		GlStateManager._viewport(0, 0, width, height);
		if (clearColor != null) {
			GlStateManager._colorMask(true, true, true, true);
			GL30.glClearBufferfv(GL11.GL_COLOR, 0, clearColor);
		}
	}

	public static void end(Cmd cmd) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
	}

	@Override
	public void bind(Cmd cmd, Desc... descs) {
		super.bind(cmd, descs);
		if (this.state.depthTest()) {
			GlStateManager._enableDepthTest();
			GlStateManager._depthFunc(this.state.depthCompare());
		} else {
			GlStateManager._disableDepthTest();
		}
		GlStateManager._depthMask(this.state.depthWrite());
		if (this.state.blend() == Blend.ADD) {
			GlStateManager._enableBlend();
			GL14.glBlendEquation(GL14.GL_FUNC_ADD);
			GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
		} else {
			GlStateManager._disableBlend();
		}
		GlStateManager._colorMask(true, true, true, true);
		GlStateManager._glBindVertexArray(Gl.emptyVao());
	}

	public void draw(Cmd cmd, int vertices, int instances) {
		GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, vertices, instances);
	}

	public void drawIndirect(Cmd cmd, GlBuf args, long offset) {
		GlStateManager._glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, args.handle);
		GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, offset);
	}

	static void releaseShared() {
		if (fbo != 0) {
			GL30.glDeleteFramebuffers(fbo);
			fbo = 0;
		}
	}
}
