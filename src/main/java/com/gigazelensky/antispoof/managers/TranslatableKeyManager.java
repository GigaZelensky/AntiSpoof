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
 * Translatable-key probe (uses only PacketEvents 2.8.1 classes).
 *
 * • Puts a temporary editable sign at y = -64  
 * • Writes four JSON {@code {"translate":"key"}} lines  
 * • Opens the editor (brief flash) then closes it after N ticks  
 * • Compares {@code UpdateSign} reply with the raw keys
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin  plugin;
    private final DetectionManager detection;
    private final ConfigManager    cfg;

    /** player → last-probe timestamp (ms) */
    private final Map<UUID, Long> lastProbe = new HashMap<>();

    /* ------------------------------------------------------------------ */
    /*  ctor & legacy register()                                          */
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

    /** kept for older bootstrap code */
    public void register() {/* already wired in ctor */}

    /* ------------------------------------------------------------------ */
    /*  helpers                                                           */
    /* ------------------------------------------------------------------ */
    private void log(Player p, String m) { plugin.getLogger().info("[Translatable] "+p.getName()+": "+m); }

    private boolean enabled() {
        try { return (boolean) cfg.getClass().getMethod("isTranslatableEnabled").invoke(cfg); }
        catch (Throwable ignored) { return true; }
    }
    private int minor() {
        String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length>1?Integer.parseInt(v[1]):0;
    }
    private Material mat(String... ids){
        for(String id:ids) try{ return Material.valueOf(id);}catch(IllegalArgumentException ignored){}
        return Material.SIGN;
    }
    private String[] linesOf(Object wrapper){
        for(String m:new String[]{"getTextLines","getLines","getText"})
            try{ return (String[])wrapper.getClass().getMethod(m).invoke(wrapper);}catch(Throwable ignored){}
        List<String> tmp=new ArrayList<>();
        for(int i=1;i<=4;i++)
            try{ Method g=wrapper.getClass().getMethod("getLine"+i); tmp.add((String)g.invoke(wrapper)); }catch(Throwable ignored){}
        return tmp.toArray(new String[0]);
    }

    /** convert config values → plain String (avoids MemorySection leak) */
    private LinkedHashMap<String,String> loadKeys(){
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        try{
            Object raw=cfg.getClass().getMethod("getTranslatableTestKeys").invoke(cfg);
            if(raw instanceof Map){
                for(Map.Entry<?,?> e:((Map<?,?>)raw).entrySet())
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }else if(raw instanceof ConfigurationSection){
                ConfigurationSection cs=(ConfigurationSection) raw;
                for(String k:cs.getKeys(false)) out.put(k,cs.getString(k,k));
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
        if(minor()<20){ log(p,"skip <1.20"); return; }

        long now=System.currentTimeMillis();
        if(now-lastProbe.getOrDefault(p.getUniqueId(),0L)<cfg.getTranslatableCooldown()){
            log(p,"cooldown"); return;
        }
        lastProbe.put(p.getUniqueId(),now);

        LinkedHashMap<String,String> tests=loadKeys();
        if(tests.isEmpty()){ log(p,"no keys"); return; }

        List<String> keys=new ArrayList<>(tests.keySet());
        while(keys.size()<4) keys.add(keys.get(0));

        Location loc=p.getLocation().clone(); loc.setY(-64);
        Block b=loc.getBlock(); Material prev=b.getType();
        b.setType(mat("OAK_SIGN","SIGN_POST"),false);

        BlockState st=b.getState();
        if(!(st instanceof Sign)){ log(p,"no sign state"); return; }
        Sign sg=(Sign)st;
        for(int i=0;i<4;i++) sg.setLine(i,"{\"translate\":\""+keys.get(i)+"\"}");
        try{ sg.getClass().getMethod("setEditable",boolean.class).invoke(sg,true);}catch(Throwable ignored){}
        try{ sg.getClass().getMethod("setWaxed",boolean.class).invoke(sg,false);}catch(Throwable ignored){}
        sg.update(true,false);
        log(p,"placed");

        Bukkit.getScheduler().runTaskLater(plugin,()->{
            Vector3i pos=new Vector3i(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p,new WrapperPlayServerOpenSignEditor(pos,true));
            log(p,"editor");
        },1);

        Bukkit.getScheduler().runTaskLater(plugin,()->{
            p.closeInventory(); b.setType(prev,false); log(p,"restore");
        },cfg.getTranslatableGuiVisibleTicks());
    }

    /* ------------------------------------------------------------------ */
    /*  packet listener                                                    */
    /* ------------------------------------------------------------------ */
    @Override
    public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;

        Player p=(Player)e.getPlayer();
        String[] lines=linesOf(new WrapperPlayClientUpdateSign(e));
        if(lines.length==0) return;

        LinkedHashMap<String,String> tests=loadKeys();
        boolean any=false; int i=0;
        for(Map.Entry<String,String> en:tests.entrySet()){
            if(i>=lines.length) break;
            String raw=en.getKey();
            String label=en.getValue();
            if(!lines[i].equals(raw)){
                any=true; log(p,"TRANSLATED "+label);
                detection.handleTranslatable(p,TranslatableEventType.TRANSLATED,label);
            }
            i++;
        }
        if(!any){
            log(p,"ZERO");
            detection.handleTranslatable(p,TranslatableEventType.ZERO,"-");
        }
    }
}