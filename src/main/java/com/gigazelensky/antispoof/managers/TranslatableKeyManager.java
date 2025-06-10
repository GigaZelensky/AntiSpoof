package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private static final class Probe {
        final Iterator<Map.Entry<String, String>> iterator;
        final Set<String> translated = new HashSet<>();
        String currentKey = null;
        BukkitTask timeoutTask = null;
        int retriesLeft = 0;

        // Fields for robust probing
        final Vector3i probePosition;
        int originalBlockId;
        String probeId;

        // Fields for debug command
        long debugStart = 0L;
        org.bukkit.command.CommandSender debugSender = null;

        Probe(Map<String, String> src, Vector3i probePosition) {
            this.iterator = src.entrySet().iterator();
            this.probePosition = probePosition;
        }

        void cancelTimeout() {
            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception e) {
                    // Ignored
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
    private static final int RESPONSE_TIMEOUT_TICKS = 40; // 2 seconds timeout
    private static final int SIGN_BLOCK_ENTITY_TYPE_ID = 9; // The integer ID for a sign's block entity in older versions

    public TranslatableKeyManager(AntiSpoofPlugin plugin, DetectionManager detect, ConfigManager cfg) {
        this.plugin = plugin;
        this.detect = detect;
        this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> startProbe(e.getPlayer(), cfg.getTranslatableRetryCount(), false), cfg.getTranslatableFirstDelay());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Probe probe = probes.remove(e.getPlayer().getUniqueId());
        if (probe != null) {
            probe.cancelTimeout();
        }
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    private void startProbe(Player p, int retries, boolean ignoreCooldown) {
        if (p == null || !p.isOnline() || !cfg.isTranslatableKeysEnabled() || probes.containsKey(p.getUniqueId())) return;

        long now = System.currentTimeMillis();
        long cooldownTime = (long) cfg.getTranslatableCooldown() * 1000L;
        if (!ignoreCooldown && now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cooldownTime) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Translatable key check for " + p.getName() + " is on cooldown.");
            }
            return;
        }
        if (!ignoreCooldown) {
            cooldown.put(p.getUniqueId(), now);
        }

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

        int y = Math.min(p.getWorld().getMaxHeight() - 2, p.getLocation().getBlockY() + 50);
        Vector3i probePos = new Vector3i(p.getLocation().getBlockX(), y, p.getLocation().getBlockZ());

        Probe probe = new Probe(keys, probePos);
        probe.retriesLeft = retries;
        probes.put(p.getUniqueId(), probe);

        sendNextKey(p, probe);
    }

    public void sendKeybind(Player p, String key, org.bukkit.command.CommandSender sender) {
        if (p == null || !p.isOnline()) return;
        if (probes.containsKey(p.getUniqueId())) {
            sender.sendMessage("A probe is already running for this player.");
            return;
        }

        int y = Math.min(p.getWorld().getMaxHeight() - 2, p.getLocation().getBlockY() + 50);
        Vector3i probePos = new Vector3i(p.getLocation().getBlockX(), y, p.getLocation().getBlockZ());

        Probe probe = new Probe(Collections.singletonMap(key, key), probePos);
        probe.debugSender = sender;
        probe.debugStart = System.currentTimeMillis();
        probes.put(p.getUniqueId(), probe);

        sendNextKey(p, probe);
    }

    private void sendNextKey(Player p, Probe probe) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> sendNextKey(p, probe));
            return;
        }

        if (probe == null || !p.isOnline()) {
            if (probe != null) {
                probe.cancelTimeout();
                probes.remove(p.getUniqueId());
            }
            return;
        }

        if (!probe.iterator.hasNext()) {
            finishProbe(p, probe);
            return;
        }

        Map.Entry<String, String> entry = probe.iterator.next();
        probe.currentKey = entry.getKey();
        probe.probeId = UUID.randomUUID().toString().substring(0, 8);

        // Get the original block using legacy methods for 1.8.8 compatibility
        Location probeLoc = new Location(p.getWorld(), probe.probePosition.getX(), probe.probePosition.getY(), probe.probePosition.getZ());
        Block originalBlock = p.getWorld().getBlockAt(probeLoc);

        // Suppress deprecation warning as this is required for 1.8.8
        @SuppressWarnings("deprecation")
        byte data = originalBlock.getData();
        @SuppressWarnings("deprecation")
        int materialId = originalBlock.getType().getId();

        int globalId = (materialId << 4) | (data & 0xF);
        probe.originalBlockId = globalId;

        sendSignProbe(p, probe);

        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> handleTimeout(p, probe, probe.probeId), RESPONSE_TIMEOUT_TICKS);
    }

    private void handleTimeout(Player p, Probe probe, String expectedProbeId) {
        if (probe == null || probe.probeId == null || !probe.probeId.equals(expectedProbeId)) {
            return; // Timeout for an old, already-handled request
        }

        probe.timeoutTask = null;

        if (probe.debugSender != null) {
            probe.debugSender.sendMessage(p.getName() + " | timed out for key '" + probe.currentKey + "'");
            finishProbe(p, probe);
            return;
        }

        if (cfg.isDebugMode()) {
            plugin.getLogger().warning("[Debug] Timed out waiting for response on key '" + probe.currentKey + "' from " + p.getName() + ". Moving to next key.");
        }

        restoreBlock(p, probe);
        sendNextKey(p, probe);
    }

    private void finishProbe(Player p, Probe probe) {
        probes.remove(p.getUniqueId());
        probe.cancelTimeout();
        restoreBlock(p, probe);

        if (probe.debugSender != null) return;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Finished translatable key probe for " + p.getName() + ". Translated keys: " + probe.translated);
        }

        List<String> requiredKeys = cfg.getTranslatableRequiredKeys();
        if (requiredKeys != null && !requiredKeys.isEmpty()) {
            for (String required : requiredKeys) {
                if (!probe.translated.contains(required)) {
                    detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, required);
                }
            }
        }

        if (probe.retriesLeft > 0) {
            int remaining = probe.retriesLeft - 1;
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Retrying probe for " + p.getName() + ". Retries left: " + remaining);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> startProbe(p, remaining, true), (long)cfg.getTranslatableRetryInterval() * 20L);
        }
    }

    private void restoreBlock(Player p, Probe probe) {
        if (probe != null && probe.probePosition != null && p != null && p.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerBlockChange(probe.probePosition, probe.originalBlockId));
        }
    }

    private void sendSignProbe(Player target, Probe probe) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));

        String probeIdJson = "{\"text\":\"" + probe.probeId + "\"}";

        if (modern) {
            NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
            messages.addTag(new NBTString("{\"translate\":\"" + probe.currentKey + "\"}"));
            messages.addTag(new NBTString("{\"text\":\"\"}"));
            messages.addTag(new NBTString("{\"text\":\"\"}"));
            messages.addTag(new NBTString(probeIdJson));

            NBTCompound front = new NBTCompound();
            front.setTag("messages", messages);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte) 0));
            nbt.setTag("front_text", front);
        } else {
            nbt.setTag("Text1", new NBTString("{\"translate\":\"" + probe.currentKey + "\"}"));
            nbt.setTag("Text2", new NBTString(""));
            nbt.setTag("Text3", new NBTString(""));
            nbt.setTag("Text4", new NBTString(probeIdJson));
        }

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState(cv);

        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(probe.probePosition, signState.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(probe.probePosition, SIGN_BLOCK_ENTITY_TYPE_ID, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(probe.probePosition, true));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKey == null) return;

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(e);
        String[] receivedLines = packet.getTextLines();

        String receivedId = (receivedLines.length > 3) ? receivedLines[3] : null;

        if (probe.probeId == null || !probe.probeId.equals(receivedId)) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().warning("[Debug] Mismatched or missing probe ID from " + p.getName() +
                        ". Expected: " + probe.probeId + ", Got: " + receivedId + ". This is likely a stray packet.");
            }
            return;
        }

        probe.cancelTimeout();
        restoreBlock(p, probe);

        String originalKey = probe.currentKey;
        String originalLineJson = "{\"translate\":\"" + originalKey + "\"}";
        String receivedLine = (receivedLines.length > 0) ? receivedLines[0] : "";

        boolean translated = !receivedLine.isEmpty() &&
                !receivedLine.equals(originalLineJson) &&
                !receivedLine.equals(originalKey);

        if (probe.debugSender != null) {
            long time = System.currentTimeMillis() - probe.debugStart;
            probe.debugSender.sendMessage(p.getName() + " | key: '" + originalKey + "' | result: \"" + receivedLine + "\" | translated: " + translated + " | time: " + time + "ms");
            finishProbe(p, probe);
            return;
        }

        if (translated) {
            probe.translated.add(originalKey);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, originalKey);
            if (cfg.isDebugMode()) {
                String label = cfg.getTranslatableModConfig(originalKey).getLabel();
                plugin.getLogger().info("[Debug] Player " + p.getName() + " translated key '" + originalKey + "' (" + label + ") to: '" + receivedLine + "'");
            }
        } else if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Player " + p.getName() + " did not translate key '" + originalKey + "'");
        }

        sendNextKey(p, probe);
    }
}