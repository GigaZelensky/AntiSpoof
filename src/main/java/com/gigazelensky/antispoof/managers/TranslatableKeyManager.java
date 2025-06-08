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
 * Translatable-key probe (minimal PacketEvents 2.8.1 API).
 *
 * • Temporary editable sign at y = -64  
 * • JSON `{"translate":…}` lines  
 * • Opens editor, closes after N ticks  
 * • Compares {@code UpdateSign} reply with raw keys
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detection;
    private final ConfigManager    cfg;

    /** last probe timestamp per player (ms) */
    private final Map<UUID, Long> lastProbe = new HashMap<>();

    /* ------------------------------------------------------------------ */
    /*  ctor                                                              */
    /* ------------------------------------------------------------------ */
    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin   = plugin;
        this.detection = detectionManager;
        this.cfg      = cfg;

        /* auto-register – keeps legacy call-sites working even if they      */
        /* forget to invoke register() below                                 */
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /**
     * Legacy hook – AntiSpoofPlugin still calls this.  
     * Safe to call multiple times; duplicates are filtered by Bukkit/PE.
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ------------------------------------------------------------------ */
    /*  utilities                                                          */
    /* ------------------------------------------------------------------ */
    private void log(Player p, String m) { plugin.getLogger().info("[Translatable] "+p.getName()+": "+m); }

    private boolean enabled() {
        try { return (boolean) cfg.getClass().getMethod("isTranslatableEnabled").invoke(cfg); }
        catch (Throwable t) { return true; }
    }

    private int minor() {
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length > 1 ? Integer.parseInt(v[1]) : 0;
    }

    private Material mat(String... ids) {
        for (String id : ids)
            try { return Material.valueOf(id); } catch (IllegalArgumentException ignored) {}
        return Material.SIGN;         // 1.8 constant
    }

    private String[] extract(Object w) {
        for (String m : new String[]{"getTextLines","getLines","getText"})
            try { return (String[]) w.getClass().getMethod(m).invoke(w); } catch (Throwable ignored) {}
        List<String> tmp=new ArrayList<>();
        for(int i=1;i<=4;i++)
            try{Method g=w.getClass().getMethod("getLine"+i);tmp.add((String)g.invoke(w));}catch(Throwable ignored){}
        return tmp.toArray(new String[0]);
    }

    /* ------------------------------------------------------------------ */
    /*  join                                                               */
    /* ------------------------------------------------------------------ */
    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin,
                ()->probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* ------------------------------------------------------------------ */
    /*  probe                                                              */
    /* ------------------------------------------------------------------ */
    private void probe(Player p){
        if(!enabled()) return;
        if(minor()<20){log(p,"skip <1.20");return;}

        long now=System.currentTimeMillis();
        if(now-lastProbe.getOrDefault(p.getUniqueId(),0L)<cfg.getTranslatableCooldown()){log(p,"cooldown");return;}
        lastProbe.put(p.getUniqueId(),now);

        LinkedHashMap<String,String> tests=new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        if(tests.isEmpty()){log(p,"no keys");return;}

        List<String> keys=new ArrayList<>(tests.keySet());
        while(keys.size()<4) keys.add(keys.get(0));

        Location loc=p.getLocation().clone();loc.setY(-64);
        Block b=loc.getBlock();Material prev=b.getType();
        b.setType(mat("OAK_SIGN","SIGN_POST"),false);

        BlockState st=b.getState();
        if(!(st instanceof Sign)){log(p,"no sign state");return;}
        Sign sg=(Sign)st;
        for(int i=0;i<4;i++) sg.setLine(i,"{\"translate\":\""+keys.get(i)+"\"}");
        try{sg.getClass().getMethod("setEditable",boolean.class).invoke(sg,true);}catch(Throwable ignored){}
        try{sg.getClass().getMethod("setWaxed",boolean.class).invoke(sg,false);}catch(Throwable ignored){}
        sg.update(true,false);
        log(p,"placed");

        Bukkit.getScheduler().runTaskLater(plugin,()->{
            Vector3i pos=new Vector3i(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p,new WrapperPlayServerOpenSignEditor(pos,true));
            log(p,"editor");
        },1);

        Bukkit.getScheduler().runTaskLater(plugin,()->{
            p.closeInventory();b.setType(prev,false);log(p,"restore");
        },cfg.getTranslatableGuiVisibleTicks());
    }

    /* ------------------------------------------------------------------ */
    /*  Packet listener                                                    */
    /* ------------------------------------------------------------------ */
    @Override
    public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;

        Player p=(Player)e.getPlayer();
        String[] lines=extract(new WrapperPlayClientUpdateSign(e));
        if(lines.length==0) return;

        LinkedHashMap<String,String> tests=new LinkedHashMap<>(cfg.getTranslatableTestKeys());
        boolean any=false;int i=0;
        for(Map.Entry<String,String> en:tests.entrySet()){
            if(i>=lines.length) break;
            if(!lines[i].equals(en.getKey())){
                any=true;log(p,"TRANSLATED "+en.getValue());
                detection.handleTranslatable(p,TranslatableEventType.TRANSLATED,en.getValue());
            }
            i++;
        }
        if(!any){log(p,"ZERO");detection.handleTranslatable(p,TranslatableEventType.ZERO,"-");}
    }
}