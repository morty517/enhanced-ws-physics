package me.skaffy.client.water;

import me.skaffy.Material;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.client.gl.Cmd;
import me.skaffy.client.gl.Desc;
import me.skaffy.client.gl.Gl;
import me.skaffy.client.gl.GlBuf;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.MaterialSyncPayload;
import me.skaffy.net.SolidifyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jetbrains.annotations.Nullable;

public final class WaterWorld {
	private static final WaterWorld INSTANCE = new WaterWorld();
	static final int UBO_SLOT = 4608;
	static final int UBO_SIZE = 4512;
	static final float PARTICLES_PER_CELL = 8.0F;
	static final float SAND_POISSON = 0.3F;

	static float sandWaveSpeed() {
		float nu = SAND_POISSON;
		return (float) Math.sqrt(WaterSettings.sandStiffness * (1.0F - nu) / ((1.0F + nu) * (1.0F - 2.0F * nu)));
	}
	private static final double WORLD_WRAP = 4096.0;
	static final int MAX_ENTITIES = 32;
	static final int MAX_SOURCES = 64;
	static final int MAX_REGIONS = 16;
	private static final int JOIN_DISTANCE = 16;
	private static final int SYNC_INTERVAL = 30;
	private static final int ECHO_TIMEOUT = 200;

	public static final Matrix4f LEVEL_PROJECTION = new Matrix4f();
	/** World fog colour of the current frame, captured from FogRenderer.setupFog (1.21.8 has no LevelRenderState yet). */
	public static final Vector4f FOG_COLOR = new Vector4f(0.5F, 0.7F, 1.0F, 1.0F);

	private static final class Echoes {
		final Long2IntOpenHashMap expected = new Long2IntOpenHashMap();
		final Long2IntOpenHashMap frame = new Long2IntOpenHashMap();

		boolean consume(long key, int amount) {
			if (this.expected.containsKey(key) && this.expected.get(key) == amount) {
				this.expected.remove(key);
				this.frame.remove(key);
				return true;
			}
			return false;
		}

		void expect(long key, int amount, int frame) {
			this.expected.put(key, amount);
			this.frame.put(key, frame);
		}

		void expire(int now) {
			this.frame.long2IntEntrySet().removeIf(e -> {
				if (now - e.getIntValue() > ECHO_TIMEOUT) {
					this.expected.remove(e.getLongKey());
					return true;
				}
				return false;
			});
		}

		void clear() {
			this.expected.clear();
			this.frame.clear();
		}
	}

	private final List<WaterRegion> regions = new ArrayList<>();
	/** Spring blocks (infinite sources) of every fluid material. */
	private final List<Source> sources = new ArrayList<>();

	private record Source(BlockPos pos, Material material) {
	}

	private final ArrayDeque<ChunkPos> chunkScanQueue = new ArrayDeque<>();
	private final java.util.EnumMap<Material, Echoes> echoes = new java.util.EnumMap<>(Material.class);
	private final List<Object[]> pendingFills = new ArrayList<>();
	private final FlowSounds sounds = new FlowSounds();
	/** Voxelised static geometry: one cache for fluids, one for granular materials (they ignore granular blocks). */
	private @Nullable SolidCache solids;
	private @Nullable SolidCache sandSolids;
	private @Nullable WaterRenderer renderer;
	private @Nullable SandRenderer sandRenderer;
	private @Nullable GlBuf uboRing;
	/** Per fluid material: the probe result (camera underwater, etc.) the composite pass of that material reads. */
	private final java.util.EnumMap<Material, GlBuf> frameViews = new java.util.EnumMap<>(Material.class);
	private boolean paused;
	private boolean failed;
	private long lastFrameNanos;
	private float simTime;
	private int frameCounter;
	private int nextRegionId;
	private int lastSteps;
	private float lastDt;
	private float smoothedFrameMs;
	private boolean localPlayerFirst;
	private int builtResolution = WaterSettings.resolution;
	private int builtSandResolution = WaterSettings.sandResolution;
	private int lastSandSteps;
	private float lastSandDt;

	public static WaterWorld get() {
		return INSTANCE;
	}

	public static boolean isRealistic(FluidState state) {
		return RealisticWaterFluid.isRealistic(state);
	}

	private static int amountOf(BlockState state, Material material) {
		return material.amount(state);
	}

	private Echoes echoes(Material material) {
		return this.echoes.computeIfAbsent(material, m -> new Echoes());
	}

	public boolean togglePause() {
		this.paused = !this.paused;
		return this.paused;
	}

	private SolidCache solids(Material material) {
		return this.solids(material, resolutionFor(material));
	}

	private SolidCache solids(Material material, int res) {
		if (material.granular()) {
			if (this.sandSolids == null || this.sandSolids.res != res) {
				this.sandSolids = new SolidCache(res, true);
			}
			return this.sandSolids;
		}
		if (this.solids == null || this.solids.res != res) {
			this.solids = new SolidCache(res, false);
		}
		return this.solids;
	}

	static int resolutionFor(Material material) {
		return Math.max(1, material.granular() ? WaterSettings.sandResolution : WaterSettings.resolution);
	}

	private static boolean isHost() {
		return Minecraft.getInstance().getSingleplayerServer() != null;
	}


	private @Nullable WaterRegion regionNear(BlockPos pos, int margin, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material.base() && r.res == resolutionFor(material) && r.claims(pos, margin)) {
				return r;
			}
		}
		return null;
	}

	private WaterRegion regionFor(BlockPos pos, Material material) {
		WaterRegion r = this.regionNear(pos, JOIN_DISTANCE, material);
		if (r == null) {
			// One simulation per kind: every fluid shares the water solver, every granular material the sand solver.
			r = new WaterRegion(this.nextRegionId++, resolutionFor(material), material.base());
			this.regions.add(r);
		}
		return r;
	}

	private boolean warnedNoGl;

	private boolean glReady() {
		if (Gl.ready()) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!this.warnedNoGl && mc.player != null) {
			this.warnedNoGl = true;
			String why = Gl.unsupportedReason();
			mc.player.displayClientMessage(Component.literal(
				"Realistic Water (OpenGL port) can't run on this GPU/driver: " + (why != null ? why : "OpenGL 4.3 compute shaders are required")
					+ ". Updating the graphics driver usually fixes this."), false);
		}
		return false;
	}

	public void addWater(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.WATER);
	}

	public void addSand(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.SAND);
	}

	public void addMaterial(BlockPos pos, double y0, double y1, Material material) {
		if (y1 <= y0 || !this.glReady()) {
			return;
		}
		WaterRegion r = this.regionFor(pos, material);
		r.addEmitter(new WaterRegion.Emitter(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1, 0, 0, 0,
			material.slot()));
		r.owned.add(pos.asLong());
	}

	public void addEmitter(WaterRegion.Emitter e, Material material) {
		if (!this.glReady()) {
			return;
		}
		BlockPos center = BlockPos.containing((e.minX() + e.maxX()) / 2, (e.minY() + e.maxY()) / 2, (e.minZ() + e.maxZ()) / 2);
		this.regionFor(center, material).addEmitter(e.withSlot(material.slot()));
	}

	private void removeMaterial(BlockPos pos, double y0, double y1, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material.base() && (r.windowContains(pos, 1) || r.claims(pos, 1))) {
				r.removals.add(new WaterRegion.Removal(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1));
				if (y0 <= 0.0) {
					r.owned.remove(pos.asLong());
				}
			}
		}
	}

	private void addSource(BlockPos pos, Material material) {
		Source source = new Source(pos.immutable(), material);
		if (this.sources.contains(source) || !this.glReady()) {
			return;
		}
		if (this.sources.size() >= MAX_SOURCES) {
			this.sources.removeFirst();
		}
		this.sources.add(source);
		this.regionFor(source.pos(), material).claimBlock(source.pos());
	}

	private void removeSource(BlockPos pos) {
		this.sources.removeIf(s -> s.pos().equals(pos));
	}

	public void removeAll(int radiusChunks, Material material) {
		if (ClientPlayNetworking.canSend(RemoveAllWaterPayload.TYPE)) {
			ClientPlayNetworking.send(new RemoveAllWaterPayload(radiusChunks, material));
		}
		// Materials share regions, so remove only the particles that are mostly this material.
		for (WaterRegion r : this.regions) {
			if (r.material == material.base() && r.hasWindow) {
				int m = WaterRegion.KILL_MARGIN;
				r.removals.add(new WaterRegion.Removal(r.ox - m, r.oy - m, r.oz - m, r.ox + r.sx + m, r.oy + r.sy + m, r.oz + r.sz + m, material.slot()));
			}
		}
		this.echoes(material).clear();
		this.sources.removeIf(s -> s.material() == material);
	}

	public void reload(ClientLevel level) {
		this.closeRegions();
		this.chunkScanQueue.clear();
		int radius = Minecraft.getInstance().options.renderDistance().get();
		ChunkPos center = new ChunkPos(Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition());
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				this.chunkScanQueue.add(new ChunkPos(center.x + dx, center.z + dz));
			}
		}
	}

	private void closeRegions() {
		for (WaterRegion r : this.regions) {
			r.close();
		}
		this.regions.clear();
		this.echoes.values().forEach(Echoes::clear);
		this.pendingFills.clear();
		this.sounds.clear();
	}


	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState == newState) {
			return;
		}
		boolean wasSpring = RealisticWaterBlock.isInfinite(oldState);
		boolean isSpring = RealisticWaterBlock.isInfinite(newState);
		long key = pos.asLong();
		if (isSpring) {
			Material spring = Material.of(newState);
			if (spring != null) {
				this.addSource(pos, spring);
			}
		} else if (wasSpring) {
			this.removeSource(pos);
		}
		for (Material m : Material.VALUES) {
			if (m.fluid() && (isSpring || wasSpring)) {
				continue;
			}
			int before = m.amount(oldState);
			int after = m.amount(newState);
			if (before == after || this.echoes(m).consume(key, after)) {
				continue;
			}
			if (m.fluid()) {
				if (after > before) {
					this.addMaterial(pos.immutable(), before / 8.0, after >= 8 ? 1.0 : after / 8.0, m);
				} else {
					this.removeMaterial(pos.immutable(), after / 8.0, 1.0, m);
				}
			} else if (after > before) {
				this.addMaterial(pos.immutable(), before / 8.0, after / 8.0, m);
			} else if (newState.isAir()) {
				this.removeMaterial(pos.immutable(), 0.0, 1.0, m);
			}
		}

		if (oldState.getBlock() != newState.getBlock() || !oldState.getCollisionShape(level, pos).equals(newState.getCollisionShape(level, pos))) {
			// One solid cache per kind (fluid / granular), shared by every material of that kind.
			for (Material representative : new Material[]{Material.WATER, Material.SAND}) {
				SolidCache cache = this.solids(representative);
				if (cache.update(level, pos)) {
					for (WaterRegion r : this.regions) {
						if (r.material.kind == representative.kind && r.res == cache.res) {
							r.patchSolid(level, cache, pos);
						}
					}
				}
			}
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		for (SolidCache cache : new SolidCache[]{this.solids, this.sandSolids}) {
			if (cache != null) {
				cache.invalidateChunk(pos.x, pos.z);
			}
		}
		for (WaterRegion r : this.regions) {
			if (r.hasWindow && r.overlapsChunk(pos.x, pos.z)) {
				r.markSolidsDirty(level, this.solids(r.material, r.res));
			}
		}
		this.chunkScanQueue.add(pos);
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		for (SolidCache cache : new SolidCache[]{this.solids, this.sandSolids}) {
			if (cache != null) {
				cache.invalidateChunk(chunk.getPos().x, chunk.getPos().z);
			}
		}
	}

	private boolean owned(long key, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material.base() && r.owned.contains(key)) {
				return true;
			}
		}
		return false;
	}

	private void scanChunk(ClientLevel level, ChunkPos chunkPos) {
		if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
			return;
		}
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir()
				|| !section.maybeHas(state -> state.getBlock() instanceof RealisticWaterBlock || state.getBlock() instanceof RealisticSandBlock)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				BlockState state = section.getBlockState(i & 15, (i >> 4) & 15, i >> 8);
				Material m = Material.of(state);
				if (m == null) {
					continue;
				}
				int amount = m.amount(state);
				if (amount == 0) {
					continue;
				}
				pos.set((chunkPos.x << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z << 4) + (i >> 8));
				if (RealisticWaterBlock.isInfinite(state)) {
					this.addSource(pos, m);
					continue;
				}
				if (!this.owned(pos.asLong(), m)) {
					this.addMaterial(pos.immutable(), 0.0, m.fluid() && amount >= 8 ? 1.0 : amount / 8.0, m);
				}
			}
		}
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (WaterSettings.resolution != this.builtResolution || WaterSettings.sandResolution != this.builtSandResolution) {
			this.builtResolution = WaterSettings.resolution;
			this.builtSandResolution = WaterSettings.sandResolution;
			this.reload(level);
		}
		for (int i = 0; i < 4 && !this.chunkScanQueue.isEmpty(); i++) {
			this.scanChunk(level, this.chunkScanQueue.poll());
		}
		for (Echoes e : this.echoes.values()) {
			e.expire(this.frameCounter);
		}
		Vec3 listener = mc.player != null ? mc.player.getEyePosition() : null;
		for (Object[] f : this.pendingFills) {
			WaterRegion r = (WaterRegion) f[0];
			WaterRegion.Fill fill = (WaterRegion.Fill) f[1];
			if (!this.regions.contains(r)) {
				continue;
			}
			if (listener != null) {
				this.sounds.onFill(r.material, fill, listener);
			}
			if (isHost()) {
				this.syncBlocks(level, r, fill);
			}
		}
		this.pendingFills.clear();
		this.sounds.tick(level);
	}


	private static int targetAmount(float fill, int current) {
		int target;
		if (fill >= 0.8F) {
			target = 8;
		} else if (fill >= 0.12F) {
			target = Mth.clamp(Math.round(fill * 8.0F), 1, 7);
		} else {
			target = 0;
		}
		if (current > 0 && target == 0 && fill > 0.05F) {
			return current;
		}
		if (current == 0 && target > 0 && fill < 0.2F) {
			return 0;
		}
		if (current > 0 && target > 0 && current < 8 && target < 8 && Math.abs(target - current) <= 1) {
			return current;
		}
		return target;
	}

	private static int targetLayers(float fill, float speed, int current) {
		float raw = fill * RealisticSandBlock.MAX_LAYERS;
		if (speed > 1.5F && raw > current) {
			return current;
		}
		int target = raw < 0.5F ? 0 : Mth.clamp(Math.round(raw), 1, RealisticSandBlock.MAX_LAYERS);
		if (current > 0 && target == 0 && raw > 0.25F) {
			return current;
		}
		if (current > 0 && target > 0 && Math.abs(raw - current) < 0.75F) {
			return current;
		}
		return target;
	}

	/** Amount of any material of {@code base}'s group in the block (0 if none) and which one, via {@code out[0]}. */
	private static int amountOfGroup(BlockState state, Material base, Material[] out) {
		Material m = Material.of(state);
		out[0] = m;
		return m != null && m.base() == base ? m.amount(state) : 0;
	}

	private void syncBlocks(ClientLevel level, WaterRegion r, WaterRegion.Fill f) {
		Material base = r.material;
		boolean sand = base.granular();
		// Changes are sent per material; a block that switches material is written as the new one (the server replaces the
		// old material of the same kind) and both block echoes are expected.
		java.util.EnumMap<Material, LongArrayList> positionsBy = new java.util.EnumMap<>(Material.class);
		java.util.EnumMap<Material, ByteArrayList> amountsBy = new java.util.EnumMap<>(Material.class);
		LongOpenHashSet seen = new LongOpenHashSet();
		Material[] found = new Material[1];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					pos.set(f.x() + x, f.y() + y, f.z() + z);
					long key = pos.asLong();
					seen.add(key);
					BlockState state = level.getBlockState(pos);
					int current = amountOfGroup(state, base, found);
					if ((current == 0 && !state.isAir()) || RealisticWaterBlock.isInfinite(state)) {
						continue;
					}
					Material have = current > 0 ? found[0] : null;
					Material want = Material.bySlot(base, f.slot(i));
					// Changes already sent but not echoed back yet win over what the client still shows.
					if (this.echoes(want).expected.containsKey(key)) {
						current = this.echoes(want).expected.get(key);
						have = current > 0 ? want : null;
					} else if (have != null && this.echoes(have).expected.containsKey(key)) {
						current = this.echoes(have).expected.get(key);
						if (current == 0) {
							have = null;
						}
					}
					int target = sand ? targetLayers(f.fullness(i), f.blocksPerSecond(i), current) : targetAmount(f.fullness(i), current);
					if (target > 0 && have != null && have != want && current > 0) {
						// Switches material: keep the amount, write the new material over it.
						positionsBy.computeIfAbsent(want, m -> new LongArrayList()).add(key);
						amountsBy.computeIfAbsent(want, m -> new ByteArrayList()).add((byte) target);
						this.echoes(want).expect(key, target, this.frameCounter);
						this.echoes(have).expect(key, 0, this.frameCounter);
					} else if (target != current) {
						Material m = target == 0 ? have : (have != null ? have : want);
						if (m != null) {
							positionsBy.computeIfAbsent(m, k -> new LongArrayList()).add(key);
							amountsBy.computeIfAbsent(m, k -> new ByteArrayList()).add((byte) target);
							this.echoes(m).expect(key, target, this.frameCounter);
						}
					}
					if (target > 0) {
						r.owned.add(key);
					} else {
						r.owned.remove(key);
					}
				}
			}
		}
		if (base == Material.LAVA) {
			this.sendSolidified(f);
		}
		LongArrayList gone = new LongArrayList();
		for (long key : r.owned) {
			if (!seen.contains(key)) {
				gone.add(key);
			}
		}
		for (long key : gone) {
			r.owned.remove(key);
			this.queueClear(level, key, base, positionsBy, amountsBy);
		}
		for (var e : positionsBy.entrySet()) {
			this.send(e.getKey(), e.getValue(), amountsBy.get(e.getKey()));
		}
	}

	/** Blocks where enough lava turned to stone (react.comp) become cobblestone, or obsidian when most of the block did. */
	private void sendSolidified(WaterRegion.Fill f) {
		LongArrayList positions = new LongArrayList();
		ByteArrayList kinds = new ByteArrayList();
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					float s = f.solidified(i);
					if (s >= WaterSettings.lavaSolidifyBlock) {
						positions.add(BlockPos.asLong(f.x() + x, f.y() + y, f.z() + z));
						kinds.add((byte) (s >= WaterSettings.lavaObsidianFraction ? 1 : 0));
					}
				}
			}
		}
		if (positions.isEmpty() || !ClientPlayNetworking.canSend(SolidifyPayload.TYPE)) {
			return;
		}
		int max = SolidifyPayload.MAX_ENTRIES;
		for (int start = 0; start < positions.size(); start += max) {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] k = new byte[n];
			for (int j = 0; j < n; j++) {
				p[j] = positions.getLong(start + j);
				k[j] = kinds.getByte(start + j);
			}
			ClientPlayNetworking.send(new SolidifyPayload(p, k));
		}
	}

	private void queueClear(ClientLevel level, long key, Material base, java.util.Map<Material, LongArrayList> positionsBy,
		java.util.Map<Material, ByteArrayList> amountsBy) {
		BlockPos pos = BlockPos.of(key);
		BlockState state = level.getBlockState(pos);
		Material m = Material.of(state);
		if (m != null && m.base() == base && m.amount(state) > 0 && !RealisticWaterBlock.isInfinite(state)) {
			positionsBy.computeIfAbsent(m, x -> new LongArrayList()).add(key);
			amountsBy.computeIfAbsent(m, x -> new ByteArrayList()).add((byte) 0);
			this.echoes(m).expect(key, 0, this.frameCounter);
		}
	}

	private void send(Material material, LongArrayList positions, ByteArrayList amounts) {
		if (positions.isEmpty()) {
			return;
		}
		if (!ClientPlayNetworking.canSend(MaterialSyncPayload.TYPE)) {
			return;
		}
		int max = MaterialSyncPayload.MAX_ENTRIES;
		for (int start = 0; start < positions.size(); start += max) {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] a = new byte[n];
			for (int k = 0; k < n; k++) {
				p[k] = positions.getLong(start + k);
				a[k] = amounts.getByte(start + k);
			}
			ClientPlayNetworking.send(new MaterialSyncPayload(material, p, a));
		}
	}


	private boolean renderedThisFrame;

	/** Called at the start of every world frame (GameRenderer.renderLevel). */
	public void beginWorldFrame() {
		this.renderedThisFrame = false;
	}

	/** Fallback after the level is drawn, for when the translucent-terrain hook did not run this frame. */
	public void renderFrameIfMissed() {
		if (!this.renderedThisFrame) {
			this.renderFrame();
		}
	}

	public void renderFrame() {
		if (this.renderedThisFrame) {
			return;
		}
		this.renderedThisFrame = true;
		Minecraft mc = Minecraft.getInstance();
		if (this.regions.isEmpty() || this.failed || mc.level == null || !Gl.ready()) {
			this.lastFrameNanos = 0L;
			return;
		}
		try {
			this.renderFrameUnsafe(mc, mc.level);
		} catch (RuntimeException e) {
			this.failed = true;
			RealisticWater.LOGGER.error("Realistic Water frame failed; disabling the simulation", e);
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("Realistic Water failed: " + e.getMessage()), false);
			}
		}
	}

	private List<BlockPos> sourcesFor(WaterRegion r) {
		List<BlockPos> out = new ArrayList<>();
		if (!r.material.fluid()) {
			return out;
		}
		for (Source s : this.sources) {
			if (s.material().base() == r.material && r.claims(s.pos(), 2)) {
				out.add(s.pos());
			}
		}
		return out;
	}

	/** Springs keep adding their material, so they count towards the region's slots every frame. */
	private int sourceSlotBits(WaterRegion r) {
		int bits = 0;
		for (Source s : this.sourceEntriesFor(r)) {
			int slot = s.material().slot();
			bits |= slot == 0 ? 1 : slot == 1 ? 2 : slot == 2 ? 4 : WaterRegion.SLOT_MIXED_BIT;
		}
		return bits;
	}

	private List<Source> sourceEntriesFor(WaterRegion r) {
		List<Source> out = new ArrayList<>();
		if (!r.material.fluid()) {
			return out;
		}
		for (Source s : this.sources) {
			if (s.material().base() == r.material && r.claims(s.pos(), 2)) {
				out.add(s);
			}
		}
		return out;
	}

	private void renderFrameUnsafe(Minecraft mc, ClientLevel level) {
		long now = System.nanoTime();
		Float clipDt = ClipRecorder.get().fixedDt();
		float frameDt = clipDt != null ? clipDt
			: this.lastFrameNanos == 0L ? 1.0F / 60.0F : (float) Math.min((now - this.lastFrameNanos) / 1.0e9, 0.1);
		this.lastFrameNanos = now;
		this.smoothedFrameMs = this.smoothedFrameMs * 0.9F + frameDt * 1000.0F * 0.1F;
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		double reach = (mc.options.renderDistance().get() + 2) * 16.0;

		List<WaterRegion> active = new ArrayList<>();
		List<WaterRegion> merged = new ArrayList<>();
		List<WaterRegion> drained = new ArrayList<>();
		float fastest = 0.0F;
		float fastestSand = 0.0F;
		for (WaterRegion r : this.regions) {
			WaterRegion.Fill fill = r.readBack();
			if (fill != null) {
				this.pendingFills.add(new Object[]{r, fill});
			}
			int[] box = r.computeBoxWorld(this.sourcesFor(r), this.frameCounter);
			r.boxWorld = box;
			if (box == null) {
				if (++r.idleFrames > 90) {
					drained.add(r);
				}
				continue;
			}
			r.idleFrames = 0;
			double cx = (box[0] + box[3]) * 0.5 / r.res;
			double cz = (box[2] + box[5]) * 0.5 / r.res;
			if (Math.abs(cx - cam.x) > reach || Math.abs(cz - cam.z) > reach) {
				continue;
			}
			active.add(r);
			if (r.material.granular()) {
				fastestSand = Math.max(fastestSand, r.lastMaxSpeed / r.res);
			} else {
				fastest = Math.max(fastest, r.lastMaxSpeed / r.res);
			}
		}

		for (int i = 0; i < active.size(); i++) {
			WaterRegion a = active.get(i);
			for (int j = active.size() - 1; j > i; j--) {
				WaterRegion b = active.get(j);
				if (a.res == b.res && a.material == b.material && overlaps(a.boxWorld, b.boxWorld)) {
					a.boxWorld = WaterRegion.unionBox(a.boxWorld, b.boxWorld);
					a.queueAbsorb(b);
					active.remove(j);
					merged.add(b);
				}
			}
		}
		while (active.size() > MAX_REGIONS) {
			active.removeLast();
		}
		// Fluids step first: they bin the grains of granular regions (as of last frame) before stepping, and granular regions
		// then read this frame's fluid velocities for drag and buoyancy.
		// Water before lava too: lava reacts with the water region's particles of this frame.
		active.sort(java.util.Comparator.comparingInt(r -> r.material.granular() ? 2 : r.material == Material.LAVA ? 1 : 0));

		int steps = 0;
		float stepDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			float speed = fastest + WaterSettings.gravity * 0.15F;
			int res = Math.max(1, WaterSettings.resolution);
			int wanted = Math.max((int) Math.ceil(frameDt * speed * res / WaterSettings.cfl), (int) Math.ceil(frameDt / WaterSettings.maxDt));
			steps = Mth.clamp(wanted, 1, clipDt != null ? WaterSettings.renderMaxStepsPerFrame : WaterSettings.maxStepsPerFrame);
			stepDt = Math.min(frameDt / steps, WaterSettings.maxDt);
		}
		int sandSteps = 0;
		float sandDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			int sandRes = resolutionFor(Material.SAND);
			float limit = Math.min(WaterSettings.sandCfl / (sandRes * sandWaveSpeed()), 1.0F / (sandRes * Math.max(fastestSand, 1e-3F)));
			int wanted = (int) Math.ceil(frameDt / limit);
			sandSteps = Mth.clamp(wanted, 1, clipDt != null ? Integer.MAX_VALUE : Math.max(1, WaterSettings.sandMaxStepsPerFrame));
			sandDt = Math.min(frameDt / sandSteps, limit);
		}
		this.lastSteps = steps;
		this.lastDt = stepDt;
		this.lastSandSteps = sandSteps;
		this.lastSandDt = sandDt;
		this.simTime += steps * stepDt;

		RenderTarget main = mc.getMainRenderTarget();
		GpuTexture colorTexture = main.getColorTexture();
		GpuTexture depthTexture = main.getDepthTexture();
		if (active.isEmpty() || !(colorTexture instanceof GlTexture color) || !(depthTexture instanceof GlTexture depth)) {
			this.retire(level, merged, drained);
			Gl.endFrame();
			this.frameCounter++;
			return;
		}
		if (this.renderer == null) {
			this.renderer = new WaterRenderer();
		}
		if (this.sandRenderer == null) {
			this.sandRenderer = new SandRenderer();
		}
		if (this.uboRing == null) {
			this.uboRing = GlBuf.upload((long) UBO_SLOT * MAX_REGIONS * Gl.FRAME_RING, GlBuf.UNIFORM);
		}
		int width = main.width;
		int height = main.height;
		this.renderer.prepare(width, height);

		Cmd cb = Gl.begin();
		try {
			this.recordFrame(mc, level, cb, active, steps, stepDt, sandSteps, sandDt, width, height, color.glId(), depth.glId());
		} finally {
			Gl.end(cb);
		}
		this.retire(level, merged, drained);
		Gl.endFrame();
		this.frameCounter++;
	}

	private void recordFrame(Minecraft mc, ClientLevel level, Cmd cb, List<WaterRegion> active, int steps, float stepDt, int sandSteps, float sandDt,
		int width, int height, int mainColor, int mainDepth) {
		Gl.barrier(cb);
		for (GlBuf view : this.frameViews.values()) {
			view.fill(cb, 0);
		}
		Gl.barrier(cb);
		boolean readFill = !this.paused;
		// Fluids are drawn one material at a time so each composite uses that material's optics; later groups see earlier
		// ones as part of the scene (milk behind water refracts through the water).
		java.util.EnumMap<Material, List<WaterRenderer.Body>> fluidGroups = new java.util.EnumMap<>(Material.class);
		List<WaterRenderer.Body> sandBodies = new ArrayList<>();
		for (int i = 0; i < active.size(); i++) {
			WaterRegion r = active.get(i);
			r.planWindow(level, this.solids(r.material, r.res), r.boxWorld);
			boolean sand = r.material.granular();
			this.pairCoupling(r, active);
			if (sand && r.waterPartner != null && r.waterPartner.lastMaxSpeed / r.waterPartner.res > 0.3F) {
				r.wake(this.frameCounter);
			}
			if (sand && entityNear(level, r)) {
				r.wake(this.frameCounter);
			}
			int regionSteps = !sand ? steps : r.dormant() ? 0 : sandSteps;
			if (readFill && r.initialized() && (this.frameCounter + r.id) % SYNC_INTERVAL == 0) {
				r.fillRequested = true;
			}
			int slot = Gl.ringSlot() * MAX_REGIONS + i;
			List<float[]> removals = r.takeRemovals();
			int numSources = this.writeGlobals(mc, level, r, slot, sand ? sandDt : stepDt, width, height, removals);
			Desc ubo = Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE);
			r.frameUbo = ubo;
			if (!sand && !r.grainPartners.isEmpty()) {
				r.recordCoupling(cb, ubo);
			}
			r.record(cb, ubo, regionSteps, numSources, this.frameCounter, sand ? null : this.frameView(r.material), removals.size());
			WaterRenderer.Body body = new WaterRenderer.Body(r, ubo);
			if (sand) {
				sandBodies.add(body);
			} else {
				fluidGroups.computeIfAbsent(r.material, m -> new ArrayList<>()).add(body); // all fluids share Material.WATER regions
			}
		}
		Gl.barrier(cb);
		if (!sandBodies.isEmpty()) {
			this.sandRenderer.record(cb, sandBodies, width, height, mainColor, mainDepth);
			Gl.barrier(cb);
		}
		for (var group : fluidGroups.entrySet()) {
			this.renderer.record(cb, group.getValue(), Desc.ssbo(this.frameView(group.getKey())), mainColor, mainDepth);
			Gl.barrier(cb);
		}
	}

	/** Fluid regions get the overlapping granular regions, granular regions the first overlapping (already stepped) fluid region. */
	private void pairCoupling(WaterRegion r, List<WaterRegion> active) {
		r.grainPartners.clear();
		r.waterPartner = null;
		r.reactPartner = null;
		if (r.material == Material.LAVA) {
			for (WaterRegion o : active) {
				if (o.material == Material.WATER && o.initialized() && r.windowOverlaps(o)) {
					r.reactPartner = o;
					break;
				}
			}
		}
		if (!WaterSettings.coupling) {
			return;
		}
		for (WaterRegion o : active) {
			if (o == r || o.material.kind == r.material.kind || !r.windowOverlaps(o)) {
				continue;
			}
			if (r.material.fluid()) {
				r.grainPartners.add(o);
			} else if (r.waterPartner == null && o.initialized()) {
				r.waterPartner = o;
			}
		}
	}

	private GlBuf frameView(Material material) {
		return this.frameViews.computeIfAbsent(material, m -> GlBuf.device(16, 0));
	}

	private void retire(ClientLevel level, List<WaterRegion> merged, List<WaterRegion> drained) {
		for (WaterRegion r : merged) {
			this.regions.remove(r);
			r.close();
		}
		for (WaterRegion r : drained) {
			this.regions.remove(r);
			if (isHost() && !this.paused) {
				java.util.EnumMap<Material, LongArrayList> positionsBy = new java.util.EnumMap<>(Material.class);
				java.util.EnumMap<Material, ByteArrayList> amountsBy = new java.util.EnumMap<>(Material.class);
				for (long key : r.owned) {
					this.queueClear(level, key, r.material, positionsBy, amountsBy);
				}
				for (var e : positionsBy.entrySet()) {
					this.send(e.getKey(), e.getValue(), amountsBy.get(e.getKey()));
				}
			}
			r.close();
		}
	}

	private static boolean entityNear(ClientLevel level, WaterRegion r) {
		int[] b = r.boxWorld;
		if (b == null) {
			return false;
		}
		double h = 1.0 / r.res;
		AABB box = new AABB(b[0] * h, b[1] * h, b[2] * h, b[3] * h, b[4] * h, b[5] * h).inflate(0.5);
		return !level.getEntities((Entity) null, box, e -> !e.isSpectator()).isEmpty() || !movingPistons(level, box.inflate(1.0)).isEmpty();
	}

	private static boolean overlaps(int[] a, int[] b) {
		return a[0] < b[3] && b[0] < a[3] && a[1] < b[4] && b[1] < a[4] && a[2] < b[5] && b[2] < a[5];
	}

	private int writeGlobals(Minecraft mc, ClientLevel level, WaterRegion r, int slot, float dt, int width, int height, List<float[]> removals) {
		ByteBuffer b = this.uboRing.mapped();
		int o = slot * UBO_SLOT;
		Camera camera = mc.gameRenderer.getMainCamera();
		float partialTick = camera.getPartialTickTime();
		int res = r.res;
		float h = 1.0F / res;
		int[] box = r.box;
		boolean sand = r.material.granular();
		Material m = r.material;

		putInts(b, o, r.nx, r.ny, r.nz, r.numCells);
		putInts(b, o + 16, r.numU, r.numV, r.numW, r.numFaces);
		int entityCount = this.writeEntities(mc, r, b, o + 576, partialTick);
		putInts(b, o + 32, r.fluidCapacity, r.wwCapacity, entityCount, this.frameCounter);
		int sourceCount = sand ? 0 : this.writeSources(r, b, o + 2112);
		putInts(b, o + 48, !sand && m != Material.LAVA && WaterSettings.whitewater ? 1 : 0, sourceCount, !sand && this.localPlayerFirst ? 1 : 0, WaterSettings.wwCellLimit);
		putFloats(b, o + 64, 0.0F, -WaterSettings.gravity * res, 0.0F, dt);
		float flip = (float) Math.pow(WaterSettings.flipRatio, dt * 60.0F);
		putFloats(b, o + 80, flip, PARTICLES_PER_CELL, WaterSettings.volumeCorrection, 0.0F);
		putFloats(b, o + 96, WaterSettings.maxSpeed * res, h, this.simTime, 0.0F);
		putFloats(b, o + 112, WaterSettings.wwTrappedAirRate, WaterSettings.wwWaveCrestRate, WaterSettings.wwEnergyMin, WaterSettings.wwEnergyMax);
		putFloats(b, o + 128, WaterSettings.wwTrappedAirMin, WaterSettings.wwTrappedAirMax, WaterSettings.wwWaveCrestMin, WaterSettings.wwWaveCrestMax);
		putFloats(b, o + 144, WaterSettings.wwFoamLifeMin, WaterSettings.wwFoamLifeMax, WaterSettings.wwBubbleBuoyancy, WaterSettings.wwBubbleDrag);
		putFloats(b, o + 160, WaterSettings.wwSprayDrag, WaterSettings.wwSprayThreshold, WaterSettings.wwBubbleThreshold, WaterSettings.wwMaxPerParticle);

		Matrix4f proj = new Matrix4f(LEVEL_PROJECTION);
		Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
		proj.get(o + 176, b);
		view.get(o + 240, b);
		new Matrix4f(proj).invert().get(o + 304, b);
		new Matrix4f(view).invert().get(o + 368, b);

		Vec3 cam = camera.getPosition();
		putFloats(b, o + 432, (float) (r.ox - cam.x), (float) (r.oy - cam.y), (float) (r.oz - cam.z), h);
		putFloats(b, o + 448, width, height, 1.0F / width, 1.0F / height);

		float sunAngle = level.getSunAngle(partialTick);
		Vector4f sunWorld = new Vector4f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0F, 0.0F);
		Vector4f sunView = view.transform(new Vector4f(sunWorld));
		float sunIntensity = Mth.clamp(sunWorld.y * 4.0F, 0.0F, 1.0F) * (1.0F - level.getRainLevel(partialTick) * 0.8F);
		putFloats(b, o + 464, sunView.x, sunView.y, sunView.z, sunIntensity);

		int sky = level.getSkyColor(cam, partialTick);
		Vector3f skyColor = new Vector3f((sky >> 16 & 0xFF) / 255.0F, (sky >> 8 & 0xFF) / 255.0F, (sky & 0xFF) / 255.0F);
		Vector4f fog = FOG_COLOR;
		float ambient = Mth.clamp(0.15F + 1.2F * (0.3F * skyColor.x + 0.59F * skyColor.y + 0.11F * skyColor.z), 0.12F, 1.0F);
		putFloats(b, o + 480, skyColor.x, skyColor.y, skyColor.z, ambient);
		putFloats(b, o + 496, fog.x, fog.y, fog.z, 1.0F);
		putFloats(b, o + 512, WaterSettings.particleRadius * h, WaterSettings.thicknessScale, WaterSettings.refraction,
			sand ? WaterSettings.sandSurfaceSmoothing : WaterSettings.surfaceSmoothing);
		putFloats(b, o + 528, WaterSettings.absorbR, WaterSettings.absorbG, WaterSettings.absorbB, 0.0F);
		putFloats(b, o + 544, WaterSettings.scatterR, WaterSettings.scatterG, WaterSettings.scatterB, WaterSettings.scatterDensity);
		putFloats(b, o + 560, WaterSettings.wwRadius, WaterSettings.foamCoverage, WaterSettings.bubbleAlpha, WaterSettings.sprayAlpha);
		putFloats(b, o + 3136, (float) (cam.x - r.ox) * res, (float) (cam.y - r.oy) * res, (float) (cam.z - r.oz) * res, res);
		putFloats(b, o + 3152, WaterSettings.underwaterFog, wrap(cam.x), wrap(cam.y), wrap(cam.z));

		SurfaceMesher mesher = r.mesher;
		int sub = mesher.subdivision;
		putInts(b, o + 3168, mesher.fx, mesher.fy, mesher.fz, sub);
		putFloats(b, o + 3184, sand ? WaterSettings.sandSurfaceIso : WaterSettings.surfaceIso, 8.0F / (sub * sub * sub), WaterSettings.nearCull,
			WaterSettings.dropletThreshold);
		putInts(b, o + 3200, box[0], box[1], box[2], WaterRegion.boxCells(box));
		putInts(b, o + 3216, box[3], box[4], box[5], WaterRegion.boxFaces(box));
		int[] fine = mesher.fineBox(box);
		putInts(b, o + 3232, fine[0], fine[1], fine[2], SurfaceMesher.voxels(fine));
		putInts(b, o + 3248, fine[3], fine[4], fine[5], SurfaceMesher.cubes(fine));
		putInts(b, o + 3264, removals.size(), 0, 0, 0);
		for (int k = 0; k < removals.size(); k++) {
			float[] rm = removals.get(k);
			putFloats(b, o + 3280 + k * 32, rm[0], rm[1], rm[2], rm[6]);
			putFloats(b, o + 3296 + k * 32, rm[3], rm[4], rm[5], 0.0F);
		}
		float grainsPerCell = WaterSettings.couplingSolidFraction * PARTICLES_PER_CELL
			* (float) Math.pow((double) resolutionFor(Material.SAND) / res, 3.0);
		putFloats(b, o + 3792, (float) (level.getMinY() - r.oy) * res - 8.0F, WaterRegion.KILL_MARGIN, Math.max(1.0F, grainsPerCell),
			!sand && !r.grainPartners.isEmpty() ? 1.0F : 0.0F);
		putFloats(b, o + 3808, WaterSettings.detailStrength, WaterSettings.detailScale, WaterSettings.detailSpeed, WaterSettings.roughness);
		putFloats(b, o + 3824, WaterSettings.specular, WaterSettings.wetDarkening, WaterSettings.filmShading, WaterSettings.foamBubbleSize);

		if (sand) {
			float e = PARTICLES_PER_CELL * WaterSettings.sandStiffness * res * res;
			float mu = e / (2.0F * (1.0F + SAND_POISSON));
			float lambda = e * SAND_POISSON / ((1.0F + SAND_POISSON) * (1.0F - 2.0F * SAND_POISSON));
			int maxGrains = 1;
			for (Material g : Material.VALUES) {
				if (g.granular()) {
					maxGrains = Math.max(maxGrains, grainsPerParticle(g));
				}
			}
			putFloats(b, o + 3840, r.material.shaderId(), 0.0F, 0.0F, 1.0F);
			putFloats(b, o + 3856, mu, lambda, maxGrains, WaterSettings.sandGrainSize);
			float impactViscosity = WaterSettings.sandImpactDamping * 0.25F / Math.max(dt, 1e-5F);
			putFloats(b, o + 3872, WaterSettings.sandWallFriction, 1.0F / PARTICLES_PER_CELL, impactViscosity, WaterSettings.sandImpactRate);
		} else {
			putFloats(b, o + 3840, r.material.shaderId(), 0.0F, 0.0F, 1.0F);
			putFloats(b, o + 3856, WaterSettings.filmFriction, WaterSettings.strandedTime, WaterSettings.strandedNeighbors, WaterSettings.filmDepth * res);
			putFloats(b, o + 3872, 0.0F, 0.0F, 0.0F, 0.0F);
		}
		putFloats(b, o + 3888, WaterSettings.sandGrainMinPixels, WaterSettings.sandBrightness, WaterSettings.sandGrainSpread, 0.0F);
		float impactLogJ = (float) Math.log(Math.max(1.0F - WaterSettings.sandImpactCompression, 0.01F));
		putFloats(b, o + 3904, WaterSettings.sandRestSpeed * res, WaterSettings.sandRestTime, WaterSettings.sandWakeAccel * WaterSettings.gravity * res * dt,
			impactLogJ);
		r.frameUniformSlot = r.uniformSlot(this.frameCounter, this.sourceSlotBits(r));
		this.writeMaterialTable(b, o + 3920, dt, r.frameUniformSlot, r.material.fluid() ? r.material : Material.SAND);
		this.uboRing.flush(o, UBO_SIZE);
		return sourceCount;
	}

	/** fluidA/B/C[4], grainA/B[4], mixInfo: what each material slot is like (see common.glsl). */
	private void writeMaterialTable(ByteBuffer b, int o, float dt, int uniformSlot, Material fluidBase) {
		for (int s = 0; s < 4; s++) {
			Material f = Material.bySlot(fluidBase, s);
			float flip = (float) Math.pow(f == Material.SUSPICIOUS_MILK ? WaterSettings.suspiciousFlipRatio : WaterSettings.flipRatio, dt * 60.0F);
			switch (f) {
				case MILK -> {
					putFloats(b, o + s * 16, WaterSettings.milkAbsorbR, WaterSettings.milkAbsorbG, WaterSettings.milkAbsorbB, flip);
					putFloats(b, o + 64 + s * 16, WaterSettings.milkScatterR, WaterSettings.milkScatterG, WaterSettings.milkScatterB,
						WaterSettings.milkScatterDensity);
				}
				case LAVA -> {
					float lavaFlip = (float) Math.pow(WaterSettings.lavaFlipRatio, dt * 60.0F);
					float a = WaterSettings.lavaAbsorb;
					putFloats(b, o + s * 16, a, a, a, lavaFlip);
					putFloats(b, o + 64 + s * 16, WaterSettings.lavaScatterR, WaterSettings.lavaScatterG, WaterSettings.lavaScatterB,
						WaterSettings.lavaScatterDensity);
				}
				case SUSPICIOUS_MILK -> {
					putFloats(b, o + s * 16, WaterSettings.suspiciousAbsorbR, WaterSettings.suspiciousAbsorbG, WaterSettings.suspiciousAbsorbB, flip);
					putFloats(b, o + 64 + s * 16, WaterSettings.suspiciousScatterR, WaterSettings.suspiciousScatterG, WaterSettings.suspiciousScatterB,
						WaterSettings.suspiciousScatterDensity);
				}
				default -> {
					putFloats(b, o + s * 16, WaterSettings.absorbR, WaterSettings.absorbG, WaterSettings.absorbB, flip);
					putFloats(b, o + 64 + s * 16, WaterSettings.scatterR, WaterSettings.scatterG, WaterSettings.scatterB, WaterSettings.scatterDensity);
				}
			}
			boolean sus = f == Material.SUSPICIOUS_MILK;
			boolean lava = f == Material.LAVA;
			// x film friction, y stranded time, z glow (emission), w crust coverage
			putFloats(b, o + 128 + s * 16, lava ? WaterSettings.lavaFilmFriction : sus ? WaterSettings.suspiciousFilmFriction : WaterSettings.filmFriction,
				lava ? WaterSettings.lavaStrandedTime : sus ? WaterSettings.suspiciousStrandedTime : WaterSettings.strandedTime,
				lava ? WaterSettings.lavaEmission : 0.0F, lava ? WaterSettings.lavaViscosity : 0.0F);

		}
		for (int s = 0; s < Material.GRAIN_SLOTS; s++) {
			GrainParams g = grain(Material.bySlot(Material.SAND, s));
			double phi = Math.toRadians(g.frictionAngle());
			float alpha = (float) (Math.sqrt(2.0 / 3.0) * 2.0 * Math.sin(phi) / (3.0 - Math.sin(phi)));
			putFloats(b, o + 192 + s * 16, alpha, g.cohesion(), g.grainSize(), g.brightness());
			putFloats(b, o + 320 + s * 16, g.waterDrag(), g.grainsPerParticle(), g.tint(), 0.0F);
			putFloats(b, o + 448 + s * 16, g.toughness(), g.heal(), 0.0F, 0.0F);
		}
		putFloats(b, o + 576, WaterSettings.fluidMixRate, uniformSlot, fluidBase == Material.LAVA ? 1.0F : 0.0F, WaterSettings.lavaCrust);
	}

	/** What a granular material is like (all from WaterSettings, so /water set tunes them live). */
	private record GrainParams(float frictionAngle, float cohesion, float grainSize, int grainsPerParticle, float brightness, float waterDrag,
		float toughness, float heal, int tint) {
	}

	private static GrainParams grain(Material m) {
		return switch (m) {
			case RED_SAND -> new GrainParams(WaterSettings.redSandFrictionAngle, WaterSettings.redSandCohesion, WaterSettings.sandGrainSize,
				WaterSettings.sandGrainsPerParticle, WaterSettings.sandBrightness, WaterSettings.sandWaterDrag, 0.0F, 0.0F, 0xFFFFFF);
			case GRAVEL -> new GrainParams(WaterSettings.gravelFrictionAngle, WaterSettings.gravelCohesion, WaterSettings.gravelGrainSize,
				WaterSettings.gravelGrainsPerParticle, WaterSettings.gravelBrightness, WaterSettings.gravelWaterDrag, 0.0F, 0.0F, 0xFFFFFF);
			case SNOW -> new GrainParams(WaterSettings.snowFrictionAngle, WaterSettings.snowCohesion, WaterSettings.snowGrainSize,
				WaterSettings.snowGrainsPerParticle, WaterSettings.snowBrightness, WaterSettings.snowWaterDrag, WaterSettings.snowToughness,
				WaterSettings.snowHeal, 0xFFFFFF);
			case SANDSTONE -> new GrainParams(WaterSettings.sandstoneFrictionAngle, WaterSettings.sandstoneCohesion, WaterSettings.sandstoneGrainSize,
				WaterSettings.sandstoneGrainsPerParticle, WaterSettings.sandBrightness, WaterSettings.sandstoneWaterDrag, WaterSettings.sandstoneToughness,
				0.0F, 0xFFFFFF);
			case DIRT -> new GrainParams(WaterSettings.dirtFrictionAngle, WaterSettings.dirtCohesion, WaterSettings.dirtGrainSize,
				WaterSettings.dirtGrainsPerParticle, 1.0F, WaterSettings.dirtWaterDrag, WaterSettings.dirtToughness, 0.0F, 0xFFFFFF);
			case CLAY -> new GrainParams(WaterSettings.clayFrictionAngle, WaterSettings.clayCohesion, WaterSettings.clayGrainSize,
				WaterSettings.clayGrainsPerParticle, 1.0F, WaterSettings.clayWaterDrag, WaterSettings.clayToughness, WaterSettings.clayHeal, 0xFFFFFF);
			case SUSPICIOUS_CURD -> new GrainParams(WaterSettings.curdFrictionAngle, WaterSettings.curdCohesion, WaterSettings.curdGrainSize,
				WaterSettings.curdGrainsPerParticle, WaterSettings.curdBrightness, WaterSettings.curdWaterDrag, WaterSettings.curdToughness,
				WaterSettings.curdHeal, 0xFFF3DA);
			default -> new GrainParams(WaterSettings.sandFrictionAngle, WaterSettings.sandCohesion, WaterSettings.sandGrainSize,
				WaterSettings.sandGrainsPerParticle, WaterSettings.sandBrightness, WaterSettings.sandWaterDrag, 0.0F, 0.0F, 0xFFFFFF);
		};
	}

	private static int grainsPerParticle(Material m) {
		return Math.max(1, grain(m).grainsPerParticle());
	}

	private static float wrap(double v) {
		return (float) (v - Math.floor(v / WORLD_WRAP) * WORLD_WRAP);
	}

	private int writeSources(WaterRegion r, ByteBuffer b, int o) {
		int n = 0;
		for (Source s : this.sourceEntriesFor(r)) {
			BlockPos p = s.pos();
			if (n >= MAX_SOURCES || !r.windowContains(p, 0)) {
				continue;
			}
			// w = material slot * 4 + fill height (inflow.comp splits them again)
			putFloats(b, o + n * 16, (p.getX() - r.ox) * r.res, (p.getY() - r.oy) * r.res, (p.getZ() - r.oz) * r.res,
				s.material().slot() * 4.0F + Math.min(WaterSettings.sourceFill, 1.0F));
			n++;
		}
		return n;
	}

	private int writeEntities(Minecraft mc, WaterRegion r, ByteBuffer b, int o, float partialTick) {
		AABB window = new AABB(r.ox, r.oy, r.oz, r.ox + r.sx, r.oy + r.sy, r.oz + r.sz).inflate(1.0);
		List<Entity> entities = new ArrayList<>(mc.level.getEntities((Entity) null, window));
		this.localPlayerFirst = mc.player != null && entities.remove(mc.player);
		if (this.localPlayerFirst) {
			entities.addFirst(mc.player);
		}
		int n = 0;
		float res = r.res;
		for (Entity e : entities) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			if (e.isSpectator()) {
				continue;
			}
			double ix = Mth.lerp(partialTick, e.xo, e.getX());
			double iy = Mth.lerp(partialTick, e.yo, e.getY());
			double iz = Mth.lerp(partialTick, e.zo, e.getZ());
			AABB box = e.getBoundingBox().move(ix - e.getX(), iy - e.getY(), iz - e.getZ());
			int base = o + n * 48;
			putFloats(b, base, (float) (box.minX - r.ox) * res, (float) (box.minY - r.oy) * res, (float) (box.minZ - r.oz) * res, 0.0F);
			putFloats(b, base + 16, (float) (box.maxX - r.ox) * res, (float) (box.maxY - r.oy) * res, (float) (box.maxZ - r.oz) * res, 0.0F);
			putFloats(b, base + 32, (float) (e.getX() - e.xo) * 20.0F * res, (float) (e.getY() - e.yo) * 20.0F * res, (float) (e.getZ() - e.zo) * 20.0F * res, 0.0F);
			n++;
		}
		for (PistonMovingBlockEntity piston : movingPistons(mc.level, window)) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			BlockPos p = piston.getBlockPos();
			AABB box = new AABB(p).move(piston.getXOff(partialTick), piston.getYOff(partialTick), piston.getZOff(partialTick));
			Direction d = piston.getMovementDirection();
			float speed = piston.getProgress(partialTick) < 1.0F ? 10.0F * res : 0.0F;
			int base = o + n * 48;
			putFloats(b, base, (float) (box.minX - r.ox) * res, (float) (box.minY - r.oy) * res, (float) (box.minZ - r.oz) * res, 0.0F);
			putFloats(b, base + 16, (float) (box.maxX - r.ox) * res, (float) (box.maxY - r.oy) * res, (float) (box.maxZ - r.oz) * res, 0.0F);
			putFloats(b, base + 32, d.getStepX() * speed, d.getStepY() * speed, d.getStepZ() * speed, 0.0F);
			n++;
		}
		return n;
	}

	private static List<PistonMovingBlockEntity> movingPistons(ClientLevel level, AABB area) {
		List<PistonMovingBlockEntity> out = new ArrayList<>();
		for (int cx = SectionPos.blockToSectionCoord(area.minX); cx <= SectionPos.blockToSectionCoord(area.maxX); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(area.minZ); cz <= SectionPos.blockToSectionCoord(area.maxZ); cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be instanceof PistonMovingBlockEntity piston && area.contains(Vec3.atCenterOf(piston.getBlockPos()))) {
						out.add(piston);
					}
				}
			}
		}
		return out;
	}

	private static void putInts(ByteBuffer b, int o, int x, int y, int z, int w) {
		b.putInt(o, x).putInt(o + 4, y).putInt(o + 8, z).putInt(o + 12, w);
	}

	private static void putFloats(ByteBuffer b, int o, float x, float y, float z, float w) {
		b.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
	}


	public String statsLine() {
		if (this.regions.isEmpty()) {
			return "Nothing simulated right now (place a realistic bucket or block, /water spawn <material>, or use /fill)";
		}
		int fluid = 0;
		int sand = 0;
		int ww = 0;
		int bodies = 0;
		int piles = 0;
		long cells = 0;
		long windowCells = 0;
		for (WaterRegion r : this.regions) {
			if (r.material.granular()) {
				sand += r.lastFluidCount;
				piles++;
			} else {
				fluid += r.lastFluidCount;
				bodies++;
			}
			ww += r.lastWhitewaterCount;
			if (r.box != null && r.boxWorld != null) {
				cells += WaterRegion.boxCells(r.box);
			}
			windowCells += r.numCells;
		}
		return String.format("%d fluid bodies  fluid %,d  whitewater %,d  |  %d granular bodies  grains %,d  |  active %,d cells of %,d allocated  |  "
				+ "frame %.1f ms  water steps %d x %.1f ms  sand steps %d x %.2f ms  sources %d  %s",
			bodies, fluid, ww, piles, sand, cells, windowCells, this.smoothedFrameMs, this.lastSteps, this.lastDt * 1000.0F, this.lastSandSteps,
			this.lastSandDt * 1000.0F, this.sources.size(), isHost() ? "(syncing blocks)" : "(not host: blocks follow the server)")
			+ (Gl.deviceInfo().isEmpty() ? "" : "  |  " + Gl.deviceInfo());
	}

	public int framesRendered() {
		return this.frameCounter;
	}

	public boolean failed() {
		return this.failed;
	}

	public void clear() {
		this.closeRegions();
		this.sources.clear();
		this.chunkScanQueue.clear();
		this.failed = false;
	}

	public void shutdown() {
		ClipRecorder.get().shutdown();
		this.clear();
		if (this.renderer != null || this.sandRenderer != null) {
			Gl.waitIdle();
		}
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		if (this.sandRenderer != null) {
			this.sandRenderer.close();
			this.sandRenderer = null;
		}
		if (this.uboRing != null) {
			this.uboRing.close();
			this.uboRing = null;
			this.frameViews.values().forEach(GlBuf::close);
			this.frameViews.clear();
		}
		WaterRegion.closePrograms();
		Gl.flushDeferredNow();
		Gl.releaseShared();
	}
}
