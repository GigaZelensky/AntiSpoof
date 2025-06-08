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
 * Minimal-dependency translatable-key probe.
 *
 * • Places a temporary editable sign at y=-64
 * • Writes JSON translatable components
 * • Opens the editor, closes it after N ticks
 * • Reads UpdateSign and compares with raw keys
 *
 * Uses only PE 2.8.1 classes present in AntiSpoof’s current POM.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detection;
    private final ConfigManager    cfg;

    /** last probe per player (ms) */
    private final Map<UUID, Long> lastProbe = new HashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin   = plugin;
        this.detection = detectionManager;
        this.cfg      = cfg;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* --------------------------------------------------------------------- */
    /*  utilities                                                            */
    /* --------------------------------------------------------------------- */

    private void log(Player p, String msg) {
        plugin.getLogger().info("[Translatable] " + p.getName() + " – " + msg);
    }

    private boolean enabled() {
        try { return (boolean) cfg.getClass().getMethod("isTranslatableEnabled").invoke(cfg); }
        catch (Throwable t) { return true; }
    }

    private int minor() {
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length > 1 ? Integer.parseInt(v[1]) : 0;
    }

    private Material findMat(String... ids) {
        for (String id : ids)
            try { return Material.valueOf(id); } catch (IllegalArgumentException ignored) {}
        return Material.SIGN;            // available in 1.8-compile
    }

    private String[] getLines(Object wrapper) {
        /* PE 2.8.1 → getTextLines(); fallback to others reflectively         */
        for (String m : new String[]{"getTextLines", "getLines", "getText"}) {
            try { return (String[]) wrapper.getClass().getMethod(m).invoke(wrapper); }
            catch (Throwable ignored) {}
        }
        /* try individual getters                                            */
        List<String> tmp = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            try {
                Method g = wrapper.getClass().getMethod("getLine" + i);
                tmp.add((String) g.invoke(wrapper));
            } catch (Throwable ignored) {}
        }
        return tmp.toArray(new String[0]);
    }

    /* --------------------------------------------------------------------- */
    /*  join → schedule probe                                                */
    /* --------------------------------------------------------------------- */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* --------------------------------------------------------------------- */
    /*  sign probe                                                           */
    /* --------------------------------------------------------------------- */
    private void probe(Player p) {

        if (!enabled()) return;
        if (minor() < 20) { log(p, "skipped (server <1.20)"); return; }

        long now = System.currentTimeMillis();
        long last = lastProbe.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cfg.getTranslatableCooldown()) { log(p,"cool-down"); return; }
        lastProbe.put(p.getUniqueId(), now);

        LinkedHashMap<String,String> map = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (map.isEmpty()) { log(p,"no keys"); return; }

        /* pick up to 4 keys */
        List<String> keys = new ArrayList<>(map.keySet());
        while (keys.size() < 4) keys.add(keys.get(0));

        /* hidden location */
        Location loc = p.getLocation().clone();
        loc.setY(-64);
        Block block    = loc.getBlock();
        Material prev  = block.getType();
        Material signM = findMat("OAK_SIGN","SIGN_POST");

        block.setType(signM, false);
        BlockState state = block.getState();
        if (!(state instanceof Sign)) { log(p,"no sign state"); return; }

        Sign sign = (Sign) state;
        for (int i = 0; i < 4; i++)
            sign.setLine(i, "{\"translate\":\"" + keys.get(i) + "\"}");

        /* editable & unwaxed (1.20+) via reflection                          */
        try { sign.getClass().getMethod("setEditable", boolean.class).invoke(sign, true); } catch (Throwable ignored) {}
        try { sign.getClass().getMethod("setWaxed",    boolean.class).invoke(sign, false);} catch (Throwable ignored) {}

        sign.update(true, false);
        log(p,"sign placed");

        /* open GUI next tick                                                 */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(pos,true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, open);
            log(p,"editor opened");
        }, 1);

        /* close GUI + restore world                                          */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.closeInventory();
            block.setType(prev, false);
            log(p,"restored");
        }, cfg.getTranslatableGuiVisibleTicks());
    }

    /* --------------------------------------------------------------------- */
    /*  Packet listener                                                      */
    /* --------------------------------------------------------------------- */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player) e.getPlayer();
        String[] lines = getLines(new WrapperPlayClientUpdateSign(e));
        if (lines.length == 0) return;

        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        boolean translated = false;
        int i = 0;
        for (Map.Entry<String,String> en : tests.entrySet()) {
            if (i >= lines.length) break;
            String raw = en.getKey();
            String lab = en.getValue();
            if (!lines[i].equals(raw)) {
                translated = true;
                detection.handleTranslatable(p, TranslatableEventType.TRANSLATED, lab);
                log(p,"TRANSLATED " + lab);
            }
            i++;
        }
        if (!translated) {
            detection.handleTranslatable(p, TranslatableEventType.ZERO, "-");
            log(p,"ZERO");
        }
    }
}