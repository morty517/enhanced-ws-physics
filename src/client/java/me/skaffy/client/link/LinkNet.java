package me.skaffy.client.link;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transport of the simulation link: a tiny TCP star, separate from the Minecraft connection. One player hosts (the port can
 * be forwarded with playit like a Minecraft server), the others join. Every message a peer sends goes to the host, which applies
 * it and passes it on to everyone else, stamped with the sender's id. Messages are opaque byte arrays here (see Link for what
 * they mean); this class only moves them, checks the password and keeps misbehaving peers from flooding anyone.
 *
 * <p>Plain Java on purpose (no Minecraft classes), so it can be tested on its own.
 */
public final class LinkNet implements AutoCloseable {
	public static final int PROTOCOL = 1;
	public static final int DEFAULT_PORT = 25599;
	public static final int MAX_FRAME = 1024;
	public static final int MAX_PEERS = 8;
	private static final byte[] MAGIC = "RWLINK".getBytes(StandardCharsets.US_ASCII);
	private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
	private static final int IDLE_TIMEOUT_MS = 30_000;
	private static final long PING_NANOS = 10_000_000_000L;
	/** Frames a peer may send per second (burst the same); anything beyond is dropped. */
	private static final int RATE = 400;
	private static final int QUEUE = 512;

	public interface Listener {
		/** A message from peer {@code sender} (0 = host). Called on a network thread. */
		void onMessage(int sender, String senderName, byte[] message);

		/** Something worth telling the player (connected, peer joined/left, errors). Called on a network thread. */
		void onEvent(String text);
	}

	private final boolean host;
	private final Listener listener;
	private final String name;
	private final byte[] password;
	private volatile boolean closed;
	private @org.jetbrains.annotations.Nullable ServerSocket server;
	private final List<Peer> peers = new CopyOnWriteArrayList<>();
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final AtomicInteger handshaking = new AtomicInteger();
	/** Joined side: the connection to the host. */
	private @org.jetbrains.annotations.Nullable Peer upstream;
	private volatile int ownId;

	private LinkNet(boolean host, String name, String password, Listener listener) {
		this.host = host;
		this.name = sanitizeName(name);
		this.password = password.getBytes(StandardCharsets.UTF_8);
		this.listener = listener;
	}

	public static LinkNet host(int port, String password, String name, Listener listener) throws IOException {
		LinkNet net = new LinkNet(true, name, password, listener);
		ServerSocket server = new ServerSocket();
		server.setReuseAddress(true);
		server.bind(new InetSocketAddress(port));
		net.server = server;
		net.ownId = 0;
		Thread t = new Thread(net::acceptLoop, "rw-link-accept");
		t.setDaemon(true);
		t.start();
		return net;
	}

	/** Connects in the background; the listener hears whether it worked. */
	public static LinkNet join(String address, int port, String password, String name, Listener listener) {
		LinkNet net = new LinkNet(false, name, password, listener);
		Thread t = new Thread(() -> net.connect(address, port), "rw-link-connect");
		t.setDaemon(true);
		t.start();
		return net;
	}

	public boolean isHost() {
		return this.host;
	}

	public boolean isClosed() {
		return this.closed;
	}

	/** Connected to the host (joined side) or hosting. */
	public boolean isUp() {
		return !this.closed && (this.host || this.upstream != null);
	}

	public List<String> peerNames() {
		List<String> out = new ArrayList<>();
		for (Peer p : this.peers) {
			out.add(p.name);
		}
		return out;
	}

	/** Sends a message to everyone else (host: to all peers; joined: to the host, which passes it on). Never blocks. */
	public void send(byte[] message) {
		if (this.closed || message.length > MAX_FRAME - 20) {
			return;
		}
		if (this.host) {
			byte[] framed = stamp(0, this.name, message);
			for (Peer p : this.peers) {
				p.enqueue(framed);
			}
		} else if (this.upstream != null) {
			this.upstream.enqueue(message);
		}
	}

	@Override
	public void close() {
		this.closed = true;
		try {
			if (this.server != null) {
				this.server.close();
			}
		} catch (IOException ignored) {
		}
		for (Peer p : this.peers) {
			p.close();
		}
		if (this.upstream != null) {
			this.upstream.close();
		}
	}

	// ---- Host side ----

	private void acceptLoop() {
		while (!this.closed) {
			try {
				Socket s = this.server.accept();
				if (this.handshaking.get() >= 4 || this.peers.size() >= MAX_PEERS) {
					s.close();
					continue;
				}
				this.handshaking.incrementAndGet();
				Thread t = new Thread(() -> this.welcome(s), "rw-link-peer");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				if (!this.closed) {
					this.listener.onEvent("Link: stopped accepting (" + e.getMessage() + ")");
				}
				return;
			}
		}
	}

	private void welcome(Socket s) {
		Peer peer = null;
		try (s) {
			s.setTcpNoDelay(true);
			s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(s.getInputStream());
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			byte[] nonce = new byte[16];
			new SecureRandom().nextBytes(nonce);
			out.write(MAGIC);
			out.writeInt(PROTOCOL);
			out.write(nonce);
			out.flush();
			byte[] magic = in.readNBytes(MAGIC.length);
			int protocol = in.readInt();
			byte[] proof = in.readNBytes(32);
			String peerName = sanitizeName(readShortString(in));
			if (!Arrays.equals(magic, MAGIC)) {
				return;
			}
			if (protocol != PROTOCOL) {
				reject(out, "different mod version (link protocol " + protocol + ", host has " + PROTOCOL + ")");
				return;
			}
			if (!MessageDigest.isEqual(proof, this.proof(nonce))) {
				Thread.sleep(1000); // slows down guessing
				reject(out, "wrong password");
				this.listener.onEvent("Link: someone tried to join with a wrong password");
				return;
			}
			if (this.closed || this.peers.size() >= MAX_PEERS) {
				reject(out, "host is full");
				return;
			}
			int id = this.nextId.getAndIncrement() & 0x7F;
			out.writeByte(1);
			out.writeByte(id);
			writeShortString(out, this.name);
			out.flush();
			s.setSoTimeout(IDLE_TIMEOUT_MS);
			peer = new Peer(s, in, out, id, peerName);
			this.peers.add(peer);
			this.handshaking.decrementAndGet();
			this.listener.onEvent("Link: " + peerName + " joined");
			peer.startWriter();
			peer.readLoop((sender, message) -> {
				// Apply here, then pass it on to everyone else.
				this.listener.onMessage(sender.id, sender.name, message);
				byte[] framed = stamp(sender.id, sender.name, message);
				for (Peer other : this.peers) {
					if (other != sender) {
						other.enqueue(framed);
					}
				}
			});
		} catch (IOException | InterruptedException e) {
			// handshake failed or connection lost
		} finally {
			if (peer == null) {
				this.handshaking.decrementAndGet();
			} else {
				this.peers.remove(peer);
				peer.close();
				if (!this.closed) {
					this.listener.onEvent("Link: " + peer.name + " left");
				}
			}
		}
	}

	private static void reject(DataOutputStream out, String reason) throws IOException {
		out.writeByte(0);
		writeShortString(out, reason);
		out.flush();
	}

	// ---- Joined side ----

	private void connect(String address, int port) {
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(address, port), HANDSHAKE_TIMEOUT_MS);
			s.setTcpNoDelay(true);
			s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(s.getInputStream());
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			byte[] magic = in.readNBytes(MAGIC.length);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new IOException("that address is not a realistic water link");
			}
			int protocol = in.readInt();
			byte[] nonce = in.readNBytes(16);
			out.write(MAGIC);
			out.writeInt(PROTOCOL);
			out.write(this.proof(nonce));
			writeShortString(out, this.name);
			out.flush();
			int ok = in.readUnsignedByte();
			if (ok != 1) {
				throw new IOException(readShortString(in));
			}
			if (protocol != PROTOCOL) {
				throw new IOException("different mod version");
			}
			this.ownId = in.readUnsignedByte();
			String hostName = sanitizeName(readShortString(in));
			s.setSoTimeout(IDLE_TIMEOUT_MS);
			Peer up = new Peer(s, in, out, 0, hostName);
			this.upstream = up;
			if (this.closed) {
				up.close();
				return;
			}
			this.listener.onEvent("Link: connected to " + hostName);
			up.startWriter();
			up.readLoop((from, framed) -> {
				if (framed.length < 2 || framed.length < 2 + (framed[1] & 0xFF)) {
					return;
				}
				int sender = framed[0] & 0xFF;
				int n = framed[1] & 0xFF;
				String senderName = sanitizeName(new String(framed, 2, n, StandardCharsets.US_ASCII));
				this.listener.onMessage(sender, senderName, Arrays.copyOfRange(framed, 2 + n, framed.length));
			});
			if (!this.closed) {
				this.listener.onEvent("Link: disconnected from " + hostName);
			}
		} catch (IOException e) {
			if (!this.closed) {
				this.listener.onEvent("Link: " + (e instanceof SocketTimeoutException ? "the host doesn't answer" : e.getMessage()));
			}
		} finally {
			try {
				s.close();
			} catch (IOException ignored) {
			}
			this.closed = true;
			this.upstream = null;
		}
	}

	// ---- Shared ----

	private byte[] proof(byte[] nonce) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update("realistic-water-link".getBytes(StandardCharsets.US_ASCII));
			sha.update(nonce);
			sha.update(this.password);
			return sha.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Host -> peers: [sender id][name length][name][message]. Names are at most 16 ASCII characters. */
	private static byte[] stamp(int sender, String name, byte[] message) {
		byte[] n = name.getBytes(StandardCharsets.US_ASCII);
		byte[] framed = new byte[message.length + 2 + n.length];
		framed[0] = (byte) sender;
		framed[1] = (byte) n.length;
		System.arraycopy(n, 0, framed, 2, n.length);
		System.arraycopy(message, 0, framed, 2 + n.length, message.length);
		return framed;
	}

	static String sanitizeName(String name) {
		String s = name.replaceAll("[^A-Za-z0-9_]", "");
		return s.isEmpty() ? "player" : s.length() > 16 ? s.substring(0, 16) : s;
	}

	private static String readShortString(DataInputStream in) throws IOException {
		int n = in.readUnsignedByte();
		return new String(in.readNBytes(n), StandardCharsets.UTF_8);
	}

	private static void writeShortString(DataOutputStream out, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		int n = Math.min(b.length, 255);
		out.writeByte(n);
		out.write(b, 0, n);
	}

	private interface Handler {
		void handle(Peer from, byte[] frame);
	}

	private final class Peer {
		final Socket socket;
		final DataInputStream in;
		final DataOutputStream out;
		final int id;
		final String name;
		final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE);
		private double tokens = RATE;
		private long lastRefill = System.nanoTime();

		Peer(Socket socket, DataInputStream in, DataOutputStream out, int id, String name) {
			this.socket = socket;
			this.in = in;
			this.out = out;
			this.id = id;
			this.name = name;
		}

		/** Drops the message if this peer can't keep up (rather than blocking the game). */
		void enqueue(byte[] frame) {
			this.queue.offer(frame);
		}

		void startWriter() {
			Thread t = new Thread(() -> {
				long lastSend = System.nanoTime();
				try {
					while (!LinkNet.this.closed && !this.socket.isClosed()) {
						byte[] frame = this.queue.poll(1, TimeUnit.SECONDS);
						long now = System.nanoTime();
						if (frame == null) {
							if (now - lastSend < PING_NANOS) {
								continue;
							}
							frame = new byte[0]; // keepalive
						}
						this.out.writeInt(frame.length);
						this.out.write(frame);
						// Batch whatever else is waiting into the same flush.
						byte[] more;
						while ((more = this.queue.poll()) != null) {
							this.out.writeInt(more.length);
							this.out.write(more);
						}
						this.out.flush();
						lastSend = now;
					}
				} catch (IOException | InterruptedException e) {
					this.close();
				}
			}, "rw-link-write");
			t.setDaemon(true);
			t.start();
		}

		void readLoop(Handler handler) throws IOException {
			while (!LinkNet.this.closed) {
				int len;
				try {
					len = this.in.readInt();
				} catch (EOFException e) {
					return;
				}
				if (len < 0 || len > MAX_FRAME) {
					throw new IOException("oversized frame");
				}
				byte[] frame = this.in.readNBytes(len);
				if (frame.length != len) {
					return;
				}
				if (len == 0 || !this.allow()) {
					continue; // keepalive, or over the rate limit
				}
				handler.handle(this, frame);
			}
		}

		private boolean allow() {
			long now = System.nanoTime();
			this.tokens = Math.min(RATE, this.tokens + (now - this.lastRefill) * 1e-9 * RATE);
			this.lastRefill = now;
			if (this.tokens < 1.0) {
				return false;
			}
			this.tokens -= 1.0;
			return true;
		}

		void close() {
			try {
				this.socket.close();
			} catch (IOException ignored) {
			}
		}
	}
}
