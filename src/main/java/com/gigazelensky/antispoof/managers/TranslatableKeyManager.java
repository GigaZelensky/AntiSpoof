
package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Probes clients by opening a fake sign with translatable components and
 * analysing the sign update response to detect mod translations.
 */
public class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin plugin;
    private final ConfigManager cfg;
    private final DetectionManager detectionManager;
    private final Map<UUID, Long> lastProbe = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.cfg = cfg;
    }

    /**
     * Registers Bukkit and packet listeners.
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int delay = cfg.getTranslatableFirstDelay();
        Bukkit.getScheduler().runTaskLater(plugin, () -> probe(player), delay);
    }

    /**
     * Actively probes a player for translatable‑key responses.
     */
    public void probe(Player player) {
        if (!cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        long last = lastProbe.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cfg.getTranslatableCooldown()) return;
        lastProbe.put(player.getUniqueId(), now);

        // Build sign lines
        Map<String,String> testKeys = cfg.getTranslatableTestKeys();
        if (testKeys.isEmpty()) return;

        List<String> keys = new ArrayList<>(testKeys.keySet());
        while (keys.size() < 4) keys.add(keys.get(0)); // pad

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = keys.get(i);
        }

        // Use a block at y = -64 (void) so it never collides with the world
        Location loc = player.getLocation().clone();
        loc.setY(-64);
        Block block = loc.getBlock();
        block.setType(Material.SIGN_POST, false);
        BlockState state = block.getState();
        if (state instanceof Sign sign) {
            for (int i = 0; i < 4; i++) {
                sign.setLine(i, lines[i]);
            }
            sign.update(false, false);
        }

        // Send open sign packet only to player
        WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), true);
        com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(player, open);

        // Schedule GUI close
        int ticksVisible = cfg.getTranslatableGuiVisibleTicks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.closeInventory(), ticksVisible);

    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.UPDATE_SIGN)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
        String[] lines;
try {
    java.lang.reflect.Method m = wrapper.getClass().getMethod("getLines");
    lines = (String[]) m.invoke(wrapper);
} catch (ReflectiveOperationException ex) {
    lines = new String[0];
}

        Map<String,String> testKeys = cfg.getTranslatableTestKeys();
        if (testKeys.isEmpty()) return;

        // Determine translation results
        boolean anyTranslated = false;
        Set<String> requiredMissing = new HashSet<>();

        int i = 0;
        for (String key : testKeys.keySet()) {
            if (i >= lines.length) break;
            String originalKey = key;
            String returned = lines[i];
            if (!returned.equals(originalKey)) {
                anyTranslated = true;
                if (testKeys.containsKey(originalKey)) {
                    detectionManager.handleTranslatable(player, TranslatableEventType.TRANSLATED, testKeys.get(originalKey));
                }
            }
            i++;
        }

        // Check required keys
        for (String required : cfg.getTranslatableRequiredKeys()) {
            int index = new ArrayList<>(testKeys.keySet()).indexOf(required);
            if (index >= 0 && index < lines.length) {
                if (lines[index].equals(required)) {
                    requiredMissing.add(required);
                }
            } else {
                requiredMissing.add(required);
            }
        }

        for (String miss : requiredMissing) {
            String label = testKeys.getOrDefault(miss, miss);
            detectionManager.handleTranslatable(player, TranslatableEventType.REQUIRED_MISS, label);
        }

        if (!anyTranslated) {
            detectionManager.handleTranslatable(player, TranslatableEventType.ZERO, "-");
        }
    }
}