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
 * Opens a hidden sign containing translatable components, then inspects the
 * {@code UpdateSign} packet to see what the client returned.
 */
public class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detectionManager;
    private final ConfigManager    cfg;

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
    /*  Join → delayed probe                                                 */
    /* --------------------------------------------------------------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* --------------------------------------------------------------------- */
    /*  Sign probe                                                            */
    /* --------------------------------------------------------------------- */

    private void probe(Player p) {
        boolean enabled = true;
        try { enabled = (boolean) cfg.getClass()
                                     .getMethod("isTranslatableEnabled")
                                     .invoke(cfg);
        } catch (Throwable ignored) {}
        if (!enabled) return;
        if (getMinor() < 20) return;

        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(p.getUniqueId(), 0L) < cfg.getTranslatableCooldown()) return;
        lastProbe.put(p.getUniqueId(), now);

        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) return;

        List<String> keyList = new ArrayList<>(tests.keySet());
        while (keyList.size() < 4) keyList.add(keyList.get(0));

        Location loc = p.getLocation().clone();
        loc.setY(-64);
        Block block    = loc.getBlock();
        Material old   = block.getType();
        Material signM = getMaterial("OAK_SIGN","SIGN_POST","SIGN");
        if (signM == null) return;

        block.setType(signM,false);
        BlockState st = block.getState();
        if (st instanceof Sign) {
            Sign s = (Sign) st;
            for (int i=0;i<4;i++) s.setLine(i,"{\"translate\":\""+keyList.get(i)+"\"}");
            s.update(true,false);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Vector3i pos = new Vector3i(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
            WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(pos,true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p,open);
        },1);

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> { p.closeInventory(); block.setType(old,false); },
                cfg.getTranslatableGuiVisibleTicks());
    }

    /* --------------------------------------------------------------------- */
    /*  Packet listener                                                      */
    /* --------------------------------------------------------------------- */

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player) e.getPlayer();
        String[] lines = extractLines(new WrapperPlayClientUpdateSign(e));
        if (lines.length == 0) return;

        LinkedHashMap<String,String> tests = new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if (tests.isEmpty()) return;

        boolean any = false;
        int i = 0;
        for (Map.Entry<String,String> en : tests.entrySet()) {
            if (i >= lines.length) break;
            if (!lines[i].equals(en.getKey())) {
                any = true;
                detectionManager.handleTranslatable(p,
                        TranslatableEventType.TRANSLATED, en.getValue());
            }
            i++;
        }
        if (!any)
            detectionManager.handleTranslatable(p,TranslatableEventType.ZERO,"-");
    }

    /* --------------------------------------------------------------------- */
    /*  helpers                                                              */
    /* --------------------------------------------------------------------- */

    private int getMinor() {
        String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length>1?Integer.parseInt(v[1]):0;
    }
    private Material getMaterial(String... ids){
        for(String id:ids)
            try{return Material.valueOf(id);}catch(IllegalArgumentException ignored){}
        return null;
    }

    /**
     * PacketEvents 2.8.1 renamed the accessor; resolve reflectively.
     */
    private String[] extractLines(Object wrapper){
        try{
            /* preferred (PE ≥2.9) */
            Method m=wrapper.getClass().getMethod("getLines");
            return (String[])m.invoke(wrapper);
        }catch(Throwable ignored){}
        try{
            /* legacy (PE 2.8.x) */
            Method m=wrapper.getClass().getMethod("getText");
            return (String[])m.invoke(wrapper);
        }catch(Throwable ignored){}

        String[] out=new String[4];
        for(int i=0;i<4;i++){
            try{
                Method m=wrapper.getClass()
                        .getMethod("getLine"+(i+1));
                out[i]=(String)m.invoke(wrapper);
            }catch(Throwable ignored){}
        }
        return out;
    }
}