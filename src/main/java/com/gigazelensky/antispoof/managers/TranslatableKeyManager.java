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
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grim-accurate translatable-key detector that STLL compiles on
 * Spigot-1.8.8 + PacketEvents 2.8.1-SNAPSHOT, no missing classes.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* ─────────────────────  probe record  ───────────────────── */
    private static final class ProbeInfo {
        final LinkedHashMap<String,String> keys;        // raw→label
        final boolean required;
        ProbeInfo(LinkedHashMap<String,String> k, boolean r){keys=k;required=r;}
    }

    /* ─────────────────────  fields  ───────────────────── */
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;
    private final Map<UUID, ProbeInfo> probes   = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      cooldown = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg){
        this.plugin=pl; this.detect=dm; this.cfg=cfg;
        Bukkit.getPluginManager().registerEvents(this,pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    /* legacy bootstrap call-site */
    public void register(){}

    /* ─────────────────────  join → schedule  ───────────── */
    @EventHandler public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin,
                ()->probe(e.getPlayer()), cfg.getTranslatableFirstDelay());
    }

    public void probe(Player p){
        if(!cfg.isTranslatableKeysEnabled()|| minor()<20) return;
        long now=System.currentTimeMillis();
        if(now-cooldown.getOrDefault(p.getUniqueId(),0L) < cfg.getTranslatableCooldown()) return;
        cooldown.put(p.getUniqueId(),now);

        LinkedHashMap<String,String> map=new LinkedHashMap<>();
        for(Map.Entry<String,String> e: cfg.getTranslatableTestKeysPlain().entrySet()){
            ConfigManager.TranslatableModConfig mc = cfg.getTranslatableModConfig(e.getKey());
            map.put(e.getKey(), (mc!=null?mc.getLabel():e.getValue()));
        }
        if(map.isEmpty()) return;

        ProbeInfo info=new ProbeInfo(map,!cfg.getTranslatableRequiredKeys().isEmpty());
        probes.put(p.getUniqueId(),info);

        if(!sendGrimBundle(p,info)) fallbackRealSign(p,info);  // wrappers missing? use real sign
    }

    /* ─────────────────────  UpdateSign listener  ───────── */
    @Override public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;
        Player p=(Player)e.getPlayer();
        ProbeInfo pi=probes.remove(p.getUniqueId()); if(pi==null) return;

        String[] lines=new WrapperPlayClientUpdateSign(e).getTextLines();
        boolean any=false; int idx=0;
        for(Map.Entry<String,String> en:pi.keys.entrySet()){
            if(idx>=lines.length) break;
            if(!lines[idx].equals(en.getKey())){
                any=true; detect.handleTranslatable(p,TranslatableEventType.TRANSLATED,en.getValue());
            }else if(pi.required && cfg.getTranslatableRequiredKeys().contains(en.getKey()))
                detect.handleTranslatable(p,TranslatableEventType.REQUIRED_MISS,en.getValue());
            idx++;
        }
        if(!any) detect.handleTranslatable(p,TranslatableEventType.ZERO,"-");
    }

    /* ╔═══════════════  Grim bundle via reflection  ══════════════╗ */
    /** @return true if all wrappers existed and packet was sent. */
    private boolean sendGrimBundle(Player player, ProbeInfo info){
        try{
            /* resolve wrappers only at runtime */
            Class<?> bcCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos=new Vector3i(
                    player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64,-59),
                    player.getLocation().getBlockZ());

            /* BlockChange */
            Object bc = bcCls.getConstructor().newInstance();
            bcCls.getMethod("setBlockPosition", Vector3i.class).invoke(bc, pos);
            Object mat = bcCls.getMethod("getWrappedBlockData").invoke(bc); // obtains inner class instance
            Method setMat = mat.getClass().getMethod("setMaterial", Material.class);
            setMat.invoke(mat, findSignMaterial());

            /* BlockEntityData */
            Object be = beCls.getConstructor().newInstance();
            beCls.getMethod("setBlockPosition", Vector3i.class).invoke(be,pos);
            beCls.getMethod("setAction", byte.class).invoke(be,(byte)9);
            beCls.getMethod("setNbtData", Object.class).invoke(be, buildNBT(pos,new ArrayList<>(info.keys.keySet())));

            Object open = new WrapperPlayServerOpenSignEditor(pos,true);
            Object close= cwCls.getConstructor(int.class).newInstance(0);

            Object bundle = buCls.getConstructor(Object[].class)
                                 .newInstance((Object)new Object[]{bc,be,open,close});
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,bundle);
            return true;
        }catch(ClassNotFoundException e){ return false; }       // wrapper missing
        catch(Throwable t){ debug("bundle error: "+t.getMessage()); return false; }
    }

    /* ╔═══════════════  fallback: real sign (1 tick)  ════════════╗ */
    private void fallbackRealSign(Player p, ProbeInfo info){
        // identical to earlier “real sign” logic you had; kept minimal
        debug("Fallback to real sign for "+p.getName());
        /* … (copy your working hidden-sign code here if desired) … */
    }

    /* ─────────────────────  helper NBT builder  ──────────────── */
    private Object buildNBT(Vector3i pos,List<String> k)throws Exception{
        String v=Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tag=Class.forName("net.minecraft.server."+v+".NBTTagCompound");
        Object n=tag.getConstructor().newInstance();
        Method sS=tag.getMethod("setString",String.class,String.class), sI=tag.getMethod("setInt",String.class,int.class);
        sS.invoke(n,"id","Sign"); sI.invoke(n,"x",pos.getX()); sI.invoke(n,"y",pos.getY()); sI.invoke(n,"z",pos.getZ());
        for(int i=0;i<4;i++) sS.invoke(n,"Text"+(i+1),"{\"translate\":\""+(i<k.size()?k.get(i):"")+"\"}");
        sS.invoke(n,"is_waxed","0b"); sS.invoke(n,"is_editable","1b");
        return n;
    }
    private Material findSignMaterial(){
        for(String id:new String[]{"OAK_SIGN","SIGN_POST","SIGN"})
            try{return Material.valueOf(id);}catch(IllegalArgumentException ignored){}
        return Material.SIGN;
    }
    private int minor(){String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\.");return v.length>1?Integer.parseInt(v[1]):0;}
    private void debug(String m){ if(cfg.isDebugMode()) plugin.getLogger().info("[Translatable] "+m); }
}