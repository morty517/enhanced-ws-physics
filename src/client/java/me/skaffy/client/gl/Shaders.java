package me.skaffy.client.gl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/** Loads GLSL from the mod jar, resolves {@code #include "file"}, injects defines and compiles for OpenGL. */
public final class Shaders {
	private static final String ROOT = "/assets/realistic-water/shaders/";
	public static final int VERTEX = GL20.GL_VERTEX_SHADER;
	public static final int FRAGMENT = GL20.GL_FRAGMENT_SHADER;
	public static final int COMPUTE = GL43.GL_COMPUTE_SHADER;

	private Shaders() {
	}

	public static String load(String name) {
		try (InputStream in = Shaders.class.getResourceAsStream(ROOT + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing shader " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read shader " + name, e);
		}
	}

	private static String preprocess(String source, Set<String> seen) {
		StringBuilder out = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#include")) {
				int a = trimmed.indexOf('"');
				int b = trimmed.lastIndexOf('"');
				String file = trimmed.substring(a + 1, b);
				if (seen.add(file)) {
					out.append(preprocess(load(file), seen)).append('\n');
				}
			} else {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	/** Full source text as it is handed to the driver (also used by the offline validator). */
	public static String source(String name, Map<String, String> defines) {
		String source = load(name);
		int nl = source.indexOf('\n');
		StringBuilder head = new StringBuilder(source.substring(0, nl + 1));
		defines.forEach((k, v) -> head.append("#define ").append(k).append(' ').append(v).append('\n'));
		return preprocess(head + source.substring(nl + 1), new HashSet<>());
	}

	public static int compile(String name, int kind, Map<String, String> defines) {
		String full = source(name, defines);
		int shader = GL20.glCreateShader(kind);
		GL20.glShaderSource(shader, full);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
			String log = GL20.glGetShaderInfoLog(shader, 65536);
			GL20.glDeleteShader(shader);
			throw new IllegalStateException("Shader " + name + " failed to compile:\n" + log);
		}
		return shader;
	}

	public static int link(String name, int... shaders) {
		int program = GL20.glCreateProgram();
		for (int s : shaders) {
			GL20.glAttachShader(program, s);
		}
		GL20.glLinkProgram(program);
		for (int s : shaders) {
			GL20.glDetachShader(program, s);
			GL20.glDeleteShader(s);
		}
		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
			String log = GL20.glGetProgramInfoLog(program, 65536);
			GL20.glDeleteProgram(program);
			throw new IllegalStateException("Program " + name + " failed to link:\n" + log);
		}
		return program;
	}
}
