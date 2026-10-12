package me.skaffy.client.gl;

/**
 * One resource binding for {@link Program#bind}. Binding {@code i} of a program goes to slot {@code i} of the matching GL
 * namespace (uniform block, storage block or texture unit), which is what the shaders' {@code layout(binding = i)} expects.
 */
public record Desc(int type, int buffer, long offset, long range, int texture, int sampler) {
	public static final int UBO = 1;
	public static final int SSBO = 2;
	public static final int TEX = 3;

	static final long WHOLE = -1L;

	public static Desc ubo(GlBuf buf, long offset, long range) {
		return new Desc(UBO, buf.handle, offset, range, 0, 0);
	}

	public static Desc ssbo(GlBuf buf) {
		return new Desc(SSBO, buf.handle, 0L, WHOLE, 0, 0);
	}

	public static Desc ssbo(GlBuf buf, long offset, long range) {
		return new Desc(SSBO, buf.handle, offset, range, 0, 0);
	}

	public static Desc tex(int texture, int sampler) {
		return new Desc(TEX, 0, 0L, 0L, texture, sampler);
	}

	public static Desc tex(GlTex tex, Sampler sampler) {
		return tex(tex.id, sampler.handle);
	}
}
