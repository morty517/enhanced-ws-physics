package me.skaffy.client.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.util.Map;
import org.lwjgl.opengl.GL43;

public final class ComputeProgram extends Program {
	public static final int MAX_GROUPS_X = 65535;

	public ComputeProgram(String shader, Map<String, String> defines, int... types) {
		super(shader, types);
		this.program = Shaders.link(shader, Shaders.compile(shader, Shaders.COMPUTE, defines));
	}

	public ComputeProgram(String shader, int... types) {
		this(shader, Map.of(), types);
	}

	public static int groups(long items, int groupSize) {
		return (int) Math.max(1L, (items + groupSize - 1) / groupSize);
	}

	public static int launched(int groups) {
		if (groups <= MAX_GROUPS_X) {
			return groups;
		}
		return MAX_GROUPS_X * ((groups + MAX_GROUPS_X - 1) / MAX_GROUPS_X);
	}

	public void dispatch(Cmd cmd, int x, int y, int z) {
		Gl.requireInside();
		if (x > MAX_GROUPS_X && y == 1 && z == 1) {
			GL43.glDispatchCompute(MAX_GROUPS_X, (x + MAX_GROUPS_X - 1) / MAX_GROUPS_X, 1);
		} else {
			GL43.glDispatchCompute(x, y, z);
		}
	}

	public void dispatchIndirect(Cmd cmd, GlBuf args, long offset) {
		Gl.requireInside();
		GlStateManager._glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, args.handle);
		GL43.glDispatchComputeIndirect(offset);
	}
}
