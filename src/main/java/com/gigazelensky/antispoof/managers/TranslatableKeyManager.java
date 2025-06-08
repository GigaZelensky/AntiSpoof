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

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getEventManager().registerListener(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay()
        );
    }

    /** Probe runs only if the *server* is 1.20+; otherwise it returns. */
    public void probe(Player player) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        if (getServerMinor() < 20) return;                    // backend too old

        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(player.getUniqueId(), 0L)
                < cfg.getTranslatableCooldown()) return;
        lastProbe.put(player.getUniqueId(), now);

        Map<String,String> test = cfg.getTranslatableTestKeys();
        if (test.isEmpty()) return;

        List<String> keys = new ArrayList<>(test.keySet());
        while (keys.size() < 4) keys.add(keys.get(0));        // pad

        // Pick a sign material that exists on the running server
        Material signMat = getMaterial("OAK_SIGN", "SIGN_POST", "SIGN");
        if (signMat == null) return;                          // should never happen

        Location loc = player.getLocation().clone();
        loc.setY(-64);                                        // out of sight
        Block block = loc.getBlock();
        Material oldType = block.getType();
        block.setType(signMat, false);

        BlockState st = block.getState();
        if (st instanceof Sign sign) {
            for (int i = 0; i < 4; i++)
                sign.setLine(i, "{\"translate\":\"" + keys.get(i) + "\"}");
            sign.update(true, false);
        }

        WrapperPlayServerOpenSignEditor open =
                new WrapperPlayServerOpenSignEditor(
                        new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                        false);
        com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager().sendPacket(player, open);

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> { player.closeInventory(); block.setType(oldType, false); },
                cfg.getTranslatableGuiVisibleTicks()
        );
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent ev) {
        if (ev.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player) ev.getPlayer();
        String[] lines;
        try {
            lines = (String[]) WrapperPlayClientUpdateSign.class
                    .getMethod("getLines").invoke(new WrapperPlayClientUpdateSign(ev));
        } catch (Exception ex) { return; }

        Map<String,String> tests = cfg.getTranslatableTestKeys();
        if (tests.isEmpty()) return;

        boolean translated = false;
        int idx = 0;
        for (String key : tests.keySet()) {
            if (idx >= lines.length) break;
            if (!lines[idx].equals(key)) {
                translated = true;
                detectionManager.handleTranslatable(
                        p, TranslatableEventType.TRANSLATED, tests.get(key));
            }
            idx++;
        }
        if (!translated)
            detectionManager.handleTranslatable(p, TranslatableEventType.ZERO, "-");
    }

    /* Helpers */

    private int getServerMinor() {
        String[] split = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return split.length >= 2 ? Integer.parseInt(split[1]) : 0;
    }

    private Material getMaterial(String... candidates) {
        for (String s : candidates) {
            try { return Material.valueOf(s); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
}