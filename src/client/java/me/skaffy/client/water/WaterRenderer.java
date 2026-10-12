package me.skaffy.client.water;

import java.util.List;
import java.util.Map;
import me.skaffy.client.gl.Desc;
import me.skaffy.client.gl.GraphicsProgram;
import me.skaffy.client.gl.Program;
import me.skaffy.client.gl.Sampler;
import me.skaffy.client.gl.Gl;
import me.skaffy.client.gl.GlTex;
import me.skaffy.client.gl.Cmd;

public final class WaterRenderer implements AutoCloseable {
	private static final int RGBA32F = GlTex.RGBA32F;

	private final int thicknessFormat;
	private final GraphicsProgram meshFront;
	private final GraphicsProgram dropletFront;
	private final GraphicsProgram meshThickness;
	private final GraphicsProgram dropletThickness;
	private final GraphicsProgram whitewaterProgram;
	private final GraphicsProgram compositeProgram;
	private final Sampler nearest = new Sampler(false);
	private final Sampler linear = new Sampler(true);

	private int width;
	private int height;
	private GlTex sceneDepth;
	private GlTex fluidZ;
	private GlTex sceneColor;
	private GlTex gbuffer;
	private GlTex thickness;
	private GlTex foam;

	public WaterRenderer() {
		// Desktop OpenGL 4.3 drivers blend 32-bit float targets, so the Vulkan format probe is not needed.
		this.thicknessFormat = GlTex.R32F;
		int U = Desc.UBO;
		int S = Desc.SSBO;
		int T = Desc.TEX;
		GraphicsProgram.State front = new GraphicsProgram.State(RGBA32F, GlTex.D32, true, true, GraphicsProgram.LESS, GraphicsProgram.Blend.NONE);
		GraphicsProgram.State add = new GraphicsProgram.State(this.thicknessFormat, GlTex.NONE, false, false, GraphicsProgram.ALWAYS,
			GraphicsProgram.Blend.ADD);
		Map<String, String> droplets = Map.of("DROPLETS", "1", "VIEW_BINDING", "3");
		this.meshFront = new GraphicsProgram("mesh.vert", "mesh_front.frag", Map.of(), front, U, S, S, S);
		this.dropletFront = new GraphicsProgram("particle.vert", "droplet_front.frag", droplets, front, U, S, S, S);
		this.meshThickness = new GraphicsProgram("mesh.vert", "mesh_thickness.frag", Map.of(), add, U, S, S, S, T);
		this.dropletThickness = new GraphicsProgram("particle.vert", "droplet_thickness.frag", droplets, add, U, S, S, S, T);
		this.whitewaterProgram = new GraphicsProgram("particle.vert", "whitewater.frag", Map.of("VIEW_BINDING", "4"),
			new GraphicsProgram.State(GlTex.RGBA16F, GlTex.NONE, false, false, GraphicsProgram.ALWAYS, GraphicsProgram.Blend.ADD),
			U, S, T, T, S);
		this.compositeProgram = new GraphicsProgram("fullscreen.vert", "composite.frag", Map.of(),
			new GraphicsProgram.State(GlTex.RGBA8, GlTex.D32, true, true, GraphicsProgram.ALWAYS, GraphicsProgram.Blend.NONE), U, T, T, T, T, T, S);
	}

	private void ensureTargets(int w, int h) {
		if (w == this.width && h == this.height && this.sceneDepth != null) {
			return;
		}
		this.releaseTargets();
		this.width = w;
		this.height = h;
		this.sceneDepth = new GlTex(GlTex.D32, w, h);
		this.fluidZ = new GlTex(GlTex.D32, w, h);
		this.sceneColor = new GlTex(GlTex.RGBA8, w, h);
		this.gbuffer = new GlTex(RGBA32F, w, h);
		this.thickness = new GlTex(this.thicknessFormat, w, h);
		this.foam = new GlTex(GlTex.RGBA16F, w, h);
	}

	private void releaseTargets() {
		for (GlTex t : new GlTex[]{this.sceneDepth, this.fluidZ, this.sceneColor, this.gbuffer, this.thickness, this.foam}) {
			if (t != null) {
				Gl.destroyLater(t);
			}
		}
		this.sceneDepth = null;
	}

	public void prepare(int w, int h) {
		this.ensureTargets(w, h);
	}

	public record Body(WaterRegion region, Desc ubo) {
	}

	public void record(Cmd cb, List<Body> bodies, Desc view, int mainColor, int mainDepth) {
		int w = this.width;
		int h = this.height;
		for (Body body : bodies) {
			WaterRegion r = body.region();
			r.mesher.build(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.staticSolid, r.mesher.fineBox(r.box),
				WaterSettings.sheetFill);
			Gl.submit(cb);
		}

		GlTex.copy(cb, mainDepth, this.sceneDepth.id, w, h);
		GlTex.copy(cb, mainDepth, this.fluidZ.id, w, h);
		GlTex.copy(cb, mainColor, this.sceneColor.id, w, h);
		Gl.barrier(cb);
		Desc depthTex = Desc.tex(this.sceneDepth, this.nearest);

		GraphicsProgram.begin(cb, w, h, this.gbuffer.id, new float[]{0, 0, 0, 0}, this.fluidZ.id);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshFront.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view);
			this.meshFront.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletFront.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), view);
			this.dropletFront.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletFront.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);

		GraphicsProgram.begin(cb, w, h, this.thickness.id, new float[]{0, 0, 0, 0}, 0);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshThickness.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view, depthTex);
			this.meshThickness.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletThickness.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), view, depthTex);
			this.dropletThickness.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletThickness.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);
		Gl.barrier(cb);

		GraphicsProgram.begin(cb, w, h, this.foam.id, new float[]{0, 0, 0, 0}, 0);
		if (WaterSettings.whitewater) {
			for (Body body : bodies) {
				WaterRegion r = body.region();
				this.whitewaterProgram.bind(cb, body.ubo(), Desc.ssbo(r.currentWhitewater()), depthTex, Desc.tex(this.gbuffer, this.nearest), view);
				this.whitewaterProgram.push(cb, Program.f(WaterSettings.wwRadius), Program.f(0.5F), Program.f(0.3F), Program.f(WaterSettings.wwMaxPixels),
					Program.f(WaterSettings.foamSize));
				this.whitewaterProgram.drawIndirect(cb, r.args, WaterRegion.ARGS_WW_DRAW);
			}
		}
		GraphicsProgram.end(cb);
		Gl.barrier(cb);

		GraphicsProgram.begin(cb, w, h, mainColor, null, mainDepth);
		this.compositeProgram.bind(cb, bodies.getFirst().ubo(),
			Desc.tex(this.gbuffer, this.nearest),
			Desc.tex(this.thickness, this.linear),
			Desc.tex(this.sceneColor, this.linear),
			depthTex,
			Desc.tex(this.foam, this.linear),
			view);
		this.compositeProgram.draw(cb, 3, 1);
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.releaseTargets();
		for (AutoCloseable c : new AutoCloseable[]{
			this.meshFront, this.dropletFront, this.meshThickness, this.dropletThickness, this.whitewaterProgram, this.compositeProgram,
			this.nearest, this.linear
		}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
