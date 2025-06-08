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

/** Detects mods by probing translation keys via a temporary sign. */
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

    /* --------------------------------------------------------------------- */
    /*  Join → schedule probe                                                */
    /* --------------------------------------------------------------------- */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay()
        );
    }

    /* --------------------------------------------------------------------- */
    /*  Probe (only runs if server ≥ 1.20)                                   */
    /* --------------------------------------------------------------------- */

    public void probe(Player player) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        if (getServerMinor() < 20) return;                             // backend too old

        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(player.getUniqueId(), 0L)
                < cfg.getTranslatableCooldown()) return;
        lastProbe.put(player.getUniqueId(), now);

        Map<String,String> tests = cfg.getTranslatableTestKeys();
        if (tests.isEmpty()) return;

        List<String> keys = new ArrayList<>(tests.keySet());
        while (keys.size() < 4) keys.add(keys.get(0));                // pad

        Material signMat = getMaterial("OAK_SIGN", "SIGN_POST", "SIGN");
        if (signMat == null) return;

        Location loc = player.getLocation().clone();
        loc.setY(-64);
        Block block = loc.getBlock();
        Material oldType = block.getType();
        block.setType(signMat, false);

        BlockState state = block.getState();
        if (state instanceof Sign sign) {
            for (int i = 0; i < 4; i++) {
                /* write Adventure component via reflection (works on 1.20+) */
                try {
                    Class<?> sideEnum  = Class.forName("org.bukkit.block.sign.Side");
                    Class<?> compClass = Class.forName("net.kyori.adventure.text.Component");
                    Object front = Enum.valueOf((Class) sideEnum, "FRONT");
                    Object signSide = sign.getClass()
                            .getMethod("getSide", sideEnum).invoke(sign, front);
                    Object comp = compClass
                            .getMethod("translatable", String.class)
                            .invoke(null, keys.get(i));
                    signSide.getClass()
                            .getMethod("setLine", int.class, compClass)
                            .invoke(signSide, i, comp);
                } catch (Throwable t) {
                    // legacy fallback if adventure APIs absent
                    sign.setLine(i, keys.get(i));
                }
            }
            sign.update(true, false);
        }

        WrapperPlayServerOpenSignEditor open =
                new WrapperPlayServerOpenSignEditor(
                        new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                        true); // front side
        com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager().sendPacket(player, open);

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> { player.closeInventory(); block.setType(oldType, false); },
                cfg.getTranslatableGuiVisibleTicks()
        );
    }

    /* --------------------------------------------------------------------- */
    /*  Handle client response                                               */
    /* --------------------------------------------------------------------- */

    @Override
    public void onPacketReceive(PacketReceiveEvent ev) {
        if (ev.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        String[] lines;
        try {
            lines = (String[]) WrapperPlayClientUpdateSign.class
                    .getMethod("getLines")
                    .invoke(new WrapperPlayClientUpdateSign(ev));
        } catch (Exception ignore) { return; }

        Player p = (Player) ev.getPlayer();
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

    /* --------------------------------------------------------------------- */
    /*  Helpers                                                              */
    /* --------------------------------------------------------------------- */

    private int getServerMinor() {
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length >= 2 ? Integer.parseInt(v[1]) : 0;
    }

    private Material getMaterial(String... ids) {
        for (String id : ids)
            try { return Material.valueOf(id); } catch (IllegalArgumentException ignore) {}
        return null;
    }
}