package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
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

import java.lang.reflect.Method;
import java.util.*;

/**
 * Opens a hidden sign with <translatable> JSON lines and checks the client’s reply.
 */
public class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detectionManager;
    private final ConfigManager    cfg;

    /** player → last-probe timestamp (ms) */
    private final Map<UUID, Long>  lastProbe = new HashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin           = plugin;
        this.detectionManager = detectionManager;
        this.cfg              = cfg;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* --------------------------------------------------------------------- */
    /*  Delayed probe on join                                                */
    /* --------------------------------------------------------------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* --------------------------------------------------------------------- */
    /*  Main probe routine                                                   */
    /* --------------------------------------------------------------------- */

    private void probe(Player p) {

        /* feature off? */
        boolean enabled = true;
        try {
            enabled = (boolean) cfg.getClass()
                                   .getMethod("isTranslatableEnabled")
                                   .invoke(cfg);
        } catch (Throwable ignored) {}
        if (!enabled) return;

        /* back-end version guard (<1.20 has no JSON sign packets) */
        if (getMinor() < 20) return;

        /* per-player cooldown */
        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(p.getUniqueId(), 0L) < cfg.getTranslatableCooldown()) return;
        lastProbe.put(p.getUniqueId(), now);

        /* test keys */
        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) return;

        /* up to 4 lines */
        List<String> keyList = new ArrayList<>(tests.keySet());
        while (keyList.size() < 4) keyList.add(keyList.get(0));

        /* hidden sign position */
        Location loc = p.getLocation().clone();
        loc.setY(-64);
        Block block    = loc.getBlock();
        Material prev  = block.getType();
        Material signM = Material.SIGN;           // 1.8 constant – reflection not needed
        block.setType(signM, false);

        /* write JSON lines */
        BlockState st = block.getState();
        if (st instanceof Sign) {
            Sign s = (Sign) st;
            for (int i = 0; i < 4; i++)
                s.setLine(i, "{\"translate\":\"" + keyList.get(i) + "\"}");
            s.update(true, false);
        }

        /* open sign editor one tick later */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(pos, true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, open);
        }, 1);

        /* close GUI & restore world */
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> { p.closeInventory(); block.setType(prev, false); },
                cfg.getTranslatableGuiVisibleTicks());
    }

    /* --------------------------------------------------------------------- */
    /*  Packet listener                                                      */
    /* --------------------------------------------------------------------- */

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player                               p     = (Player) e.getPlayer();
        WrapperPlayClientUpdateSign          w     = new WrapperPlayClientUpdateSign(e);
        String[]                             lines = extractLines(w);
        if (lines.length == 0) return;

        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) return;

        boolean translated = false;
        int     idx        = 0;

        for (Map.Entry<String,String> entry : tests.entrySet()) {
            if (idx >= lines.length) break;
            String rawKey = entry.getKey();
            String label  = entry.getValue();

            if (!lines[idx].equals(rawKey)) {
                translated = true;
                detectionManager.handleTranslatable(
                        p, TranslatableEventType.TRANSLATED, label);
            }
            idx++;
        }
        if (!translated)
            detectionManager.handleTranslatable(p, TranslatableEventType.ZERO, "-");
    }

    /* --------------------------------------------------------------------- */
    /*  helpers                                                              */
    /* --------------------------------------------------------------------- */

    private int getMinor() {
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length > 1 ? Integer.parseInt(v[1]) : 0;
    }

    private String[] extractLines(WrapperPlayClientUpdateSign w) {
        /* PacketEvents switched names a few times → reflect safely */
        try {
            Method m = w.getClass().getMethod("getLines");
            return (String[]) m.invoke(w);
        } catch (Exception ignored) { }
        try {
            Method m = w.getClass().getMethod("getText");
            return (String[]) m.invoke(w);
        } catch (Exception ignored) { }
        return new String[0];
    }
}