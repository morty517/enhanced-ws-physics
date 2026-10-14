package me.skaffy.client.gl;

/**
 * Stand-in for a Vulkan command buffer. OpenGL executes calls immediately and in order, so this carries no state; it only keeps
 * the original "record into a command buffer" call shape and marks code that must run between {@link Gl#begin()} and
 * {@link Gl#end(Cmd)}.
 */
public final class Cmd {
	static final Cmd INSTANCE = new Cmd();

	private Cmd() {
	}
}
