package me.skaffy.client.water;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import me.skaffy.Material;
import me.skaffy.RealisticWater;
import me.skaffy.client.gl.ComputeProgram;
import me.skaffy.client.gl.Desc;
import me.skaffy.client.gl.GraphicsProgram;
import me.skaffy.client.gl.Gl;
import me.skaffy.client.gl.GlBuf;
import me.skaffy.client.gl.GlTex;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import me.skaffy.client.gl.Cmd;

final class SandRenderer implements AutoCloseable {
	private static ResourceLocation textureFor(Material m) {
		return ResourceLocation.withDefaultNamespace("textures/block/" + switch (m) {
			case RED_SAND -> "red_sand";
			case GRAVEL -> "gravel";
			default -> "sand";
		} + ".png");
	}

	private final GraphicsProgram grains;
	private final ComputeProgram cull;
	/** Grain colours per granular material: the vanilla block texture, sampled per grain by sand_shade.glsl. */
	private final java.util.EnumMap<Material, GlBuf> textures = new java.util.EnumMap<>(Material.class);

	SandRenderer() {
		int U = Desc.UBO;
		int S = Desc.SSBO;
		GraphicsProgram.State opaque = new GraphicsProgram.State(GlTex.RGBA8, GlTex.D32, true, true, GraphicsProgram.LESS,
			GraphicsProgram.Blend.NONE);
		this.grains = new GraphicsProgram("grain.vert", "grain.frag", Map.of(), opaque, U, S, S);
		this.cull = new ComputeProgram("grain_cull.comp", U, S, S, S, S, S);
		for (Material m : Material.VALUES) {
			if (m.granular()) {
				this.textures.put(m, loadTexture(textureFor(m)));
			}
		}
	}

	private static GlBuf loadTexture(ResourceLocation id) {
		int[] argb;
		int w;
		int h;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage image = NativeImage.read(in)) {
			w = image.getWidth();
			h = Math.min(image.getHeight(), w);
			argb = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					argb[x + y * w] = image.getPixel(x, y);
				}
			}
		} catch (Exception e) {
			RealisticWater.LOGGER.warn("Couldn't read {}, using a flat sand colour", id, e);
			w = 1;
			h = 1;
			argb = new int[]{0xFFDBCFA3};
		}
		long r = 0;
		long g = 0;
		long b = 0;
		for (int c : argb) {
			r += (c >> 16) & 0xFF;
			g += (c >> 8) & 0xFF;
			b += c & 0xFF;
		}
		int n = argb.length;
		int average = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		long bytes = (4L + n) * 4L;
		GlBuf buf = GlBuf.upload(bytes, GlBuf.STORAGE);
		ByteBuffer m = buf.mapped();
		m.putInt(0, w).putInt(4, h).putInt(8, average).putInt(12, 0);
		for (int i = 0; i < n; i++) {
			m.putInt(16 + i * 4, argb[i]);
		}
		buf.flush(0, bytes);
		return buf;
	}

	void record(Cmd cb, List<WaterRenderer.Body> bodies, int width, int height, int mainColor, int mainDepth) {
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			if (r.takeFieldStale()) {
				r.mesher.buildField(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.mesher.fineBox(r.box), false);
			}
			r.args.fill(cb, WaterRegion.ARGS_GRAIN_DRAW + 4, 4, 0);
		}
		Gl.barrier(cb);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			this.cull.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), Desc.ssbo(r.surfaceParticles), Desc.ssbo(r.args),
				Desc.ssbo(r.counters));
			this.cull.push(cb, WaterRegion.C_FLUID + r.cur);
			this.cull.dispatchIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DISPATCH);
		}
		Gl.barrier(cb);
		GraphicsProgram.begin(cb, width, height, mainColor, null, mainDepth);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			this.grains.bind(cb, body.ubo(), Desc.ssbo(r.surfaceParticles), Desc.ssbo(this.textures.getOrDefault(r.material, this.textures.get(Material.SAND))));
			this.grains.drawIndirect(cb, r.args, WaterRegion.ARGS_GRAIN_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.grains.close();
		this.cull.close();
		this.textures.values().forEach(GlBuf::close);
	}
}
