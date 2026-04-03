package com.example.lagfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
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
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
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
    private static final String BLOCK_PERSIST_REGIONS_CONFIG_PATH = "block-persist.regions";
    private static final String BLOCK_PERSIST_BLOCKS_CONFIG_PATH = "block-persist.blocks";
    private static final Vector ZERO_VECTOR = new Vector(0, 0, 0);

    private final HashMap<UUID, PacketCounter> packetCounters = new HashMap<>();
    private final Map<String, PersistRegion> persistRegions = new HashMap<>();
    private final Map<BlockKey, PersistBlockData> persistentBlocks = new HashMap<>();
    private ProtocolManager protocolManager;
    private BossTimerPlaceholder bossTimerPlaceholder;
    private volatile long bossIntervalMillis;
    private volatile long nextBossSpawnMillis;
    private boolean worldGuardAvailable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBossTimerState();
        loadBlockPersistState();
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
        startBlockPersistSaveTask();
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
        saveBlockPersistState();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bosstimer")) {
            return handleBossTimerCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("whitelistpickaxe")) {
            return handleWhitelistPickaxeCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("blockpersist")) {
            return handleBlockPersistCommand(sender, args);
        }

        return false;
    }

    private boolean handleBlockPersistCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /blockpersist <create|list|delete>");
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use /blockpersist create.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /blockpersist create <name>");
                return true;
            }
            return createPersistRegion((Player) sender, args[1]);
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (persistRegions.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No persist regions exist.");
                return true;
            }

            sender.sendMessage(ChatColor.GREEN + "Persist regions:");
            for (PersistRegion region : persistRegions.values().stream()
                    .sorted((left, right) -> left.name.compareToIgnoreCase(right.name))
                    .collect(Collectors.toList())) {
                sender.sendMessage(ChatColor.AQUA + "- " + region.name + ChatColor.GRAY + " @ "
                        + region.worldName + " "
                        + region.minX + "," + region.minY + "," + region.minZ);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /blockpersist delete <name>");
                return true;
            }

            String regionKey = args[1].toLowerCase(Locale.ROOT);
            PersistRegion removed = persistRegions.remove(regionKey);
            if (removed == null) {
                sender.sendMessage(ChatColor.RED + "Persist region not found: " + args[1]);
                return true;
            }

            persistentBlocks.entrySet().removeIf(entry -> entry.getValue().regionName.equalsIgnoreCase(removed.name));
            saveBlockPersistState();
            sender.sendMessage(ChatColor.GREEN + "Deleted persist region " + removed.name + ".");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /blockpersist <create|list|delete>");
        return true;
    }

    private boolean createPersistRegion(Player player, String rawName) {
        String regionName = rawName.trim();
        if (regionName.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Region name cannot be empty.");
            return true;
        }
        String regionKey = regionName.toLowerCase(Locale.ROOT);
        if (persistRegions.containsKey(regionKey)) {
            player.sendMessage(ChatColor.RED + "A persist region with that name already exists.");
            return true;
        }

        BukkitPlayer worldEditPlayer = BukkitAdapter.adapt(player);
        Region selection;
        try {
            selection = WorldEdit.getInstance().getSessionManager().get(worldEditPlayer)
                    .getSelection(worldEditPlayer.getWorld());
        } catch (IncompleteRegionException exception) {
            player.sendMessage(ChatColor.RED + "You must make a full WorldEdit selection first.");
            return true;
        }

        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        PersistRegion region = new PersistRegion(regionName, player.getWorld().getName(),
                min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                max.getBlockX(), max.getBlockY(), max.getBlockZ());
        persistRegions.put(regionKey, region);
        captureRegionBlocks(region);
        saveBlockPersistState();
        player.sendMessage(ChatColor.GREEN + "Persist region " + region.name + " created.");
        return true;
    }

    private void captureRegionBlocks(PersistRegion region) {
        org.bukkit.World world = Bukkit.getWorld(region.worldName);
        if (world == null) {
            return;
        }

        for (int x = region.minX; x <= region.maxX; x++) {
            for (int y = region.minY; y <= region.maxY; y++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    BlockKey key = new BlockKey(world.getName(), x, y, z);
                    persistentBlocks.put(key, PersistBlockData.fromBlock(region.name, block));
                }
            }
        }
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

    private boolean handleWhitelistPickaxeCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can use this command.");
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /whitelistpickaxe <add|remove> <block>");
            return true;
        }

        Material material = Material.matchMaterial(args[1]);
        if (material == null || !material.isBlock()) {
            sender.sendMessage(ChatColor.RED + "Unknown block: " + args[1]);
            return true;
        }

        Set<String> whitelist = getWhitelistedBlockNames();
        String materialName = material.name();

        if (args[0].equalsIgnoreCase("add")) {
            if (!whitelist.add(materialName)) {
                sender.sendMessage(ChatColor.YELLOW + materialName + " is already whitelisted for whitelist pickaxes.");
                return true;
            }

            saveWhitelistedBlockNames(whitelist);
            sender.sendMessage(ChatColor.GREEN + "Added " + materialName + " to the whitelist pickaxe block list.");
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            if (!whitelist.remove(materialName)) {
                sender.sendMessage(ChatColor.YELLOW + materialName + " is not in the whitelist pickaxe block list.");
                return true;
            }

            saveWhitelistedBlockNames(whitelist);
            sender.sendMessage(ChatColor.GREEN + "Removed " + materialName + " from the whitelist pickaxe block list.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /whitelistpickaxe <add|remove> <block>");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!worldGuardAvailable) {
            return;
        }

        Player player = event.getPlayer();
        if (!isInRegion(player.getLocation(), "bossarea")) {
            return;
        }

        if (!isOffhandRestrictedWeapon(event.getMainHandItem()) && !isOffhandRestrictedWeapon(event.getOffHandItem())) {
            return;
        }

        event.setCancelled(true);
    }

    private boolean isOffhandRestrictedWeapon(ItemStack item) {
        if (item == null) {
            return false;
        }

        Material material = item.getType();
        return Tag.ITEMS_SWORDS.isTagged(material)
                || Tag.ITEMS_AXES.isTagged(material)
                || material == Material.CROSSBOW;
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
        handlePersistBlockBreak(event.getPlayer(), event.getBlock());

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
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().isOp()) {
            return;
        }

        PersistRegion region = getPersistRegion(event.getBlockPlaced().getLocation());
        if (region == null) {
            return;
        }

        Block block = event.getBlockPlaced();
        BlockKey key = BlockKey.fromLocation(block.getLocation());
        persistentBlocks.put(key, PersistBlockData.fromBlock(region.name, block));
        saveBlockPersistState();
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
        handlePersistentBlocksPotentiallyBroken(event.blockList());
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityExplodeMonitor(EntityExplodeEvent event) {
        handlePersistentBlocksPotentiallyBroken(event.blockList());
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplodeHigh(BlockExplodeEvent event) {
        handlePersistentBlocksPotentiallyBroken(event.blockList());
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockExplodeMonitor(BlockExplodeEvent event) {
        handlePersistentBlocksPotentiallyBroken(event.blockList());
        preventExplosionBlockDamage(event.blockList());
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockBurn(BlockBurnEvent event) {
        restoreIfPersistent(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockFade(BlockFadeEvent event) {
        restoreIfPersistent(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockSpread(BlockSpreadEvent event) {
        restoreIfPersistent(event.getSource());
        restoreIfPersistent(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        restoreIfPersistent(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePersistentBlocksPotentiallyBroken(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePersistentBlocksPotentiallyBroken(event.getBlocks());
    }

    private void preventExplosionBlockDamage(List<Block> blocks) {
        if (!blocks.isEmpty()) {
            blocks.clear();
        }
    }

    private void handlePersistBlockBreak(Player player, Block block) {
        BlockKey key = BlockKey.fromLocation(block.getLocation());
        PersistBlockData existing = persistentBlocks.get(key);
        if (existing == null) {
            return;
        }

        if (player.isOp()) {
            persistentBlocks.remove(key);
            saveBlockPersistState();
            player.sendMessage(ChatColor.YELLOW + "persist block broken - will not persist");
            return;
        }

        restoreIfPersistent(block);
    }

    private void handlePersistentBlocksPotentiallyBroken(List<Block> blocks) {
        for (Block block : blocks) {
            restoreIfPersistent(block);
        }
    }

    private void restoreIfPersistent(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }

        BlockKey key = BlockKey.fromLocation(block.getLocation());
        PersistBlockData data = persistentBlocks.get(key);
        if (data == null) {
            return;
        }

        if (block.getType() == data.material && block.getBlockData().getAsString().equals(data.blockData)) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            Block currentBlock = block.getWorld().getBlockAt(block.getX(), block.getY(), block.getZ());
            currentBlock.setType(data.material, false);
            currentBlock.setBlockData(Bukkit.createBlockData(data.blockData), false);
        });
    }

    private PersistRegion getPersistRegion(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        for (PersistRegion region : persistRegions.values()) {
            if (region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    private synchronized void loadBlockPersistState() {
        persistRegions.clear();
        persistentBlocks.clear();

        ConfigurationSection regionsSection = getConfig().getConfigurationSection(BLOCK_PERSIST_REGIONS_CONFIG_PATH);
        if (regionsSection != null) {
            for (String regionKey : regionsSection.getKeys(false)) {
                ConfigurationSection section = regionsSection.getConfigurationSection(regionKey);
                if (section == null) {
                    continue;
                }

                PersistRegion region = PersistRegion.fromConfig(regionKey, section);
                if (region != null) {
                    persistRegions.put(regionKey.toLowerCase(Locale.ROOT), region);
                }
            }
        }

        ConfigurationSection blocksSection = getConfig().getConfigurationSection(BLOCK_PERSIST_BLOCKS_CONFIG_PATH);
        if (blocksSection != null) {
            for (String key : blocksSection.getKeys(false)) {
                ConfigurationSection section = blocksSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }

                PersistBlockData blockData = PersistBlockData.fromConfig(section);
                BlockKey blockKey = BlockKey.fromConfig(section);
                if (blockData == null || blockKey == null) {
                    continue;
                }

                if (persistRegions.containsKey(blockData.regionName.toLowerCase(Locale.ROOT))) {
                    persistentBlocks.put(blockKey, blockData);
                }
            }
        }
    }

    private synchronized void saveBlockPersistState() {
        getConfig().set(BLOCK_PERSIST_REGIONS_CONFIG_PATH, null);
        getConfig().set(BLOCK_PERSIST_BLOCKS_CONFIG_PATH, null);

        for (PersistRegion region : persistRegions.values()) {
            String basePath = BLOCK_PERSIST_REGIONS_CONFIG_PATH + "." + region.name.toLowerCase(Locale.ROOT);
            getConfig().set(basePath + ".name", region.name);
            getConfig().set(basePath + ".world", region.worldName);
            getConfig().set(basePath + ".min.x", region.minX);
            getConfig().set(basePath + ".min.y", region.minY);
            getConfig().set(basePath + ".min.z", region.minZ);
            getConfig().set(basePath + ".max.x", region.maxX);
            getConfig().set(basePath + ".max.y", region.maxY);
            getConfig().set(basePath + ".max.z", region.maxZ);
        }

        int index = 0;
        for (Map.Entry<BlockKey, PersistBlockData> entry : persistentBlocks.entrySet()) {
            BlockKey key = entry.getKey();
            PersistBlockData data = entry.getValue();
            String basePath = BLOCK_PERSIST_BLOCKS_CONFIG_PATH + "." + index;
            getConfig().set(basePath + ".region", data.regionName);
            getConfig().set(basePath + ".world", key.worldName);
            getConfig().set(basePath + ".x", key.x);
            getConfig().set(basePath + ".y", key.y);
            getConfig().set(basePath + ".z", key.z);
            getConfig().set(basePath + ".material", data.material.name());
            getConfig().set(basePath + ".block-data", data.blockData);
            index++;
        }

        saveConfig();
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
        Plugin worldGuardPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (!(worldGuardPlugin instanceof WorldGuardPlugin)) {
            getLogger().warning("WorldGuard not found; WorldGuard-based protections are unavailable.");
            return false;
        }
        getLogger().info("WorldGuard support enabled.");
        return true;
    }

    private boolean shouldBlockExternalMovement(Location location) {
        if (!worldGuardAvailable) {
            return false;
        }
        if (!getConfig().getBoolean(SPAWN_PROTECTION_CONFIG_PATH + ".enabled", true)) {
            return false;
        }

        String protectedRegionId = getProtectedRegionId();
        if (protectedRegionId.isEmpty()) {
            return false;
        }

        return isInRegion(location, protectedRegionId);
    }

    private boolean isInRegion(Location location, String regionId) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
        if (regionManager == null) {
            return false;
        }

        ApplicableRegionSet applicableRegions = regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(location));
        return applicableRegions.getRegions().stream()
                .anyMatch(region -> region.getId().equalsIgnoreCase(regionId));
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

    private static final class PersistRegion {
        private final String name;
        private final String worldName;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;

        private PersistRegion(String name, String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.name = name;
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private boolean contains(Location location) {
            return location.getWorld() != null
                    && location.getWorld().getName().equals(worldName)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }

        private static PersistRegion fromConfig(String fallbackName, ConfigurationSection section) {
            String world = section.getString("world");
            if (world == null || world.isBlank()) {
                return null;
            }
            String configuredName = section.getString("name", fallbackName);
            int minX = section.getInt("min.x");
            int minY = section.getInt("min.y");
            int minZ = section.getInt("min.z");
            int maxX = section.getInt("max.x");
            int maxY = section.getInt("max.y");
            int maxZ = section.getInt("max.z");
            return new PersistRegion(configuredName, world, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class BlockKey {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static BlockKey fromLocation(Location location) {
            return new BlockKey(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private static BlockKey fromConfig(ConfigurationSection section) {
            String world = section.getString("world");
            if (world == null || world.isBlank()) {
                return null;
            }
            return new BlockKey(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BlockKey)) {
                return false;
            }
            BlockKey key = (BlockKey) other;
            return x == key.x && y == key.y && z == key.z && worldName.equals(key.worldName);
        }

        @Override
        public int hashCode() {
            return worldName.hashCode() * 31 * 31 * 31 + x * 31 * 31 + y * 31 + z;
        }
    }

    private static final class PersistBlockData {
        private final String regionName;
        private final Material material;
        private final String blockData;

        private PersistBlockData(String regionName, Material material, String blockData) {
            this.regionName = regionName;
            this.material = material;
            this.blockData = blockData;
        }

        private static PersistBlockData fromBlock(String regionName, Block block) {
            return new PersistBlockData(regionName, block.getType(), block.getBlockData().getAsString());
        }

        private static PersistBlockData fromConfig(ConfigurationSection section) {
            String regionName = section.getString("region");
            String materialName = section.getString("material");
            String blockData = section.getString("block-data");
            if (regionName == null || materialName == null || blockData == null) {
                return null;
            }

            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                return null;
            }

            return new PersistBlockData(regionName, material, blockData);
        }
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
