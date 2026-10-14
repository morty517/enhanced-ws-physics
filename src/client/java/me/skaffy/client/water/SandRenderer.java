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
			case SNOW -> "snow";
			case SANDSTONE -> "sandstone";
			case DIRT -> "dirt";
			case CLAY -> "clay";
			case SUSPICIOUS_CURD -> "white_concrete_powder";
			default -> "sand";
		} + ".png");
	}

	private final GraphicsProgram grains;
	private final ComputeProgram cull;
	/** Grain colours of every granular material slot (vanilla block textures), sampled per grain by sand_shade.glsl. */
	private final GlBuf textures;

	SandRenderer() {
		int U = Desc.UBO;
		int S = Desc.SSBO;
		GraphicsProgram.State opaque = new GraphicsProgram.State(GlTex.RGBA8, GlTex.D32, true, true, GraphicsProgram.LESS,
			GraphicsProgram.Blend.NONE);
		this.grains = new GraphicsProgram("grain.vert", "grain.frag", Map.of(), opaque, U, S, S);
		this.cull = new ComputeProgram("grain_cull.comp", U, S, S, S, S, S);
		this.textures = loadTextures();
	}

	private record Image(int w, int h, int average, int[] argb) {
	}

	/** Layout: 4 header ints per material slot (width, height, average, texel offset in ints), then the texels of every slot. */
	private static GlBuf loadTextures() {
		int slots = Material.GRAIN_SLOTS;
		Image[] images = new Image[slots];
		int total = 4 * slots;
		for (int s = 0; s < slots; s++) {
			images[s] = loadTexture(textureFor(Material.bySlot(Material.SAND, s)));
			total += images[s].argb().length;
		}
		GlBuf buf = GlBuf.upload(total * 4L, GlBuf.STORAGE);
		ByteBuffer m = buf.mapped();
		int offset = 4 * slots;
		for (int s = 0; s < slots; s++) {
			Image img = images[s];
			m.putInt(s * 16, img.w()).putInt(s * 16 + 4, img.h()).putInt(s * 16 + 8, img.average()).putInt(s * 16 + 12, offset);
			for (int i = 0; i < img.argb().length; i++) {
				m.putInt((offset + i) * 4, img.argb()[i]);
			}
			offset += img.argb().length;
		}
		buf.flush(0, total * 4L);
		return buf;
	}

	private static Image loadTexture(ResourceLocation id) {
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
		return new Image(w, h, average, argb);
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
			this.grains.bind(cb, body.ubo(), Desc.ssbo(r.surfaceParticles), Desc.ssbo(this.textures));
			this.grains.drawIndirect(cb, r.args, WaterRegion.ARGS_GRAIN_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.grains.close();
		this.cull.close();
		this.textures.close();
	}
}
