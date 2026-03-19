package com.example.lagfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LagFixPlugin extends JavaPlugin implements Listener {

    private static final String BOSS_SPAWN_COMMAND = "mm mobs spawn HPC_GRANDWARDEN:1 1 worldo12,-11,67,-214,0,0";
    private static final long DEFAULT_BOSS_INTERVAL_MILLIS = 60L * 60L * 1000L;
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)h$", Pattern.CASE_INSENSITIVE);

    private final HashMap<UUID, PacketCounter> packetCounters = new HashMap<>();
    private ProtocolManager protocolManager;
    private BossTimerPlaceholder bossTimerPlaceholder;
    private volatile long bossIntervalMillis;
    private volatile long nextBossSpawnMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBossTimerState();

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
        if (!command.getName().equalsIgnoreCase("bosstimer")) {
            return false;
        }

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
