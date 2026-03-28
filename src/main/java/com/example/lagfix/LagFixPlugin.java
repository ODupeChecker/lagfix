package com.example.lagfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LagFixPlugin extends JavaPlugin implements Listener {

    private static final String BOSS_SPAWN_COMMAND = "mm mobs spawn HPC_GRANDWARDEN:1 1 worldo12,-11,67,-214,0,0";
    private static final long DEFAULT_BOSS_INTERVAL_MILLIS = 60L * 60L * 1000L;
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)h$", Pattern.CASE_INSENSITIVE);
    private static final String WHITELIST_PICKAXE_MARKER = "ᴄᴀɴ ᴏɴʟʏ ᴍɪɴᴇ ɢᴇɴꜱ";
    private static final String WHITELIST_PICKAXE_CONFIG_PATH = "whitelist-pickaxe.blocks";
    private static final String SPAWN_PROTECTION_CONFIG_PATH = "spawn-movement-protection";
    private static final String FORWARD_TELEPORT_WALL_CHECK_CONFIG_PATH = "forward-teleport-wall-check";
    private static final Vector ZERO_VECTOR = new Vector(0, 0, 0);

    private final HashMap<UUID, PacketCounter> packetCounters = new HashMap<>();
    private ProtocolManager protocolManager;
    private BossTimerPlaceholder bossTimerPlaceholder;
    private volatile long bossIntervalMillis;
    private volatile long nextBossSpawnMillis;
    private boolean worldGuardAvailable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBossTimerState();
        worldGuardAvailable = setupWorldGuardSupport();

        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().severe("ProtocolLib is required but not found. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerWindowClickListener();
        startCounterResetTask();
        startBossTimerTask();
        registerBossTimerPlaceholder();

        getLogger().info("LagFix enabled.");
    }

    @Override
    public void onDisable() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }
        if (bossTimerPlaceholder != null) {
            bossTimerPlaceholder.unregister();
            bossTimerPlaceholder = null;
        }
        synchronized (packetCounters) {
            packetCounters.clear();
        }
        saveBossTimerState();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bosstimer")) {
            return handleBossTimerCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("whitelist")) {
            return handleWhitelistCommand(sender, args);
        }

        return false;
    }

    private boolean handleBossTimerCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /bosstimer set <1h/2h/1.5h/3h> or /bosstimer spawn");
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /bosstimer set <1h/2h/1.5h/3h>");
                return true;
            }

            long parsedDuration = parseDurationMillis(args[1]);
            if (parsedDuration <= 0L) {
                sender.sendMessage(ChatColor.RED + "Invalid duration. Use values like 1h, 2h, 1.5h, or 3h.");
                return true;
            }

            bossIntervalMillis = parsedDuration;
            nextBossSpawnMillis = System.currentTimeMillis() + bossIntervalMillis;
            saveBossTimerState();
            sender.sendMessage(ChatColor.GREEN + "Boss timer set to " + formatDurationWords(bossIntervalMillis)
                    + ". Next spawn in " + formatRemainingTime() + ".");
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            spawnBossAndResetTimer();
            sender.sendMessage(ChatColor.GREEN + "Boss spawned. Timer reset to " + formatRemainingTime() + ".");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /bosstimer set <1h/2h/1.5h/3h> or /bosstimer spawn");
        return true;
    }

    private boolean handleWhitelistCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can use this command.");
            return true;
        }

        if (args.length != 3 || !args[0].equalsIgnoreCase("pickaxe")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /whitelist pickaxe <add|remove> <block>");
            return true;
        }

        Material material = Material.matchMaterial(args[2]);
        if (material == null || !material.isBlock()) {
            sender.sendMessage(ChatColor.RED + "Unknown block: " + args[2]);
            return true;
        }

        Set<String> whitelist = getWhitelistedBlockNames();
        String materialName = material.name();

        if (args[1].equalsIgnoreCase("add")) {
            if (!whitelist.add(materialName)) {
                sender.sendMessage(ChatColor.YELLOW + materialName + " is already whitelisted for whitelist pickaxes.");
                return true;
            }

            saveWhitelistedBlockNames(whitelist);
            sender.sendMessage(ChatColor.GREEN + "Added " + materialName + " to the whitelist pickaxe block list.");
            return true;
        }

        if (args[1].equalsIgnoreCase("remove")) {
            if (!whitelist.remove(materialName)) {
                sender.sendMessage(ChatColor.YELLOW + materialName + " is not in the whitelist pickaxe block list.");
                return true;
            }

            saveWhitelistedBlockNames(whitelist);
            sender.sendMessage(ChatColor.GREEN + "Removed " + materialName + " from the whitelist pickaxe block list.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /whitelist pickaxe <add|remove> <block>");
        return true;
    }

    private void registerWindowClickListener() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST,
                PacketType.Play.Client.WINDOW_CLICK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();

                int maxPerSecond = getConfig().getInt("max-window-clicks-per-second", 20);
                boolean kickPlayer = getConfig().getBoolean("kick-player", true);

                PacketCounter counter;
                int currentCount;
                synchronized (packetCounters) {
                    counter = packetCounters.computeIfAbsent(uuid, ignored -> new PacketCounter());
                    currentCount = ++counter.windowClicksThisSecond;
                    counter.lastSeenMillis = now;
                }

                if (currentCount <= maxPerSecond) {
                    return;
                }

                event.setCancelled(true);
                logViolation(player.getName(), currentCount, now, counter);

                if (kickPlayer) {
                    Bukkit.getScheduler().runTask(LagFixPlugin.this,
                            () -> player.kickPlayer("Too many inventory packets"));
                }
            }
        });
    }

    private void logViolation(String playerName, int packetRate, long now, PacketCounter counter) {
        final long logCooldownMillis = 5000L;

        synchronized (packetCounters) {
            if (now - counter.lastLogMillis < logCooldownMillis) {
                return;
            }
            counter.lastLogMillis = now;
        }

        getLogger().warning("Blocked WINDOW_CLICK packet flood from " + playerName
                + " at " + packetRate + " packets/sec.");
    }

    private void startCounterResetTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            synchronized (packetCounters) {
                Iterator<Map.Entry<UUID, PacketCounter>> iterator = packetCounters.entrySet().iterator();
                while (iterator.hasNext()) {
                    PacketCounter counter = iterator.next().getValue();
                    counter.windowClicksThisSecond = 0;

                    if (now - counter.lastSeenMillis > 10000L) {
                        iterator.remove();
                    }
                }
            }
        }, 20L, 20L);
    }

    private void startBossTimerTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (System.currentTimeMillis() < nextBossSpawnMillis) {
                return;
            }
            spawnBossAndResetTimer();
        }, 20L, 20L);
    }

    private void registerBossTimerPlaceholder() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().warning("PlaceholderAPI not found. %boss_timer% will not be available.");
            return;
        }

        bossTimerPlaceholder = new BossTimerPlaceholder(this);
        bossTimerPlaceholder.register();
        getLogger().info("Registered PlaceholderAPI placeholder %boss_timer%.");
    }

    private synchronized void loadBossTimerState() {
        bossIntervalMillis = getConfig().getLong("boss-timer.interval-millis", DEFAULT_BOSS_INTERVAL_MILLIS);
        if (bossIntervalMillis <= 0L) {
            bossIntervalMillis = DEFAULT_BOSS_INTERVAL_MILLIS;
        }

        nextBossSpawnMillis = getConfig().getLong("boss-timer.next-spawn-millis", 0L);
        if (nextBossSpawnMillis <= 0L) {
            nextBossSpawnMillis = System.currentTimeMillis() + bossIntervalMillis;
            saveBossTimerState();
        }
    }

    private synchronized void saveBossTimerState() {
        getConfig().set("boss-timer.interval-millis", bossIntervalMillis);
        getConfig().set("boss-timer.next-spawn-millis", nextBossSpawnMillis);
        saveConfig();
    }

    private synchronized void spawnBossAndResetTimer() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), BOSS_SPAWN_COMMAND);
        nextBossSpawnMillis = System.currentTimeMillis() + bossIntervalMillis;
        saveBossTimerState();
    }

    public synchronized String formatRemainingTime() {
        long remainingMillis = Math.max(0L, nextBossSpawnMillis - System.currentTimeMillis());
        long totalSeconds = (remainingMillis + 999L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private long parseDurationMillis(String input) {
        Matcher matcher = DURATION_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return -1L;
        }

        double hours = Double.parseDouble(matcher.group(1));
        long millis = Math.round(hours * 60D * 60D * 1000D);
        return millis > 0L ? millis : -1L;
    }

    private String formatDurationWords(long durationMillis) {
        long totalMinutes = Math.round(durationMillis / 60000.0D);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;

        if (minutes == 0L) {
            return hours + (hours == 1L ? " hour" : " hours");
        }

        return hours + (hours == 1L ? " hour " : " hours ") + minutes
                + (minutes == 1L ? " minute" : " minutes");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isWhitelistPickaxe(item)) {
            return;
        }

        Material brokenBlockType = event.getBlock().getType();
        if (getWhitelistedBlockNames().contains(brokenBlockType.name())) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "This whitelist pickaxe can only mine whitelisted blocks.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (!shouldBlockExternalMovement(player.getLocation())) {
            return;
        }

        Vector velocity = event.getVelocity();
        if (velocity == null || velocity.lengthSquared() <= 0.0D) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && shouldBlockExternalMovement(player.getLocation())) {
                player.setVelocity(ZERO_VECTOR);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!getConfig().getBoolean(FORWARD_TELEPORT_WALL_CHECK_CONFIG_PATH + ".enabled", true)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }

        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }

        if (isLocationPassableForPlayer(to)) {
            return;
        }

        Vector movement = to.toVector().subtract(from.toVector());
        double movementLength = movement.length();
        if (movementLength <= 0.0D) {
            return;
        }

        Vector direction = movement.normalize();
        double step = getConfig().getDouble(FORWARD_TELEPORT_WALL_CHECK_CONFIG_PATH + ".ray-step", 0.2D);
        if (step <= 0.0D) {
            step = 0.2D;
        }

        Location lastSafe = from.clone();
        boolean hitWall = false;

        for (double distance = step; distance <= movementLength; distance += step) {
            Location sample = from.clone().add(direction.clone().multiply(distance));
            if (!isLocationPassableForPlayer(sample)) {
                hitWall = true;
                break;
            }
            lastSafe = sample;
        }

        if (!hitWall) {
            return;
        }

        Location adjustedDestination = lastSafe.clone();
        adjustedDestination.setYaw(to.getYaw());
        adjustedDestination.setPitch(to.getPitch());
        event.setTo(adjustedDestination);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplodeHigh(EntityExplodeEvent event) {
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityExplodeMonitor(EntityExplodeEvent event) {
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplodeHigh(BlockExplodeEvent event) {
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockExplodeMonitor(BlockExplodeEvent event) {
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    private void preventExplosionBlockDamage(List<Block> blocks) {
        if (!blocks.isEmpty()) {
            blocks.clear();
        }
    }

    private boolean isWhitelistPickaxe(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_PICKAXE")) {
            return false;
        }

        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) {
            return false;
        }

        if (containsWhitelistMarker(itemMeta.getDisplayName())) {
            return true;
        }

        if (!itemMeta.hasLore() || itemMeta.getLore() == null) {
            return false;
        }

        for (String loreLine : itemMeta.getLore()) {
            if (containsWhitelistMarker(loreLine)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsWhitelistMarker(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        String normalizedText = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text));
        return normalizedText != null && normalizedText.contains(WHITELIST_PICKAXE_MARKER);
    }

    private Set<String> getWhitelistedBlockNames() {
        return getConfig().getStringList(WHITELIST_PICKAXE_CONFIG_PATH).stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private void saveWhitelistedBlockNames(Set<String> whitelist) {
        List<String> sortedWhitelist = whitelist.stream()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        getConfig().set(WHITELIST_PICKAXE_CONFIG_PATH, sortedWhitelist);
        saveConfig();
    }

    private boolean setupWorldGuardSupport() {
        if (!getConfig().getBoolean(SPAWN_PROTECTION_CONFIG_PATH + ".enabled", true)) {
            getLogger().info("Spawn movement protection is disabled in config.");
            return false;
        }

        Plugin worldGuardPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (!(worldGuardPlugin instanceof WorldGuardPlugin)) {
            getLogger().warning("WorldGuard not found; spawn movement protection is unavailable.");
            return false;
        }

        String regionId = getProtectedRegionId();
        if (regionId.isEmpty()) {
            getLogger().warning("Spawn movement protection region id is empty; protection is disabled.");
            return false;
        }

        getLogger().info("Spawn movement protection enabled for WorldGuard region '" + regionId + "'.");
        return true;
    }

    private boolean shouldBlockExternalMovement(Location location) {
        return worldGuardAvailable && isProtectedSpawnRegion(location);
    }

    private boolean isProtectedSpawnRegion(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
        if (regionManager == null) {
            return false;
        }

        ApplicableRegionSet applicableRegions = regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(location));
        String protectedRegionId = getProtectedRegionId();
        return applicableRegions.getRegions().stream()
                .anyMatch(region -> region.getId().equalsIgnoreCase(protectedRegionId));
    }

    private String getProtectedRegionId() {
        return getConfig().getString(SPAWN_PROTECTION_CONFIG_PATH + ".region-id", "spawn").trim();
    }

    private boolean isLocationPassableForPlayer(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        Block feetBlock = location.getBlock();
        Block headBlock = location.clone().add(0.0D, 1.0D, 0.0D).getBlock();
        return feetBlock.isPassable() && headBlock.isPassable();
    }

    private static final class PacketCounter {
        private int windowClicksThisSecond;
        private long lastLogMillis;
        private long lastSeenMillis;
    }

    private static final class BossTimerPlaceholder extends PlaceholderExpansion {
        private final LagFixPlugin plugin;

        private BossTimerPlaceholder(LagFixPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "boss";
        }

        @Override
        public String getAuthor() {
            return String.join(", ", plugin.getDescription().getAuthors().isEmpty()
                    ? List.of("OpenAI")
                    : plugin.getDescription().getAuthors());
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            if (!"timer".equalsIgnoreCase(params)) {
                return null;
            }
            return plugin.formatRemainingTime();
        }
    }
}
