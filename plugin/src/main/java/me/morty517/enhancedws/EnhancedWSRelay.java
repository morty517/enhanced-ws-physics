package me.morty517.enhancedws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Server-side transport for Enhanced WS Physics. Players with the mod send what they do to their simulation (a cube dropped,
 * the hose, springs, removals, the grab tool) on the plugin channel; this plugin checks it (permissions, particle limits,
 * sizes, distance) and passes it on to the other players with the mod nearby, so everyone simulates the same events. It also
 * tells every player which simulation resolution to use, so everyone has the same amount of particles, and keeps hose springs,
 * so they are there for players who join later.
 *
 * <p>Message format: see the mod's Link class (same messages as the mod's own player-to-player link).
 */
public final class EnhancedWSRelay extends JavaPlugin implements PluginMessageListener, Listener, TabExecutor {
	static final String CHANNEL = "realistic-water:link";

	// Messages from players (same as the mod's link messages).
	private static final byte SPAWN = 1;
	private static final byte CARRIER = 2;
	private static final byte CARRIER_END = 3;
	private static final byte SPRING = 4;
	private static final byte SPRINGS_CLEAR = 5;
	private static final byte REMOVE = 6;
	/**
	 * The mod says hello after joining. Needed behind Velocity: the client announces the channel while connecting to the proxy,
	 * and the proxy doesn't pass that on to this server, so the player would never count as having the mod.
	 */
	private static final byte HELLO = 64;

	// Messages to players: [kind] ...
	private static final byte K_RELAY = 0;    // int sender id, UTF sender name, message
	private static final byte K_SETTINGS = 1; // int water resolution, int sand resolution (0 = player's own), int limit (-1 = none), int max springs
	private static final byte K_NOTICE = 2;   // UTF text (action bar)

	/** Mod material network ids that are fluids (springs only make sense for those). */
	private static final String[] MATERIAL_IDS = {"water", "sand", "milk", "red_sand", "gravel", "suspicious_milk", "snow", "lava",
		"sandstone", "dirt", "clay", "suspicious_curd"};
	private static final boolean[] FLUID = {true, false, true, false, false, true, false, true, false, false, false, false};
	/** The mod's own resolutions, assumed when the server leaves it to the players. */
	private static final int MOD_WATER_RES = 6;
	private static final int MOD_SAND_RES = 12;
	/** Particles per simulation cell when material is dropped. */
	private static final int PARTICLES_PER_CELL = 8;
	private static final float MAX_EDGE = 16.0F;
	private static final double MAX_SPAWN_DISTANCE = 64.0;
	private static final int SERVER_SENDER = 0;

	private boolean enabled;
	private int waterRes;
	private int sandRes;
	private long defaultLimit;
	private int maxSprings;
	private double relayDistance;
	private boolean debugEcho;

	private final Map<UUID, Budget> budgets = new HashMap<>();
	/** World key -> block position -> spring. */
	private final Map<String, Map<Long, Spring>> springs = new HashMap<>();
	private File springsFile;

	private record Spring(int x, int y, int z, byte material, UUID owner, String ownerName) {
	}

	private static final class Budget {
		double tokens = -1;
		long last = System.nanoTime();
		long lastNotice;
	}

	@Override
	public void onEnable() {
		this.saveDefaultConfig();
		this.loadSettings();
		this.springsFile = new File(this.getDataFolder(), "springs.yml");
		this.loadSprings();
		this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
		this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
		this.getServer().getPluginManager().registerEvents(this, this);
		var cmd = this.getCommand("ews");
		if (cmd != null) {
			cmd.setExecutor(this);
			cmd.setTabCompleter(this);
		}
		// Players already online after a reload.
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.getListeningPluginChannels().contains(CHANNEL)) {
				this.greet(p);
			}
		}
	}

	@Override
	public void onDisable() {
		this.saveSprings();
		this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
		this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
	}

	private void loadSettings() {
		this.reloadConfig();
		var c = this.getConfig();
		this.enabled = c.getBoolean("enabled", true);
		this.waterRes = Math.clamp(c.getInt("water-resolution", 6), 0, 16);
		this.sandRes = Math.clamp(c.getInt("sand-resolution", 8), 0, 16);
		this.defaultLimit = Math.max(0L, c.getLong("default-limit", 50000L));
		this.maxSprings = Math.max(0, c.getInt("max-springs", 16));
		this.relayDistance = Math.max(16.0, c.getDouble("relay-distance", 160.0));
		this.debugEcho = c.getBoolean("debug-echo", false);
	}

	// ---- Players with the mod ----

	@EventHandler
	public void onRegister(PlayerRegisterChannelEvent event) {
		if (event.getChannel().equals(CHANNEL)) {
			Player p = event.getPlayer();
			Bukkit.getScheduler().runTask(this, () -> {
				if (p.isOnline()) {
					this.greet(p);
				}
			});
		}
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		Player p = event.getPlayer();
		if (p.getListeningPluginChannels().contains(CHANNEL)) {
			this.sendSprings(p);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player p = event.getPlayer();
		this.budgets.remove(p.getUniqueId());
		// Let go of whatever they were holding.
		byte[] end = message(out -> out.writeByte(CARRIER_END));
		for (Player other : p.getWorld().getPlayers()) {
			if (other != p) {
				this.send(other, relay(p.getEntityId(), p.getName(), end));
			}
		}
	}

	/**
	 * Makes the server treat the player as listening on the channel (CraftPlayer.addChannel, which also fires
	 * PlayerRegisterChannelEvent and so greets them). There is no API for it; without it the server drops every message to them.
	 */
	private void adopt(Player p) {
		if (p.getListeningPluginChannels().contains(CHANNEL)) {
			return;
		}
		try {
			p.getClass().getMethod("addChannel", String.class).invoke(p, CHANNEL);
			this.getLogger().info(p.getName() + " has the mod (said hello on the channel)");
		} catch (ReflectiveOperationException | RuntimeException e) {
			this.getLogger().warning("Couldn't register the channel for " + p.getName() + ": " + e);
		}
	}

	private void greet(Player p) {
		this.sendSettings(p);
		this.sendSprings(p);
	}

	private void sendSettings(Player p) {
		long limit = this.limitOf(p);
		this.send(p, message(out -> {
			out.writeByte(K_SETTINGS);
			out.writeInt(this.waterRes);
			out.writeInt(this.sandRes);
			out.writeInt(limit == Long.MAX_VALUE ? -1 : (int) Math.min(limit, Integer.MAX_VALUE));
			out.writeInt(p.hasPermission("enhancedws.admin") ? -1 : this.maxSprings);
		}));
	}

	private void sendSprings(Player p) {
		Map<Long, Spring> inWorld = this.springs.get(worldKey(p.getWorld()));
		if (inWorld == null) {
			return;
		}
		String dim = worldKey(p.getWorld());
		for (Spring s : inWorld.values()) {
			this.send(p, relay(senderId(s.owner), s.ownerName, springMessage(dim, s, true)));
		}
	}

	// ---- Incoming ----

	@Override
	public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] bytes) {
		if (!channel.equals(CHANNEL) || !this.enabled || bytes.length == 0 || bytes.length > 1024
			|| !player.hasPermission("enhancedws.use")) {
			return;
		}
		try {
			this.handle(player, bytes);
		} catch (IOException | RuntimeException e) {
			// malformed: ignore
		}
	}

	private void handle(Player player, byte[] bytes) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		byte type = in.readByte();
		String dim = worldKey(player.getWorld());
		if (type != HELLO && !player.getListeningPluginChannels().contains(CHANNEL)) {
			this.adopt(player); // talking to us on the channel, so it has the mod
		}
		switch (type) {
			case HELLO -> {
				boolean known = player.getListeningPluginChannels().contains(CHANNEL);
				this.adopt(player);
				if (known) {
					this.greet(player); // already registered (no proxy, or registered later): just answer
				}
			}
			case SPAWN -> {
				if (!in.readUTF().equals(dim)) {
					return;
				}
				int m = in.readByte();
				double x = in.readDouble();
				double y = in.readDouble();
				double z = in.readDouble();
				float sx = in.readFloat();
				float sy = in.readFloat();
				float sz = in.readFloat();
				float vx = in.readFloat();
				float vy = in.readFloat();
				float vz = in.readFloat();
				if (m < 0 || m >= MATERIAL_IDS.length || !finite(x, y, z, sx, sy, sz, vx, vy, vz) || sx <= 0 || sy <= 0 || sz <= 0
					|| sx > MAX_EDGE || sy > MAX_EDGE || sz > MAX_EDGE) {
					return;
				}
				Location at = new Location(player.getWorld(), x + sx / 2, y + sy / 2, z + sz / 2);
				if (at.distanceSquared(player.getLocation()) > MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE) {
					return;
				}
				if (!this.materialAllowed(player, m)) {
					this.notice(player, "You can't use " + MATERIAL_IDS[m].replace('_', ' ') + " here");
					return;
				}
				int res = FLUID[m] ? (this.waterRes > 0 ? this.waterRes : MOD_WATER_RES) : (this.sandRes > 0 ? this.sandRes : MOD_SAND_RES);
				double particles = (double) sx * sy * sz * res * res * res * PARTICLES_PER_CELL;
				if (!this.spend(player, particles)) {
					return;
				}
				this.relayNear(player, at, bytes);
			}
			case CARRIER -> {
				if (!player.hasPermission("enhancedws.grab") || !in.readUTF().equals(dim)) {
					return;
				}
				double x = in.readDouble();
				double y = in.readDouble();
				double z = in.readDouble();
				if (!finite(x, y, z)) {
					return;
				}
				this.relayNear(player, new Location(player.getWorld(), x, y, z), bytes);
			}
			case CARRIER_END -> this.relayNear(player, player.getLocation(), bytes);
			case SPRING -> {
				if (!in.readUTF().equals(dim)) {
					return;
				}
				int x = in.readInt();
				int y = in.readInt();
				int z = in.readInt();
				byte m = in.readByte();
				boolean add = in.readBoolean();
				Location at = new Location(player.getWorld(), x + 0.5, y + 0.5, z + 0.5);
				if (m < 0 || m >= MATERIAL_IDS.length || !FLUID[m] || at.distanceSquared(player.getLocation()) > MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE) {
					return;
				}
				Map<Long, Spring> inWorld = this.springs.computeIfAbsent(dim, k -> new LinkedHashMap<>());
				long key = packPos(x, y, z);
				boolean admin = player.hasPermission("enhancedws.admin");
				if (add) {
					if (!player.hasPermission("enhancedws.spring") || !this.materialAllowed(player, m) || inWorld.containsKey(key)) {
						return;
					}
					long mine = inWorld.values().stream().filter(s -> s.owner.equals(player.getUniqueId())).count();
					if (!admin && mine >= this.maxSprings) {
						this.notice(player, "Spring limit reached (" + this.maxSprings + "). Others don't see this one");
						return;
					}
					inWorld.put(key, new Spring(x, y, z, m, player.getUniqueId(), player.getName()));
				} else {
					Spring s = inWorld.get(key);
					if (s == null) {
						return;
					}
					if (!admin && !s.owner.equals(player.getUniqueId())) {
						this.notice(player, "That spring is " + s.ownerName + "'s, it stays for everyone else");
						return;
					}
					inWorld.remove(key);
				}
				this.saveSpringsLater();
				// Springs stay on the server and every player in the world gets them, near or not (so removals reach everyone too).
				for (Player other : player.getWorld().getPlayers()) {
					if (other != player || this.debugEcho) {
						this.send(other, relay(other == player ? -1 : player.getEntityId(), player.getName(), bytes));
					}
				}
			}
			case SPRINGS_CLEAR -> {
				if (!in.readUTF().equals(dim)) {
					return;
				}
				this.clearSprings(player.getWorld(), player.getUniqueId());
			}
			case REMOVE -> {
				int m = in.readByte();
				if (m < 0 || m >= MATERIAL_IDS.length || !player.hasPermission("enhancedws.remove")) {
					return; // without the permission /water remove only clears the player's own simulation
				}
				for (Player other : player.getWorld().getPlayers()) {
					if (other != player || this.debugEcho) {
						this.send(other, relay(player.getEntityId(), player.getName(), bytes));
					}
				}
			}
			default -> {
			}
		}
	}

	private boolean materialAllowed(Player p, int m) {
		String node = "enhancedws.material." + MATERIAL_IDS[m];
		return !p.isPermissionSet(node) || p.hasPermission(node);
	}

	/** Particle budget: refills at the player's limit per second, holds at most one second's worth. */
	private boolean spend(Player p, double particles) {
		long limit = this.limitOf(p);
		if (limit == Long.MAX_VALUE) {
			return true;
		}
		Budget b = this.budgets.computeIfAbsent(p.getUniqueId(), k -> new Budget());
		long now = System.nanoTime();
		b.tokens = b.tokens < 0 ? limit : Math.min(limit, b.tokens + (now - b.last) * 1e-9 * limit);
		b.last = now;
		if (particles > b.tokens) {
			if (now - b.lastNotice > 2_000_000_000L) {
				b.lastNotice = now;
				this.notice(p, particles > limit
					? "Too big: ~" + (long) particles + " particles, your limit is " + limit + ". Others don't see it"
					: "Slow down: particle limit " + limit + "/s. Others don't see some of it");
			}
			return false;
		}
		b.tokens -= particles;
		return true;
	}

	/** The player's particle limit per second: the highest enhancedws.spawn.limit.N they have, else the default. */
	private long limitOf(Player p) {
		if (p.hasPermission("enhancedws.spawn.unlimited")) {
			return Long.MAX_VALUE;
		}
		long best = -1;
		for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
			String perm = info.getPermission();
			if (info.getValue() && perm.startsWith("enhancedws.spawn.limit.")) {
				try {
					best = Math.max(best, Long.parseLong(perm.substring("enhancedws.spawn.limit.".length())));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return best >= 0 ? best : this.defaultLimit;
	}

	// ---- Outgoing ----

	private void relayNear(Player from, Location at, byte[] message) {
		byte[] framed = relay(from.getEntityId(), from.getName(), message);
		double d2 = this.relayDistance * this.relayDistance;
		for (Player other : from.getWorld().getPlayers()) {
			if ((other != from || this.debugEcho) && other.getLocation().distanceSquared(at) <= d2) {
				this.send(other, other == from ? relay(-1, "echo", message) : framed);
			}
		}
	}

	private void send(Player p, byte[] bytes) {
		if (p.getListeningPluginChannels().contains(CHANNEL)) {
			p.sendPluginMessage(this, CHANNEL, bytes);
		}
	}

	private void notice(Player p, String text) {
		this.send(p, message(out -> {
			out.writeByte(K_NOTICE);
			out.writeUTF(text);
		}));
	}

	private void clearSprings(World world, UUID owner) {
		String dim = worldKey(world);
		Map<Long, Spring> inWorld = this.springs.get(dim);
		if (inWorld == null) {
			return;
		}
		List<Spring> removed = new ArrayList<>();
		for (Iterator<Spring> it = inWorld.values().iterator(); it.hasNext(); ) {
			Spring s = it.next();
			if (owner == null || s.owner.equals(owner)) {
				removed.add(s);
				it.remove();
			}
		}
		for (Spring s : removed) {
			byte[] framed = relay(senderId(s.owner), s.ownerName, springMessage(dim, s, false));
			for (Player p : world.getPlayers()) {
				this.send(p, framed);
			}
		}
		this.saveSpringsLater();
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] message(Writer w) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
			w.write(new DataOutputStream(bytes));
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] relay(int sender, String name, byte[] msg) {
		return message(out -> {
			out.writeByte(K_RELAY);
			out.writeInt(sender);
			out.writeUTF(name);
			out.write(msg);
		});
	}

	private static byte[] springMessage(String dim, Spring s, boolean add) {
		return message(out -> {
			out.writeByte(SPRING);
			out.writeUTF(dim);
			out.writeInt(s.x);
			out.writeInt(s.y);
			out.writeInt(s.z);
			out.writeByte(s.material);
			out.writeBoolean(add);
		});
	}

	/** A stable id for springs whose owner may be offline (players are keyed by entity id, which is positive). */
	private static int senderId(UUID owner) {
		return Integer.MIN_VALUE + (owner.hashCode() & 0x3FFFFFFF);
	}

	private static String worldKey(World w) {
		return w.getKey().toString();
	}

	private static long packPos(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	private static boolean finite(double... values) {
		for (double v : values) {
			if (!Double.isFinite(v)) {
				return false;
			}
		}
		return true;
	}

	// ---- Spring storage ----

	private boolean saveQueued;

	private void saveSpringsLater() {
		if (!this.saveQueued) {
			this.saveQueued = true;
			Bukkit.getScheduler().runTaskLater(this, () -> {
				this.saveQueued = false;
				this.saveSprings();
			}, 100L);
		}
	}

	private void saveSprings() {
		YamlConfiguration yml = new YamlConfiguration();
		for (var world : this.springs.entrySet()) {
			List<String> lines = new ArrayList<>();
			for (Spring s : world.getValue().values()) {
				lines.add(s.x + " " + s.y + " " + s.z + " " + s.material + " " + s.owner + " " + s.ownerName);
			}
			yml.set(world.getKey().replace(':', '|'), lines);
		}
		try {
			this.getDataFolder().mkdirs();
			yml.save(this.springsFile);
		} catch (IOException e) {
			this.getLogger().warning("Couldn't save springs: " + e.getMessage());
		}
	}

	private void loadSprings() {
		this.springs.clear();
		if (!this.springsFile.exists()) {
			return;
		}
		YamlConfiguration yml = YamlConfiguration.loadConfiguration(this.springsFile);
		for (String key : yml.getKeys(false)) {
			Map<Long, Spring> inWorld = new LinkedHashMap<>();
			for (String line : yml.getStringList(key)) {
				String[] p = line.split(" ");
				try {
					Spring s = new Spring(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Byte.parseByte(p[3]),
						UUID.fromString(p[4]), p[5]);
					inWorld.put(packPos(s.x, s.y, s.z), s);
				} catch (RuntimeException ignored) {
				}
			}
			this.springs.put(key.replace('|', ':'), inWorld);
		}
	}

	// ---- /ews ----

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
		String sub = args.length > 0 ? args[0].toLowerCase() : "status";
		switch (sub) {
			case "status" -> {
				List<String> modded = Bukkit.getOnlinePlayers().stream().filter(p -> p.getListeningPluginChannels().contains(CHANNEL))
					.map(Player::getName).collect(Collectors.toList());
				int springCount = this.springs.values().stream().mapToInt(Map::size).sum();
				sender.sendMessage("§bEnhanced WS relay§r: " + (this.enabled ? "§aon" : "§coff") + "§r, resolution water "
					+ res(this.waterRes) + ", sand " + res(this.sandRes) + ", default limit " + this.defaultLimit + " particles/s, "
					+ springCount + " springs");
				sender.sendMessage("With the mod (" + modded.size() + "): " + String.join(", ", modded));
			}
			case "resolution" -> {
				if (args.length < 3 || !(args[1].equalsIgnoreCase("water") || args[1].equalsIgnoreCase("sand"))) {
					sender.sendMessage("/ews resolution <water|sand> <2-16|off>");
					return true;
				}
				int value;
				if (args[2].equalsIgnoreCase("off")) {
					value = 0;
				} else {
					try {
						value = Math.clamp(Integer.parseInt(args[2]), 2, 16);
					} catch (NumberFormatException e) {
						sender.sendMessage("Not a number: " + args[2]);
						return true;
					}
				}
				boolean water = args[1].equalsIgnoreCase("water");
				this.getConfig().set(water ? "water-resolution" : "sand-resolution", value);
				this.saveConfig();
				this.loadSettings();
				this.broadcastSettings();
				sender.sendMessage((water ? "Water" : "Sand") + " resolution: " + res(value) + " (sent to everyone with the mod)");
			}
			case "limit" -> {
				if (args.length < 2) {
					sender.sendMessage("/ews limit <particles per second>  (default; per player: enhancedws.spawn.limit.<n>)");
					return true;
				}
				try {
					this.getConfig().set("default-limit", Math.max(0L, Long.parseLong(args[1])));
				} catch (NumberFormatException e) {
					sender.sendMessage("Not a number: " + args[1]);
					return true;
				}
				this.saveConfig();
				this.loadSettings();
				this.budgets.clear();
				this.broadcastSettings();
				sender.sendMessage("Default limit: " + this.defaultLimit + " particles/s");
			}
			case "clear" -> {
				List<World> worlds = new ArrayList<>();
				if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
					worlds.addAll(Bukkit.getWorlds());
				} else if (sender instanceof Player p) {
					worlds.add(p.getWorld());
				} else {
					worlds.addAll(Bukkit.getWorlds());
				}
				for (World w : worlds) {
					this.clearSprings(w, null);
					for (int m = 0; m < MATERIAL_IDS.length; m++) {
						byte id = (byte) m;
						byte[] framed = relay(SERVER_SENDER, "server", message(out -> {
							out.writeByte(REMOVE);
							out.writeByte(id);
						}));
						for (Player p : w.getPlayers()) {
							this.send(p, framed);
						}
					}
				}
				sender.sendMessage("Cleared simulations and springs in " + worlds.size() + " world(s)");
			}
			case "springs" -> {
				if (args.length < 2 || !args[1].equalsIgnoreCase("clear")) {
					sender.sendMessage("/ews springs clear [player]");
					return true;
				}
				UUID owner = null;
				if (args.length > 2) {
					var target = Bukkit.getOfflinePlayer(args[2]);
					owner = target.getUniqueId();
				}
				for (World w : Bukkit.getWorlds()) {
					this.clearSprings(w, owner);
				}
				sender.sendMessage("Springs cleared" + (owner != null ? " for " + args[2] : ""));
			}
			case "toggle" -> {
				this.getConfig().set("enabled", !this.enabled);
				this.saveConfig();
				this.loadSettings();
				sender.sendMessage("Relay " + (this.enabled ? "§aon" : "§coff"));
			}
			case "reload" -> {
				this.loadSettings();
				this.budgets.clear();
				this.broadcastSettings();
				sender.sendMessage("Reloaded");
			}
			default -> sender.sendMessage("/ews <status|resolution|limit|clear|springs|toggle|reload>");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
		List<String> options = switch (args.length) {
			case 1 -> List.of("status", "resolution", "limit", "clear", "springs", "toggle", "reload");
			case 2 -> switch (args[0].toLowerCase()) {
				case "resolution" -> List.of("water", "sand");
				case "clear" -> List.of("all");
				case "springs" -> List.of("clear");
				default -> List.of();
			};
			case 3 -> args[0].equalsIgnoreCase("resolution") ? List.of("4", "6", "8", "12", "off")
				: args[0].equalsIgnoreCase("springs") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
			default -> List.of();
		};
		String prefix = args[args.length - 1].toLowerCase();
		return options.stream().filter(o -> o.toLowerCase().startsWith(prefix)).toList();
	}

	private void broadcastSettings() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			this.sendSettings(p);
		}
	}

	private static String res(int value) {
		return value <= 0 ? "player's own" : String.valueOf(value);
	}
}
