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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Translatable-key probe that compiles on Spigot-1.8.8 & PacketEvents 2.8.1.
 *
 * – writes JSON {@code {"translate": "<key>"}} to a hidden sign  
 * – opens the editor for a single tick, then restores the block  
 * – compares {@code UpdateSign} reply with raw keys
 *
 * The YAML “MemorySection” leak is fixed: any non-string value now falls
 * back to the map-key itself (so at worst you see the key name).
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detection;
    private final ConfigManager    cfg;
    private final Map<UUID, Long>  lastProbe = new HashMap<>();

    /* ------------------------------------------------------------------ */
    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager cfg) {
        this.plugin    = plugin;
        this.detection = detectionManager;
        this.cfg       = cfg;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    /** legacy bootstrap hook (safe NOP if already registered) */
    public void register() {/* already done in ctor */}

    /* ------------------------------------------------------------------ */
    /*  helpers                                                           */
    /* ------------------------------------------------------------------ */
    private void log(Player p,String m){plugin.getLogger().info("[Translatable] "+p.getName()+": "+m);}
    private boolean enabled(){try{return (boolean)cfg.getClass().getMethod("isTranslatableEnabled").invoke(cfg);}catch(Throwable t){return true;}}
    private int minor(){String[]v=Bukkit.getBukkitVersion().split("-")[0].split("\\.");return v.length>1?Integer.parseInt(v[1]):0;}
    private Material mat(String...ids){for(String id:ids)try{return Material.valueOf(id);}catch(Exception ignored){}return Material.SIGN;}
    private String[] extract(Object w){
        for(String m:new String[]{"getTextLines","getLines","getText"})
            try{return (String[])w.getClass().getMethod(m).invoke(w);}catch(Throwable ignored){}
        List<String> tmp=new ArrayList<>();
        for(int i=1;i<=4;i++)
            try{tmp.add((String)w.getClass().getMethod("getLine"+i).invoke(w));}catch(Throwable ignored){}
        return tmp.toArray(new String[0]);
    }

    /** flatten YAML section → key ⇒ label (String) */
    private LinkedHashMap<String,String> loadKeys(){
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        try{
            Object raw=cfg.getClass().getMethod("getTranslatableTestKeys").invoke(cfg);
            if(raw instanceof Map){
                for(Map.Entry<?,?> e:((Map<?,?>)raw).entrySet()){
                    String k=String.valueOf(e.getKey());
                    Object v=e.getValue();
                    String label=(v instanceof String)?(String)v:
                                 (v instanceof ConfigurationSection)?((ConfigurationSection)v).getName():k;
                    out.put(k,label);
                }
            }else if(raw instanceof ConfigurationSection){
                ConfigurationSection cs=(ConfigurationSection)raw;
                for(String k:cs.getKeys(false))
                    out.put(k,cs.getString(k,k));
            }
        }catch(Throwable ignored){}
        return out;
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

        LinkedHashMap<String,String> tests=loadKeys();
        if(tests.isEmpty()){log(p,"no keys");return;}

        List<String> keys=new ArrayList<>(tests.keySet());
        while(keys.size()<4) keys.add(keys.get(0));

        Location loc=p.getLocation().clone();loc.setY(-64);
        Block b=loc.getBlock();Material prev=b.getType();
        b.setType(mat("OAK_SIGN","SIGN_POST"),false);

        BlockState st=b.getState();
        if(!(st instanceof Sign)){log(p,"state !sign");return;}
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
    /*  packet listener                                                    */
    /* ------------------------------------------------------------------ */
    @Override
    public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;

        Player p=(Player)e.getPlayer();
        String[] lines=extract(new WrapperPlayClientUpdateSign(e));
        if(lines.length==0) return;

        LinkedHashMap<String,String> tests=loadKeys();
        boolean any=false; int i=0;
        for(Map.Entry<String,String> en:tests.entrySet()){
            if(i>=lines.length) break;
            if(!lines[i].equals(en.getKey())){
                any=true;log(p,"TRANSLATED "+en.getValue());
                detection.handleTranslatable(p,TranslatableEventType.TRANSLATED,en.getValue());
            }
            i++;
        }
        if(!any){
            log(p,"ZERO");
            detection.handleTranslatable(p,TranslatableEventType.ZERO,"-");
        }
    }
}