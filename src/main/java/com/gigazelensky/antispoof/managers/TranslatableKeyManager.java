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
 * Hidden-sign probe for client translatable-key detection.
 *
 * Compile-time safe against Spigot-1.8.8; all 1.20+ methods are invoked
 * through reflection so the project still builds on the legacy API.
 */
public class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detection;
    private final ConfigManager    cfg;
    private final Map<UUID, Long>  lastProbe = new HashMap<>();

    private void log(Player p, String msg) {
        plugin.getLogger().info("[Translatable] " + p.getName() + ": " + msg);
    }

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin   = plugin;
        this.detection = detectionManager;
        this.cfg      = cfg;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ------------------------------------------------------------------ */
    /*  Join → delayed probe                                              */
    /* ------------------------------------------------------------------ */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* ------------------------------------------------------------------ */
    /*  Probe                                                             */
    /* ------------------------------------------------------------------ */
    private void probe(Player p) {

        /* 0) master switch */
        boolean enabled;
        try { enabled = (boolean) cfg.getClass()
                                     .getMethod("isTranslatableEnabled")
                                     .invoke(cfg);
        } catch (Throwable t) { enabled = true; }
        if (!enabled) return;

        /* 1) needs 1.20+ back-end (UpdateSign JSON) */
        if (getMinor() < 20) { log(p, "skipped – server <1.20"); return; }

        /* 2) per-player cooldown */
        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(p.getUniqueId(), 0L) < cfg.getTranslatableCooldown()) {
            log(p, "cool-down active");
            return;
        }
        lastProbe.put(p.getUniqueId(), now);

        /* 3) keys */
        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) { log(p, "no keys configured"); return; }
        List<String> keyList = new ArrayList<>(tests.keySet());
        while (keyList.size() < 4) keyList.add(keyList.get(0));   // pad

        /* 4) hidden sign position */
        Location loc = p.getLocation().clone();
        loc.setY(-64);
        Block block   = loc.getBlock();
        Material prev = block.getType();
        Material signMat = getMaterial("OAK_SIGN", "SIGN_POST", "SIGN");
        if (signMat == null) { log(p, "no sign material found"); return; }

        /* 5) place sign + write JSON lines */
        block.setType(signMat, false);
        BlockState state = block.getState();
        if (!(state instanceof Sign)) { log(p, "state !sign"); return; }
        Sign sign = (Sign) state;
        for (int i = 0; i < 4; i++)
            sign.setLine(i, "{\"translate\":\"" + keyList.get(i) + "\"}");
        /* editable/waxed flags on 1.20+: reflected so build succeeds */
        try { sign.getClass().getMethod("setWaxed", boolean.class).invoke(sign, false); } catch (Throwable ignored) {}
        try { sign.getClass().getMethod("setEditable", boolean.class).invoke(sign, true); } catch (Throwable ignored) {}
        sign.update(true, false);
        log(p, "sign placed");

        /* 6) open editor (1 tick later so TE data already sent) */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(pos, true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, open);
            log(p, "editor opened");
        }, 1);

        /* 7) close GUI & restore */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.closeInventory();
            block.setType(prev, false);
            log(p, "sign restored");
        }, cfg.getTranslatableGuiVisibleTicks());
    }

    /* ------------------------------------------------------------------ */
    /*  Packet listener                                                   */
    /* ------------------------------------------------------------------ */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player) e.getPlayer();
        String[] lines = extractLines(new WrapperPlayClientUpdateSign(e));
        if (lines.length == 0) return;

        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) return;

        boolean translated = false;
        int idx = 0;
        for (Map.Entry<String,String> en : tests.entrySet()) {
            if (idx >= lines.length) break;
            if (!lines[idx].equals(en.getKey())) {
                translated = true;
                log(p, "TRANSLATED → " + en.getValue());
                detection.handleTranslatable(p, TranslatableEventType.TRANSLATED, en.getValue());
            }
            idx++;
        }
        if (!translated) {
            log(p, "ZERO (no translation)");
            detection.handleTranslatable(p, TranslatableEventType.ZERO, "-");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                           */
    /* ------------------------------------------------------------------ */

    private int getMinor() {
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length > 1 ? Integer.parseInt(v[1]) : 0;
    }

    private Material getMaterial(String... ids) {
        for (String id : ids)
            try { return Material.valueOf(id); } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** reflect over WrapperPlayClientUpdateSign to stay API-safe */
    private String[] extractLines(Object wrapper) {
        try {
            Method m = wrapper.getClass().getMethod("getTextLines"); // PE 2.8.1
            return (String[]) m.invoke(wrapper);
        } catch (Throwable ignored) {}
        try {
            Method m = wrapper.getClass().getMethod("getLines");
            return (String[]) m.invoke(wrapper);
        } catch (Throwable ignored) {}
        try {
            Method m = wrapper.getClass().getMethod("getText");
            return (String[]) m.invoke(wrapper);
        } catch (Throwable ignored) {}
        /* fallback: try individual getters */
        List<String> tmp = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            try {
                Method m = wrapper.getClass().getMethod("getLine" + i);
                tmp.add((String) m.invoke(wrapper));
            } catch (Throwable ignored) {}
        }
        return tmp.toArray(new String[0]);
    }
}