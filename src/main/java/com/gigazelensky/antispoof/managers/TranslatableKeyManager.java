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
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Translatable‑key detector – reflection version compatible with PacketEvents 4.x (same
 * technique Grim 3 uses but without compile‑time dependency on wrappers that may not exist).
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* -------------------------------------------------------------------- probe */
    private static final class Probe {
        final List<Map.Entry<String, String>> keys;
        final boolean requiredEnabled;
        int cursor = 0;
        int failures = 0;
        boolean terminated = false;
        List<Map.Entry<String, String>> pending = Collections.emptyList();
        Probe(LinkedHashMap<String, String> map, boolean req) { this.keys = new ArrayList<>(map.entrySet()); this.requiredEnabled=req; }
        boolean hasNext() { return cursor < keys.size(); }
        void advance(int n){ cursor += n; }
    }

    /* ----------------------------------------------------------------- fields */
    private static final int BATCH_SIZE = 4; // 4 sign lines (front only)

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    private final Map<UUID, Probe> probes   = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  cooldown = new ConcurrentHashMap<>();
    private final Set<UUID>        disabled = ConcurrentHashMap.newKeySet();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg){
        this.plugin = pl; this.detect = dm; this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ---------------------------------------------------------------- public‑API for older listener code */
    public void probe(Player p){ startProbe(p); }

    /* --------------------------------------------------------------- join hook */
    @EventHandler public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin, ()->startProbe(e.getPlayer()), cfg.getTranslatableFirstDelay());
    }

    private void startProbe(Player p){
        if(disabled.contains(p.getUniqueId())) return;
        if(!cfg.isTranslatableKeysEnabled() || minor()<20) return;
        long now=System.currentTimeMillis();
        if(now - cooldown.getOrDefault(p.getUniqueId(),0L) < cfg.getTranslatableCooldown()) return;
        cooldown.put(p.getUniqueId(), now);

        LinkedHashMap<String,String> map=new LinkedHashMap<>();
        // legacy map
        for(Map.Entry<String,String> e: cfg.getTranslatableTestKeysPlain().entrySet()){
            ConfigManager.TranslatableModConfig mc = cfg.getTranslatableModConfig(e.getKey());
            map.put(e.getKey(), mc!=null?mc.getLabel():e.getValue());
        }
        // mods section
        try{
            FileConfiguration root=(FileConfiguration)cfg.getClass().getMethod("getConfig").invoke(cfg);
            ConfigurationSection mods=root.getConfigurationSection("translatable-keys.mods");
            if(mods!=null){
                for(String k:mods.getKeys(false))
                    map.putIfAbsent(k, mods.getString(k+".label",k));
            }
        }catch(Throwable ignored){}
        if(map.isEmpty()){ debug("no translatable keys configured"); return; }

        Probe pr=new Probe(map,!cfg.getTranslatableRequiredKeys().isEmpty());
        probes.put(p.getUniqueId(), pr);
        sendNextBatch(p,pr);
    }

    /* --------------------------------------------------------- packet receive */
    @Override public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;
        Player p=(Player)e.getPlayer();
        Probe pr=probes.get(p.getUniqueId()); if(pr==null||pr.pending.isEmpty()) return;

        String[] lines=new WrapperPlayClientUpdateSign(e).getTextLines();
        boolean any=false;
        for(int i=0;i<pr.pending.size();i++){
            Map.Entry<String,String> en=pr.pending.get(i);
            String expectedKey=en.getKey();
            String label=en.getValue();
            String got=i<lines.length?lines[i]:"";
            if(!got.equals(expectedKey)){
                any=true;
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, label);
            }else if(pr.requiredEnabled && cfg.getTranslatableRequiredKeys().contains(expectedKey)){
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, label);
                pr.terminated=true; probes.remove(p.getUniqueId()); return;
            }
        }
        if(!any) detect.handleTranslatable(p, TranslatableEventType.ZERO, "-");

        sendNextBatch(p, pr);
        if(!pr.hasNext()) probes.remove(p.getUniqueId());
    }

    /* -------------------------------------------------------------- batching */
    private void sendNextBatch(Player p, Probe pr){
        if(pr.terminated||!pr.hasNext()) return;
        List<Map.Entry<String,String>> slice=pr.keys.subList(pr.cursor, Math.min(pr.cursor+BATCH_SIZE, pr.keys.size()));
        pr.pending=new ArrayList<>(slice);
        pr.advance(slice.size());
        if(!sendBundleReflect(p,slice)){
            pr.failures++; if(pr.failures>=2){ disabled.add(p.getUniqueId()); probes.remove(p.getUniqueId()); debug("disabled probe for "+p.getName()); }
            fallbackRealSign(p,slice);
        }
    }

    /* ---------------------------------------------------- bundle via reflection (PE 4.x) */
    private boolean sendBundleReflect(Player player, List<Map.Entry<String,String>> keys){
        try{
            Class<?> bcCls=Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls=Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls=Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls=Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos=new Vector3i(player.getLocation().getBlockX(), ThreadLocalRandom.current().nextInt(-64,-59), player.getLocation().getBlockZ());

            Object bc=createBlockChange(bcCls,pos);
            Object be=beCls.getConstructor().newInstance();
            Method posSetter=Arrays.stream(beCls.getMethods()).filter(m->m.getName().equals("setBlockPosition")||m.getName().equals("setLocation")).findFirst().orElseThrow();
            posSetter.invoke(be,pos);
            beCls.getMethod("setAction", byte.class).invoke(be,(byte)9);
            beCls.getMethod("setNbtData", Object.class).invoke(be, buildNBT(pos,keys));

            Object open = new WrapperPlayServerOpenSignEditor(pos,true);
            Object close=cwCls.getConstructor(int.class).newInstance(0);
            Object bundle=buCls.getConstructor(Object[].class).newInstance((Object)new Object[]{bc,be,open,close});
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,bundle);
            return true;
        }catch(ClassNotFoundException e){ return false; }
        catch(Throwable t){ debug("bundle error: "+t.getMessage()); return false; }
    }

    private Object createBlockChange(Class<?> bcCls, Vector3i pos) throws Exception{
        try{ return bcCls.getConstructor(Vector3i.class,int.class).newInstance(pos,63);}catch(NoSuchMethodException ignore){}
        try{
            Object bc=bcCls.getConstructor().newInstance();
            bcCls.getMethod("setBlockPosition",Vector3i.class).invoke(bc,pos);
            Class<?> wbdCls=Class.forName(bcCls.getName()+"$WrappedBlockData");
            Object wbd=wbdCls.getMethod("create",Material.class).invoke(null,findSign());
            bcCls.getMethod("setBlockData",wbdCls).invoke(bc,wbd);
            return bc;
        }catch(NoSuchMethodException ignore){}
        return bcCls.getConstructor(Material.class,Vector3i.class).newInstance(findSign(),pos);
    }

    /* --------------------------------------------- real‑sign fallback (pre‑1.20 or reflection fail) */
    private void fallbackRealSign(Player p, List<Map.Entry<String,String>> keys){
        Block b=p.getWorld().getBlockAt(p.getLocation().getBlockX(), -64, p.getLocation().getBlockZ());
        Material prev=b.getType(); b.setType(findSign(),false);
        BlockState st=b.getState();
        if(st instanceof Sign s){
            int i=0; for(Map.Entry<String,String> en: keys){ if(i==4) break; s.setLine(i,"{\"translate\":\""+en.getKey()+"\"}"); i++; }
            try{ s.getClass().getMethod("setEditable",boolean.class).invoke(s,true);}catch(Throwable ignored){}
            s.update(true,false);
        }
        Bukkit.getScheduler().runTask(plugin, ()->PacketEvents.getAPI().getPlayerManager().sendPacket(p,new WrapperPlayServerOpenSignEditor(new Vector3i(b.getX(),b.getY(),b.getZ()),true)));
        Bukkit.getScheduler().runTaskLater(plugin, ()->b.setType(prev,false), guiTicks());
    }

    /* ------------------------------------------------- NBT builder */
    private Object buildNBT(Vector3i pos, List<Map.Entry<String,String>> keys) throws Exception{
        String v=Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tagCls=Class.forName("net.minecraft.server."+v+".NBTTagCompound");
        Object n=tagCls.getConstructor().newInstance();
        Method sS=tagCls.getMethod("setString",String.class,String.class), sI=tagCls.getMethod("setInt",String.class,int.class);
        sS.invoke(n,"id","Sign"); sI.invoke(n,"x",pos.getX()); sI.invoke(n,"y",pos.getY()); sI.invoke(n,"z",pos.getZ());
        for(int i=0;i<4;i++) sS.invoke(n,"Text"+(i+1), i<keys.size()? "{\"translate\":\""+keys.get(i).getKey()+"\"}" : "{\"text\":\"\"}");
        sS.invoke(n,"is_editable","1b"); return n;
    }

    /* ------------------------------------------------- misc helpers */
    private int guiTicks(){ try{ return (int)cfg.getClass().getMethod("getTranslatableGuiVisibleTicks").invoke(cfg);}catch(Throwable t){ return 2; }}
    private Material findSign(){ for(String id:new String[]{"OAK_SIGN","SIGN_POST","SIGN"}) try{ return Material.valueOf(id);}catch(IllegalArgumentException ignored){} return Material.SIGN; }
    private int minor(){ String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\."); return v.length>1?Integer.parseInt(v[1]):0; }
    private void debug(String m){ if(cfg.isDebugMode()) plugin.getLogger().info("[Translatable] "+m); }
}
