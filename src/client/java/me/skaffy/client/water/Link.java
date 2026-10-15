package me.skaffy.client.water;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.skaffy.Material;
import me.skaffy.client.link.LinkNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Simulation link: players running the mod share what they do to their simulations over a separate connection (LinkNet), so
 * on any server they see each other's material. Only events travel (a cube dropped, the hose, springs, removals, the grab
 * tool), not particles: every client simulates them itself, so splashes differ in detail but the picture is the same.
 *
 * <p>Everything a peer sends is checked here before it touches the simulation (materials, sizes, distance, a volume budget
 * per peer, a spring cap), so a misbehaving peer can't flood anyone's game.
 */
public final class Link {
	private static final byte SPAWN = 1;
	private static final byte CARRIER = 2;
	private static final byte CARRIER_END = 3;
	private static final byte SPRING = 4;
	private static final byte SPRINGS_CLEAR = 5;
	private static final byte REMOVE = 6;

	/** Largest cube edge a peer may drop at once, in blocks. */
	private static final float MAX_EDGE = 16.0F;
	/** Volume (blocks^3) a peer may add per second, and in one burst. */
	private static final double VOLUME_RATE = 1500.0;
	private static final double VOLUME_BURST = MAX_EDGE * MAX_EDGE * MAX_EDGE + 100.0;
	/** Events further than this from you are ignored (you wouldn't see them anyway). */
	private static final double MAX_DISTANCE = 192.0;
	private static final int MAX_SPRINGS_PER_PEER = 64;
	private static final float MAX_SPEED = 80.0F;
	private static final float MAX_CARRIER_RADIUS = 4.0F;
	private static final long CARRIER_TIMEOUT_NANOS = 300_000_000L;

	private record Incoming(int sender, String name, byte[] message) {
	}

	private static final class PeerState {
		String name = "?";
		double volume = VOLUME_BURST;
		long lastRefill = System.nanoTime();
		final List<BlockPos> springs = new ArrayList<>();
		@Nullable Carriers.Carrier carrier;
		long carrierNanos;
		boolean warned;
	}

	private static @Nullable LinkNet net;
	private static final ConcurrentLinkedQueue<Incoming> inbox = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<>();
	private static final Map<Integer, PeerState> peers = new HashMap<>();
	private static boolean grabSent;

	private Link() {
	}

	// ---- Control (commands) ----

	public static String host(int port, String password) {
		stop();
		try {
			net = LinkNet.host(port, password, playerName(), listener());
			return "Hosting the simulation link on port " + port + ". Forward it (e.g. playit, TCP) and send friends the address and password";
		} catch (IOException e) {
			net = null;
			return "Couldn't host on port " + port + ": " + e.getMessage();
		}
	}

	public static String join(String address, String password) {
		stop();
		String host = address;
		int port = LinkNet.DEFAULT_PORT;
		int colon = address.lastIndexOf(':');
		if (colon > 0) {
			try {
				port = Integer.parseInt(address.substring(colon + 1));
				host = address.substring(0, colon);
			} catch (NumberFormatException e) {
				return "Bad address " + address;
			}
		}
		net = LinkNet.join(host, port, password, playerName(), listener());
		return "Connecting to " + host + ":" + port + "...";
	}

	public static String stop() {
		boolean was = net != null;
		if (net != null) {
			net.close();
			net = null;
		}
		peers.clear();
		inbox.clear();
		return was ? "Simulation link stopped" : "No simulation link running";
	}

	public static String status() {
		if (net == null || net.isClosed()) {
			return "Simulation link: off";
		}
		if (net.isHost()) {
			List<String> names = net.peerNames();
			return "Simulation link: hosting, " + names.size() + " joined" + (names.isEmpty() ? "" : " (" + String.join(", ", names) + ")");
		}
		return "Simulation link: " + (net.isUp() ? "connected" : "connecting...");
	}

	private static LinkNet.Listener listener() {
		return new LinkNet.Listener() {
			@Override
			public void onMessage(int sender, String senderName, byte[] message) {
				if (inbox.size() < 4096) {
					inbox.add(new Incoming(sender, senderName, message));
				}
			}

			@Override
			public void onEvent(String text) {
				notices.add(text);
			}
		};
	}

	private static boolean active() {
		return net != null && net.isUp();
	}

	private static String playerName() {
		return Minecraft.getInstance().getUser().getName();
	}

	private static String dimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.dimension().location().toString() : "";
	}

	// ---- Outgoing (called where the local player does something) ----

	static void spawn(WaterRegion.Emitter e, Material material) {
		if (!active()) {
			return;
		}
		send(out -> {
			out.writeByte(SPAWN);
			out.writeUTF(dimension());
			out.writeByte(material.networkId());
			out.writeDouble(e.minX());
			out.writeDouble(e.minY());
			out.writeDouble(e.minZ());
			out.writeFloat((float) (e.maxX() - e.minX()));
			out.writeFloat((float) (e.maxY() - e.minY()));
			out.writeFloat((float) (e.maxZ() - e.minZ()));
			out.writeFloat(e.vx());
			out.writeFloat(e.vy());
			out.writeFloat(e.vz());
		});
	}

	static void spring(BlockPos pos, Material material, boolean add) {
		if (!active()) {
			return;
		}
		send(out -> {
			out.writeByte(SPRING);
			out.writeUTF(dimension());
			out.writeInt(pos.getX());
			out.writeInt(pos.getY());
			out.writeInt(pos.getZ());
			out.writeByte(material.networkId());
			out.writeBoolean(add);
		});
	}

	static void springsClear() {
		if (active()) {
			send(out -> {
				out.writeByte(SPRINGS_CLEAR);
				out.writeUTF(dimension());
			});
		}
	}

	static void remove(Material material) {
		if (active()) {
			send(out -> {
				out.writeByte(REMOVE);
				out.writeByte(material.networkId());
			});
		}
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	private static void send(Writer writer) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
			writer.write(new DataOutputStream(bytes));
			net.send(bytes.toByteArray());
		} catch (IOException ignored) {
		}
	}

	// ---- Per tick: grab tool out, everything in ----

	static void tick(Minecraft mc, WaterWorld world) {
		String notice;
		while ((notice = notices.poll()) != null) {
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(notice), false);
			}
		}
		if (net != null && net.isClosed()) {
			net = null;
			peers.clear();
		}
		if (active()) {
			if (Grabber.active()) {
				Vec3 c = Grabber.center();
				Vec3 v = Grabber.velocity();
				send(out -> {
					out.writeByte(CARRIER);
					out.writeUTF(dimension());
					out.writeDouble(c.x);
					out.writeDouble(c.y);
					out.writeDouble(c.z);
					out.writeFloat((float) v.x);
					out.writeFloat((float) v.y);
					out.writeFloat((float) v.z);
					out.writeFloat(Grabber.radius());
				});
				grabSent = true;
			} else if (grabSent) {
				send(out -> out.writeByte(CARRIER_END));
				grabSent = false;
			}
		}
		Incoming in;
		for (int n = 0; n < 256 && (in = inbox.poll()) != null; n++) {
			try {
				apply(mc, world, in);
			} catch (IOException | RuntimeException e) {
				// malformed message: ignore it
			}
		}
	}

	/** Grab tools of other players, moved on along their last known velocity. */
	static void addRemoteCarriers(List<Carriers.Carrier> out) {
		long now = System.nanoTime();
		for (PeerState p : peers.values()) {
			Carriers.Carrier c = p.carrier;
			if (c == null) {
				continue;
			}
			long age = now - p.carrierNanos;
			if (age > CARRIER_TIMEOUT_NANOS) {
				p.carrier = null;
				continue;
			}
			out.add(new Carriers.Carrier(c.center().add(c.velocity().scale(age * 1e-9)), c.velocity(), c.radius()));
		}
	}

	private static void apply(Minecraft mc, WaterWorld world, Incoming in) throws IOException {
		if (mc.player == null || mc.level == null) {
			return;
		}
		PeerState peer = peers.computeIfAbsent(in.sender, k -> new PeerState());
		peer.name = in.name;
		DataInputStream data = new DataInputStream(new ByteArrayInputStream(in.message));
		byte type = data.readByte();
		switch (type) {
			case SPAWN -> {
				if (!data.readUTF().equals(dimension())) {
					return;
				}
				Material m = Material.byNetworkId(data.readByte());
				double x = data.readDouble();
				double y = data.readDouble();
				double z = data.readDouble();
				float sx = data.readFloat();
				float sy = data.readFloat();
				float sz = data.readFloat();
				float vx = data.readFloat();
				float vy = data.readFloat();
				float vz = data.readFloat();
				if (m == null || !finite(x, y, z, sx, sy, sz, vx, vy, vz) || sx <= 0 || sy <= 0 || sz <= 0
					|| sx > MAX_EDGE || sy > MAX_EDGE || sz > MAX_EDGE || !near(mc, x, y, z)) {
					return;
				}
				double volume = (double) sx * sy * sz;
				if (!budget(peer, volume)) {
					if (!peer.warned) {
						peer.warned = true;
						mc.player.displayClientMessage(Component.literal("Link: " + peer.name + " is spawning too much, skipping some of it"), false);
					}
					return;
				}
				world.addEmitter(new WaterRegion.Emitter(x, y, z, x + sx, y + sy, z + sz, clampSpeed(vx), clampSpeed(vy), clampSpeed(vz)), m);
			}
			case CARRIER -> {
				if (!data.readUTF().equals(dimension())) {
					return;
				}
				double x = data.readDouble();
				double y = data.readDouble();
				double z = data.readDouble();
				float vx = data.readFloat();
				float vy = data.readFloat();
				float vz = data.readFloat();
				float r = data.readFloat();
				if (!finite(x, y, z, vx, vy, vz, r) || !near(mc, x, y, z)) {
					return;
				}
				peer.carrier = new Carriers.Carrier(new Vec3(x, y, z), new Vec3(clampSpeed(vx), clampSpeed(vy), clampSpeed(vz)),
					Math.clamp(r, 0.1F, MAX_CARRIER_RADIUS));
				peer.carrierNanos = System.nanoTime();
			}
			case CARRIER_END -> peer.carrier = null;
			case SPRING -> {
				if (!data.readUTF().equals(dimension())) {
					return;
				}
				BlockPos pos = new BlockPos(data.readInt(), data.readInt(), data.readInt());
				Material m = Material.byNetworkId(data.readByte());
				boolean add = data.readBoolean();
				if (m == null || !m.fluid() || !near(mc, pos.getX(), pos.getY(), pos.getZ())) {
					return;
				}
				if (add) {
					if (peer.springs.size() >= MAX_SPRINGS_PER_PEER) {
						return;
					}
					if (world.setVirtualSpring(pos, m, true)) {
						peer.springs.add(pos);
					}
				} else {
					world.setVirtualSpring(pos, m, false);
					peer.springs.remove(pos);
				}
			}
			case SPRINGS_CLEAR -> {
				if (!data.readUTF().equals(dimension())) {
					return;
				}
				for (BlockPos pos : peer.springs) {
					world.setVirtualSpring(pos, Material.WATER, false);
				}
				peer.springs.clear();
			}
			case REMOVE -> {
				Material m = Material.byNetworkId(data.readByte());
				if (m != null) {
					world.removeAllLocal(mc.options.renderDistance().get() + 2, m);
				}
			}
			default -> {
			}
		}
	}

	private static boolean budget(PeerState peer, double volume) {
		long now = System.nanoTime();
		peer.volume = Math.min(VOLUME_BURST, peer.volume + (now - peer.lastRefill) * 1e-9 * VOLUME_RATE);
		peer.lastRefill = now;
		if (volume > peer.volume) {
			return false;
		}
		peer.volume -= volume;
		return true;
	}

	private static boolean near(Minecraft mc, double x, double y, double z) {
		return mc.player.position().distanceToSqr(x, y, z) < MAX_DISTANCE * MAX_DISTANCE;
	}

	private static float clampSpeed(float v) {
		return Math.clamp(v, -MAX_SPEED, MAX_SPEED);
	}

	private static boolean finite(double... values) {
		for (double v : values) {
			if (!Double.isFinite(v)) {
				return false;
			}
		}
		return true;
	}
}
