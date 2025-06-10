package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    // Probe class to hold the state of the check for each player
    private static final class Probe {
        final Iterator<Map.Entry<String, String>> iterator;
        final Set<String> translated = new HashSet<>();
        String currentKey = null;
        String[] sentLines = null; // Store the original JSON lines sent to the client
        BukkitTask timeoutTask = null; // To handle unresponsive clients

        Probe(Map<String, String> src) {
            this.iterator = src.entrySet().iterator();
        }

        // Helper to safely cancel the timeout task
        void cancelTimeout() {
            if (timeoutTask != null) {
                // This try-catch block is a version-safe way to cancel a task
                // without checking isCancelled(), which doesn't exist on older APIs.
                try {
                    timeoutTask.cancel();
                } catch (Exception e) {
                    // Ignore exceptions, which can happen if the task is already cancelled or finished.
                }
                timeoutTask = null;
            }
        }
    }

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;
    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private static final int RESPONSE_TIMEOUT_TICKS = 20; // 1 second timeout for each key

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detect,
                                  ConfigManager cfg) {
        this.plugin = plugin;
        this.detect = detect;
        this.cfg = cfg;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> startProbe(e.getPlayer()),
                cfg.getTranslatableFirstDelay()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // Clean up resources for the player who left to prevent memory leaks
        Probe probe = probes.remove(e.getPlayer().getUniqueId());
        if (probe != null) {
            probe.cancelTimeout();
        }
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    private void startProbe(Player p) {
        if (p == null || !p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        // Check cooldown to prevent spamming a player with checks
        long now = System.currentTimeMillis();
        long cooldownTime = (long) cfg.getTranslatableCooldown() * 1000L;
        if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cooldownTime) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Translatable key check for " + p.getName() + " is on cooldown.");
            }
            return;
        }
        cooldown.put(p.getUniqueId(), now);

        // Get keys to test from the configuration
        Map<String, String> keys = cfg.getTranslatableModsWithLabels();
        if (keys.isEmpty()) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] No translatable keys configured to check.");
            }
            return;
        }

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting translatable key probe for " + p.getName() + " with " + keys.size() + " keys.");
        }

        Probe probe = new Probe(keys);
        probes.put(p.getUniqueId(), probe);
        // Start the sequential check
        sendNextKey(p, probe);
    }

    /**
     * This is the core of the new sequential logic. It sends one key, sets a timeout,
     * and relies on the packet listener or the timeout to call it again for the next key.
     */
    private void sendNextKey(Player p, Probe probe) {
        // Ensure the probe is still valid
        if (probe == null || !p.isOnline()) {
            if (probe != null) {
                probe.cancelTimeout();
                probes.remove(p.getUniqueId());
            }
            return;
        }

        // If we've run out of keys, finish the process.
        if (!probe.iterator.hasNext()) {
            finishProbe(p, probe);
            probes.remove(p.getUniqueId());
            return;
        }

        // Send the next probe from the list.
        Map.Entry<String, String> entry = probe.iterator.next();
        probe.currentKey = entry.getKey();
        sendSignProbe(p, probe);

        // Schedule a timeout. If we don't get a response, this will trigger the next key.
        // This prevents the check from stalling if a client doesn't send an UpdateSign packet.
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cfg.isDebugMode()) {
                plugin.getLogger().warning("[Debug] Timed out waiting for response on key '" + probe.currentKey + "' from " + p.getName() + ". Moving to next key.");
            }
            probe.timeoutTask = null; // Mark task as complete
            sendNextKey(p, probe);    // Continue the sequence
        }, RESPONSE_TIMEOUT_TICKS);
    }

    private void finishProbe(Player p, Probe probe) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Finished translatable key probe for " + p.getName() + ". Translated keys: " + probe.translated);
        }

        List<String> requiredKeys = cfg.getTranslatableRequiredKeys();
        if (requiredKeys == null || requiredKeys.isEmpty()) return;

        for (String required : requiredKeys) {
            if (!probe.translated.contains(required)) {
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, required);
            }
        }
    }

    private void sendSignProbe(Player target, Probe probe) {
        String key = probe.currentKey;
        Vector3i pos = new Vector3i(
                target.getLocation().getBlockX(),
                Math.min(target.getWorld().getMaxHeight() - 2, target.getLocation().getBlockY() + 25),
                target.getLocation().getBlockZ());

        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        String[] sentLines = new String[4];

        if (modern) {
            NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
            sentLines[0] = "{\"translate\":\"" + key + "\"}";
            sentLines[1] = "{\"text\":\"\"}";
            sentLines[2] = "{\"text\":\"\"}";
            sentLines[3] = "{\"text\":\"\"}";
            for (String line : sentLines) messages.addTag(new NBTString(line));
            NBTCompound front = new NBTCompound();
            front.setTag("messages", messages);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte) 0));
            nbt.setTag("front_text", front);
        } else {
            sentLines[0] = "{\"translate\":\"" + key + "\"}";
            sentLines[1] = "";
            sentLines[2] = "";
            sentLines[3] = "";
            nbt.setTag("Text1", new NBTString(sentLines[0]));
            nbt.setTag("Text2", new NBTString(sentLines[1]));
            nbt.setTag("Text3", new NBTString(sentLines[2]));
            nbt.setTag("Text4", new NBTString(sentLines[3]));
        }

        probe.sentLines = sentLines;

        WrappedBlockState signState;
        try {
            signState = (WrappedBlockState) StateTypes.OAK_SIGN.getClass().getMethod("createBlockData").invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            signState = StateTypes.OAK_SIGN.createBlockState(cv);
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKey == null || probe.sentLines == null) return;

        // A response was received! Cancel the timeout task.
        probe.cancelTimeout();

        String[] receivedLines = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (receivedLines.length > 0) {
            String originalLineJson = probe.sentLines[0];
            String receivedLine = receivedLines[0];

            // The key comparison: if the received line is not empty and is different from the original JSON, it was translated.
            if (!receivedLine.isEmpty() && !receivedLine.equals(originalLineJson)) {
                probe.translated.add(probe.currentKey);
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, probe.currentKey);

                if (cfg.isDebugMode()) {
                    String label = cfg.getTranslatableModConfig(probe.currentKey).getLabel();
                    plugin.getLogger().info(
                            "[Debug] Player " + p.getName() + " translated key '" + probe.currentKey +
                                    "' (" + label + ") to: '" + receivedLine + "'"
                    );
                }
            } else {
                if (cfg.isDebugMode()) {
                    plugin.getLogger().info(
                            "[Debug] Player " + p.getName() + " did not translate key '" + probe.currentKey + "'"
                    );
                }
            }
        }

        // IMPORTANT: Trigger the check for the next key.
        sendNextKey(p, probe);
    }
}