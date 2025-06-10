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
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
        String[] sentLines = null;
        BukkitTask timeoutTask = null;
        long debugStart = 0L;
        org.bukkit.command.CommandSender debugSender = null;
        int retriesLeft = 0;

        // --- FIX: Add fields for unique ID and block restoration ---
        String probeId;
        Vector3i probePos;
        Material originalMaterial;
        byte originalData;
        boolean hasOriginalBlock = false;

        Probe(Map<String, String> src) {
            this.iterator = src.entrySet().iterator();
        }

        void cancelTimeout() {
            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception e) {
                    // Ignore
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
    private static final int RESPONSE_TIMEOUT_TICKS = 40; // Increased timeout slightly for more reliability

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
            restoreOriginalBlock(e.getPlayer(), probe); // Failsafe restore on quit
        }
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    private void startProbe(Player p, int retries, boolean ignoreCooldown) {
        if (p == null || !p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;
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
        Probe probe = new Probe(keys);
        probe.retriesLeft = retries;
        probes.put(p.getUniqueId(), probe);
        sendNextKey(p, probe);
    }

    public void sendKeybind(Player p, String key, org.bukkit.command.CommandSender sender) {
        if (p == null || !p.isOnline()) return;
        Probe probe = new Probe(Collections.singletonMap(key, key));
        probe.currentKey = key;
        probe.debugStart = System.currentTimeMillis();
        probe.debugSender = sender;
        probes.put(p.getUniqueId(), probe);
        sendNextKey(p, probe);
    }

    private void sendNextKey(Player p, Probe probe) {
        if (probe == null || !p.isOnline()) {
            if (p != null) probes.remove(p.getUniqueId());
            return;
        }

        if (probe.debugSender != null && probe.currentKey != null) {
           // For debug command, skip iterator logic.
        } else if (!probe.iterator.hasNext()) {
            finishProbe(p, probe);
            return;
        } else {
            Map.Entry<String, String> entry = probe.iterator.next();
            probe.currentKey = entry.getKey();
        }

        probe.probeId = UUID.randomUUID().toString().substring(0, 8);
        Location playerLoc = p.getLocation();
        int y = Math.min(p.getWorld().getMaxHeight() - 2, playerLoc.getBlockY() + 50);
        probe.probePos = new Vector3i(playerLoc.getBlockX(), y, playerLoc.getBlockZ());

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !probes.containsKey(p.getUniqueId())) return;
            Location probeLocation = new Location(p.getWorld(), probe.probePos.getX(), probe.probePos.getY(), probe.probePos.getZ());
            org.bukkit.block.Block block = probeLocation.getBlock();
            probe.originalMaterial = block.getType();
            //noinspection deprecation
            probe.originalData = block.getData();
            probe.hasOriginalBlock = true;
            
            sendSignProbe(p, probe);

            probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (probes.get(p.getUniqueId()) != probe) return; // Ignore timeout for old probes

                if (cfg.isDebugMode()) {
                    plugin.getLogger().warning("[Debug] Timed out waiting for response on key '" + probe.currentKey + "' from " + p.getName() + ". Moving to next key.");
                }
                
                restoreOriginalBlock(p, probe); // Restore block on timeout
                
                if (probe.debugSender != null) {
                    probe.debugSender.sendMessage(p.getName() + " | timeout");
                    probes.remove(p.getUniqueId());
                } else {
                    scheduleNext(p, probe);
                }
            }, RESPONSE_TIMEOUT_TICKS);
        });
    }

    private void sendSignProbe(Player target, Probe probe) {
        Vector3i pos = probe.probePos;
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        probe.sentLines = new String[4];

        // --- FIX: Embed the unique ID into the sign text ---
        String probeIdJson = "{\"text\":\"" + probe.probeId + "\"}";

        if (modern) {
            NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
            probe.sentLines[0] = "{\"translate\":\"" + probe.currentKey + "\"}";
            probe.sentLines[1] = "{\"text\":\"\"}";
            probe.sentLines[2] = "{\"text\":\"\"}";
            probe.sentLines[3] = probeIdJson;
            for (String line : probe.sentLines) messages.addTag(new NBTString(line));
            NBTCompound front = new NBTCompound();
            front.setTag("messages", messages);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte) 0));
            nbt.setTag("front_text", front);
        } else {
            probe.sentLines[0] = "{\"translate\":\"" + probe.currentKey + "\"}";
            probe.sentLines[1] = "";
            probe.sentLines[2] = "";
            probe.sentLines[3] = probe.probeId; // For legacy, just send raw text.
            nbt.setTag("Text1", new NBTString(probe.sentLines[0]));
            nbt.setTag("Text2", new NBTString(probe.sentLines[1]));
            nbt.setTag("Text3", new NBTString(probe.sentLines[2]));
            nbt.setTag("Text4", new NBTString(probe.sentLines[3]));
        }

        WrappedBlockState signState = WrappedBlockState.getByString("minecraft:oak_sign");
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, modern));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKey == null) return;
        
        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(e);
        String[] receivedLines = packet.getTextLines();

        // --- FIX: The core of the fix. Check for our unique ID. ---
        if (receivedLines.length < 4 || !receivedLines[3].equals(probe.probeId)) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Ignored stray UPDATE_SIGN from " + p.getName() + ". Expected ID: '" + probe.probeId + "', but got a packet for a different/old sign.");
            }
            return;
        }

        probe.cancelTimeout();
        restoreOriginalBlock(p, probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info(
                    "[Debug] Received UPDATE_SIGN from " + p.getName() + " for key '" + probe.currentKey + "'. " +
                            "Line 1 Sent: '" + probe.sentLines[0] + "', " +
                            "Line 1 Received: '" + (receivedLines.length > 0 ? receivedLines[0] : "EMPTY") + "'"
            );
        }

        if (receivedLines.length > 0) {
            String receivedLine = receivedLines[0];
            boolean translated = !receivedLine.isEmpty() &&
                                 !receivedLine.equals(probe.sentLines[0]) &&
                                 !receivedLine.equals(probe.currentKey);
            if (translated) {
                probe.translated.add(probe.currentKey);
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, probe.currentKey);
                if (cfg.isDebugMode()) {
                    String label = cfg.getTranslatableModConfig(probe.currentKey).getLabel();
                    plugin.getLogger().info("[Debug] Player " + p.getName() + " translated key '" + probe.currentKey + "' (" + label + ") to: '" + receivedLine + "'");
                }
            } else if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Player " + p.getName() + " did not translate key '" + probe.currentKey + "'");
            }

            if (probe.debugSender != null) {
                long time = System.currentTimeMillis() - probe.debugStart;
                probe.debugSender.sendMessage(p.getName() + " | result:\"" + receivedLine + "\" time=" + time + "ms");
                probes.remove(p.getUniqueId());
                return;
            }
        }
        
        scheduleNext(p, probe);
    }
    
    private void finishProbe(Player p, Probe probe) {
        probes.remove(p.getUniqueId());

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
            Bukkit.getScheduler().runTaskLater(plugin, () -> startProbe(p, remaining, true), cfg.getTranslatableRetryInterval());
        }
    }

    private void scheduleNext(Player p, Probe probe) {
        if (probe == null || !probes.containsValue(probe)) return; // Don't schedule if probe is already finished
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendNextKey(p, probe), cfg.getTranslatableKeyDelay());
    }

    private void restoreOriginalBlock(Player p, Probe probe) {
        if (probe != null && probe.hasOriginalBlock && p != null && p.isOnline()) {
            Location loc = new Location(p.getWorld(), probe.probePos.getX(), probe.probePos.getY(), probe.probePos.getZ());
            //noinspection deprecation
            p.sendBlockChange(loc, probe.originalMaterial, probe.originalData);
            probe.hasOriginalBlock = false;
        }
    }
}