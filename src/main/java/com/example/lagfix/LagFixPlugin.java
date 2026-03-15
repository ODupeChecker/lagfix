package com.example.lagfix;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class LagFixPlugin extends JavaPlugin {

    private final HashMap<UUID, PacketCounter> packetCounters = new HashMap<>();
    private ProtocolManager protocolManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().severe("ProtocolLib is required but not found. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        registerWindowClickListener();
        startCounterResetTask();

        getLogger().info("LagFix enabled.");
    }

    @Override
    public void onDisable() {
        if (protocolManager != null) {
            protocolManager.removePacketListeners(this);
        }
        synchronized (packetCounters) {
            packetCounters.clear();
        }
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

    private static final class PacketCounter {
        private int windowClicksThisSecond;
        private long lastLogMillis;
        private long lastSeenMillis;
    }
}
