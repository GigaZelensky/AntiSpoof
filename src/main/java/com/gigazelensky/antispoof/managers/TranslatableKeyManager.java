package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grim-style translatable-key detector.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* ------------------------------------------------ probe state --- */
    private static final class Probe {
        private final List<Map.Entry<String,String>> keys;
        private int cursor = 0;
        Probe(LinkedHashMap<String,String> src){ this.keys = List.copyOf(src.entrySet()); }
        List<Map.Entry<String,String>> next(int n){
            if(cursor >= keys.size()) return Collections.emptyList();
            List<Map.Entry<String,String>> slice = keys.subList(cursor, Math.min(cursor+n, keys.size()));
            cursor += slice.size();
            return slice;
        }
        boolean done(){ return cursor >= keys.size(); }
    }

    /* ------------------------------------------------ fields -------- */
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    private final Map<UUID,Probe>    probes        = new ConcurrentHashMap<>();
    private final Map<UUID,Integer>  failCounters  = new ConcurrentHashMap<>();
    private final Map<UUID,Long>     cooldown      = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg){
        this.plugin = pl; this.detect = dm; this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ------------------------------------------------ join ---------- */
    @EventHandler public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    /* ------------------------------------------------ public entry -- */
    public void probe(Player p){
        if(!cfg.isTranslatableKeysEnabled() || minor() < 20) return;

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

        Probe probe = new Probe(map);
        probes.put(p.getUniqueId(), probe);
        failCounters.put(p.getUniqueId(), 0);

        sendNextBatch(p);
    }

    /* ------------------------------------------------ bundle loop --- */
    private void sendNextBatch(Player p){
        Probe probe = probes.get(p.getUniqueId());
        if(probe == null) return;

        List<Map.Entry<String,String>> slice = probe.next(4);
        if(slice.isEmpty()) return;

        if(!sendBundleReflect(p, slice)){
            int fails = failCounters.merge(p.getUniqueId(), 1, Integer::sum);
            if(fails <= 1){
                debug("bundle unavailable, falling back to real sign ("+fails+")");
                fallbackRealSign(p, slice);
            }else{
                debug("bundle failed twice, disabling for "+p.getName());
                probes.remove(p.getUniqueId());
            }
            return;
        }

        if(!probe.done()){
            Bukkit.getScheduler().runTask(plugin, () -> sendNextBatch(p));
        }
    }

    /* ------------------------------------------------ UPDATE_SIGN --- */
    @Override public void onPacketReceive(PacketReceiveEvent e){
        if(e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId()); if(probe == null) return;

        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        List<String> required = cfg.getTranslatableRequiredKeys();
        boolean anyTranslated = false, requiredMiss = false;

        int idx = 0;
        for(Map.Entry<String,String> en : probe.keys.subList(probe.cursor - 4, probe.cursor)){
            if(idx >= lines.length) break;
            String key = en.getKey(), friendly = en.getValue(), returned = lines[idx++];
            if(!key.equals(returned)){
                anyTranslated = true;
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, friendly);
            }else if(required.contains(key)){
                requiredMiss = true;
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, friendly);
            }
        }

        if(requiredMiss){ probes.remove(p.getUniqueId()); return; }
        if(!anyTranslated && probe.done())
            detect.handleTranslatable(p, TranslatableEventType.ZERO, "-");
    }

    /* ------------------------------------------------ bundle (reflection) */
    private boolean sendBundleReflect(Player player, List<Map.Entry<String,String>> keys){
        try{
            Class<?> bcCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos = new Vector3i(player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64,-59),
                    player.getLocation().getBlockZ());

            /* 1️⃣ BLOCK_CHANGE ------------------------------------------------ */
            Object bc = createBlockChange(bcCls, pos);

            /* 2️⃣ BLOCK_ENTITY_DATA (robust ctor resolver) ------------------- */
            Object nbt = buildNBT(pos, keys);
            Object be  = null;
            for(Constructor<?> c : beCls.getConstructors()){
                Class<?>[] p = c.getParameterTypes();
                if(p.length == 3 && Vector3i.class.isAssignableFrom(p[0])){
                    Object act = (p[1]==byte.class||p[1]==Byte.class)? (byte)9
                                : (p[1]==short.class||p[1]==Short.class)? (short)9 : 9;
                    be = c.newInstance(pos, act, nbt); break;
                }
            }
            if(be == null){ // setters-fallback
                be = beCls.getConstructor().newInstance();
                Method setPos = Arrays.stream(beCls.getMethods())
                        .filter(m->m.getName().equals("setBlockPosition")||m.getName().equals("setLocation"))
                        .findFirst().orElseThrow();
                setPos.invoke(be,pos);
                beCls.getMethod("setAction", byte.class).invoke(be, (byte)9);
                beCls.getMethod("setNbtData", Object.class).invoke(be, nbt);
            }

            /* 3️⃣ OPEN & 4️⃣ CLOSE ----------------------------------------- */
            Object open  = new WrapperPlayServerOpenSignEditor(pos, true);
            Object close = cwCls.getConstructor(int.class).newInstance(0);

            Object bundle = buCls.getConstructor(Object[].class)
                                 .newInstance((Object)new Object[]{bc, be, open, close});

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bundle);
            return true;
        }
        catch(ClassNotFoundException e){ return false; }
        catch(Throwable t){ debug("bundle error: "+t); return false; }
    }

    /* ------------------------------------------------ BLOCK_CHANGE helper */
    private Object createBlockChange(Class<?> bcCls, Vector3i pos) throws Exception{
        // 1) (Vector3i, Material, int)
        for(Constructor<?> c : bcCls.getConstructors()){
            Class<?>[] p = c.getParameterTypes();
            if(p.length == 3 && Vector3i.class.isAssignableFrom(p[0]) && Material.class.isAssignableFrom(p[1])){
                return c.newInstance(pos, findSign(), 0);
            }
        }
        // 2) (Vector3i, int)
        try{ return bcCls.getConstructor(Vector3i.class,int.class).newInstance(pos,63); }
        catch(NoSuchMethodException ignored){}

        // 3) (Material, Vector3i)
        try{ return bcCls.getConstructor(Material.class,Vector3i.class).newInstance(findSign(),pos); }
        catch(NoSuchMethodException ignored){}

        // 4) no-arg + setters
        Object bc = bcCls.getConstructor().newInstance();
        Method setPos = Arrays.stream(bcCls.getMethods())
                .filter(m->m.getName().equals("setBlockPosition")||m.getName().equals("setLocation"))
                .findFirst().orElseThrow();
        setPos.invoke(bc, pos);
        try{
            Class<?> wbdCls = Class.forName(bcCls.getName()+"$WrappedBlockData");
            Object wbd = wbdCls.getMethod("create", Material.class).invoke(null, findSign());
            bcCls.getMethod("setBlockData", wbdCls).invoke(bc, wbd);
        }catch(Throwable ignored){}
        return bc;
    }

    /* ------------------------------------------------ fallback sign -- */
    private void fallbackRealSign(Player p, List<Map.Entry<String,String>> slice){
        Block b = p.getWorld().getBlockAt(p.getLocation().getBlockX(), -64, p.getLocation().getBlockZ());
        Material prev = b.getType(); b.setType(findSign(), false);

        BlockState st = b.getState();
        if(st instanceof Sign s){
            int i=0; for(Map.Entry<String,String> en : slice){ if(i==4) break; s.setLine(i++, en.getKey()); }
            try{ s.getClass().getMethod("setEditable", boolean.class).invoke(s,true);}catch(Throwable ignored){}
            s.update(true,false);
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p,new WrapperPlayServerOpenSignEditor(new Vector3i(b.getX(),b.getY(),b.getZ()),true));
        Bukkit.getScheduler().runTaskLater(plugin, ()->b.setType(prev,false),2L);
    }

    /* ------------------------------------------------ NBT builder ---- */
    private Object buildNBT(Vector3i pos, List<Map.Entry<String,String>> keys) throws Exception{
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tag = Class.forName("net.minecraft.server."+v+".NBTTagCompound");
        Object n = tag.getConstructor().newInstance();
        Method sS = tag.getMethod("setString", String.class, String.class),
               sI = tag.getMethod("setInt",    String.class, int.class);
        sS.invoke(n,"id","Sign");
        sI.invoke(n,"x",pos.getX()); sI.invoke(n,"y",pos.getY()); sI.invoke(n,"z",pos.getZ());
        for(int i=0;i<4;i++){
            String key = i < keys.size() ? keys.get(i).getKey() : "";
            sS.invoke(n,"Text"+(i+1), "{\"translate\":\""+key+"\"}");
        }
        sS.invoke(n,"is_waxed","0b"); sS.invoke(n,"is_editable","1b");
        return n;
    }

    /* ------------------------------------------------ helpers -------- */
    private Material findSign(){
        for(String id : new String[]{"OAK_SIGN","SIGN_POST","SIGN"})
            try{ return Material.valueOf(id);}catch(IllegalArgumentException ignored){}
        return Material.SIGN;
    }
    private int minor(){
        String[] v = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return v.length>1?Integer.parseInt(v[1]):0;
    }
    private void debug(String m){ if(cfg.isDebugMode()) plugin.getLogger().info("[Translatable] "+m); }
}