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
 * Final production build – Grim bundle first, real-sign fallback.
 * Loads keys from both legacy map and `translatable-keys.mods.*.label`.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* in-flight probe -------------------------------------------------- */
    private static final class ProbeInfo {
        final LinkedHashMap<String,String> keys;
        final boolean required;
        ProbeInfo(LinkedHashMap<String,String> k, boolean r){ keys=k; required=r; }
    }

    /* fields ----------------------------------------------------------- */
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;
    private final Map<UUID,ProbeInfo> probes   = new ConcurrentHashMap<>();
    private final Map<UUID,Long>      cooldown = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg){
        this.plugin = pl; this.detect = dm; this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    public void register(){}

    /* join → schedule -------------------------------------------------- */
    @EventHandler public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* probe ------------------------------------------------------------ */
    public void probe(Player p){
        if(!cfg.isTranslatableKeysEnabled() || minor()<20) return;

        long now = System.currentTimeMillis();
        if(now - cooldown.getOrDefault(p.getUniqueId(),0L) < cfg.getTranslatableCooldown()) return;
        cooldown.put(p.getUniqueId(), now);

        LinkedHashMap<String,String> map = new LinkedHashMap<>();

        /* legacy flat map */
        for(Map.Entry<String,String> e : cfg.getTranslatableTestKeysPlain().entrySet()){
            ConfigManager.TranslatableModConfig mc = cfg.getTranslatableModConfig(e.getKey());
            map.put(e.getKey(), mc != null ? mc.getLabel() : e.getValue());
        }

        /* new mods section */
        try{
            FileConfiguration root = (FileConfiguration) cfg.getClass().getMethod("getConfig").invoke(cfg);
            ConfigurationSection mods = root.getConfigurationSection("translatable-keys.mods");
            if(mods != null){
                for(String key : mods.getKeys(false)){
                    String label = mods.getString(key+".label", key);
                    map.putIfAbsent(key, label);
                }
            }
        }catch(Throwable ignored){}

        if(map.isEmpty()){ debug("no translatable keys configured"); return; }

        ProbeInfo info = new ProbeInfo(map, !cfg.getTranslatableRequiredKeys().isEmpty());
        probes.put(p.getUniqueId(), info);

        if(!sendBundle(p, info)){
            debug("bundle unavailable, using real sign");
            fallbackRealSign(p, info);
        }
    }

    /* UpdateSign ------------------------------------------------------- */
    @Override public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;
        Player p=(Player)e.getPlayer();
        ProbeInfo pi = probes.remove(p.getUniqueId()); if(pi==null) return;

        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        boolean any=false; int idx=0;
        for(Map.Entry<String,String> en:pi.keys.entrySet()){
            if(idx>=lines.length) break;
            if(!lines[idx].equals(en.getKey())){
                any = true;
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, en.getValue());
            }else if(pi.required && cfg.getTranslatableRequiredKeys().contains(en.getKey())){
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, en.getValue());
            }
            idx++;
        }
        if(!any) detect.handleTranslatable(p, TranslatableEventType.ZERO, "-");
    }

    /* send Grim bundle ------------------------------------------------- */
    private boolean sendBundle(Player player, ProbeInfo info){
        try{
            Class<?> bcCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos = new Vector3i(player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64,-59),
                    player.getLocation().getBlockZ());

            Object bc = createBlockChange(bcCls, pos);
            Object be = beCls.getConstructor().newInstance();
            Method posSetter = Arrays.stream(beCls.getMethods())
                    .filter(m -> m.getName().equals("setBlockPosition") || m.getName().equals("setLocation"))
                    .findFirst().orElseThrow();
            posSetter.invoke(be, pos);
            beCls.getMethod("setAction", byte.class).invoke(be, (byte)9);
            beCls.getMethod("setNbtData", Object.class).invoke(be, buildNBT(pos, new ArrayList<>(info.keys.keySet())));

            Object open  = new WrapperPlayServerOpenSignEditor(pos, true);
            Object close = cwCls.getConstructor(int.class).newInstance(0);
            Object bundle = buCls.getConstructor(Object[].class).newInstance((Object) new Object[]{bc, be, open, close});

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bundle);
            debug("bundle sent to "+player.getName());
            return true;
        }
        catch(ClassNotFoundException e){ return false; }
        catch(Throwable t){ debug("bundle error: "+t.getMessage()); return false; }
    }
    private Object createBlockChange(Class<?> bcCls, Vector3i pos) throws Exception{
        try{ return bcCls.getConstructor(Vector3i.class,int.class).newInstance(pos,63); }catch(NoSuchMethodException ignore){}
        try{
            Object bc = bcCls.getConstructor().newInstance();
            bcCls.getMethod("setBlockPosition",Vector3i.class).invoke(bc,pos);
            Class<?> wbdCls = Class.forName(bcCls.getName()+"$WrappedBlockData");
            Object wbd = wbdCls.getMethod("create",Material.class).invoke(null, findSign());
            bcCls.getMethod("setBlockData",wbdCls).invoke(bc,wbd);
            return bc;
        }catch(NoSuchMethodException ignore){}
        return bcCls.getConstructor(Material.class,Vector3i.class).newInstance(findSign(), pos);
    }

    /* fallback real sign ---------------------------------------------- */
    private void fallbackRealSign(Player p, ProbeInfo info){
        Block b = p.getWorld().getBlockAt(p.getLocation().getBlockX(), -64, p.getLocation().getBlockZ());
        Material prev = b.getType(); b.setType(findSign(), false);

        BlockState st = b.getState();
        if(st instanceof Sign s){
            int i=0; for(String k:info.keys.keySet()){ if(i==4) break; s.setLine(i,k); i++; }
            try{s.getClass().getMethod("setEditable",boolean.class).invoke(s,true);}catch(Throwable ignored){}
            s.update(true,false);
        }
        Bukkit.getScheduler().runTask(plugin,
                ()->PacketEvents.getAPI().getPlayerManager()
                        .sendPacket(p,new WrapperPlayServerOpenSignEditor(
                                new Vector3i(b.getX(),b.getY(),b.getZ()),true)));
        Bukkit.getScheduler().runTaskLater(plugin, ()->b.setType(prev,false), guiTicks());
    }

    /* build NBT -------------------------------------------------------- */
    private Object buildNBT(Vector3i pos,List<String> keys)throws Exception{
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tag = Class.forName("net.minecraft.server."+v+".NBTTagCompound");
        Object n = tag.getConstructor().newInstance();
        Method sS = tag.getMethod("setString",String.class,String.class),
               sI = tag.getMethod("setInt",String.class,int.class);
        sS.invoke(n,"id","Sign");
        sI.invoke(n,"x",pos.getX()); sI.invoke(n,"y",pos.getY()); sI.invoke(n,"z",pos.getZ());
        for(int i=0;i<4;i++)
            sS.invoke(n,"Text"+(i+1),"{\"translate\":\""+(i<keys.size()?keys.get(i):"")+"\"}");
        sS.invoke(n,"is_waxed","0b"); sS.invoke(n,"is_editable","1b");
        return n;
    }

    /* misc helpers ----------------------------------------------------- */
    private int guiTicks(){ try{ return (int) cfg.getClass().getMethod("getTranslatableGuiVisibleTicks").invoke(cfg); }
                            catch(Throwable t){ return 2; } }
    private Material findSign(){ for(String id:new String[]{"OAK_SIGN","SIGN_POST","SIGN"})
        try{ return Material.valueOf(id);}catch(IllegalArgumentException ignored){} return Material.SIGN;}
    private int minor(){ String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\."); return v.length>1?Integer.parseInt(v[1]):0; }
    private void debug(String m){ if(cfg.isDebugMode()) plugin.getLogger().info("[Translatable] "+m); }
}