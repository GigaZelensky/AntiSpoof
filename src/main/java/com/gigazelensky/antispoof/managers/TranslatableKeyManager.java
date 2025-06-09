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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private static final class ProbeInfo {
        final LinkedHashMap<String,String> keys;
        ProbeInfo(LinkedHashMap<String,String> k){keys=k;}
    }

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;
    private final Map<UUID,ProbeInfo> probes   = new ConcurrentHashMap<>();
    private final Map<UUID,Long>      cooldown = new ConcurrentHashMap<>();
    private final String nmsVersion = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg){
        this.plugin=pl; this.detect=dm; this.cfg=cfg;
        // Register both Bukkit events and this class as a global packet listener
        Bukkit.getPluginManager().registerEvents(this,pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    public void register(){}

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(plugin,()->probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    public void probe(Player p){
        if(p == null || !p.isOnline()) return;
        if(!cfg.isTranslatableKeysEnabled() || minor()<20) return;

        long now=System.currentTimeMillis();
        if(now-cooldown.getOrDefault(p.getUniqueId(),0L) < cfg.getTranslatableCooldown()) return;
        cooldown.put(p.getUniqueId(),now);

        LinkedHashMap<String, String> map = new LinkedHashMap<>(cfg.getTranslatableModsWithLabels());

        if(map.isEmpty()){
            debug("no keys configured");
            return;
        }

        ProbeInfo info=new ProbeInfo(map);
        // Add the player to the map of players we are currently probing
        probes.put(p.getUniqueId(),info);
        
        // Schedule a timeout to remove them if they don't respond
        Bukkit.getScheduler().runTaskLater(plugin, () -> probes.remove(p.getUniqueId()), 60L);

        // Send the packets
        if(!sendBundleReflectively(p,info)) fallbackRealSign(p,info);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e){
        // We only care about sign updates
        if(e.getPacketType()!=PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player)e.getPlayer();
        if (p == null) return;

        // **THE KEY LOGIC**: Only process this packet if it came from a player we are currently probing.
        ProbeInfo pi = probes.remove(p.getUniqueId());
        if (pi == null) {
            return; // Not a player we are probing, so we ignore the packet completely.
        }

        String[] lines=new WrapperPlayClientUpdateSign(e).getTextLines();
        boolean any=false;
        int idx=0;
        for(Map.Entry<String,String> en:pi.keys.entrySet()){
            if(idx>=lines.length) break;
            if(!lines[idx].equals(en.getKey())){
                any=true;
                detect.handleTranslatable(p,TranslatableEventType.TRANSLATED,en.getValue());
            }
            idx++;
        }
        if(!any) {
            detect.handleTranslatable(p,TranslatableEventType.ZERO,"-");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean sendBundleReflectively(Player player, ProbeInfo info) {
        try {
            Class<?> bcCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos = new Vector3i(player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64, -59),
                    player.getLocation().getBlockZ());

            Object blockChangePacket = bcCls.getConstructor(Vector3i.class, int.class).newInstance(pos, findSign().getId());

            Object blockEntityPacket = beCls.getConstructor().newInstance();
            beCls.getMethod("setPosition", Vector3i.class).invoke(blockEntityPacket, pos);
            beCls.getMethod("setAction", byte.class).invoke(blockEntityPacket, (byte) 9);
            beCls.getMethod("setNbtData", Object.class).invoke(blockEntityPacket, buildNBT(pos, new ArrayList<>(info.keys.keySet())));

            Object openSignPacket = new WrapperPlayServerOpenSignEditor(pos, true);
            Object closeWindowPacket = cwCls.getConstructor(int.class).newInstance(0);

            Object bundle = buCls.getConstructor(Object[].class)
                    .newInstance((Object) new Object[]{blockChangePacket, blockEntityPacket, openSignPacket, closeWindowPacket});

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bundle);
            debug("bundle sent successfully");
            return true;
        } catch (Throwable t) {
            debug("bundle reflect err: " + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void fallbackRealSign(Player p, ProbeInfo info) {
        debug("fallback sign (NMS method)");
        try {
            int x = p.getLocation().getBlockX();
            int y = ThreadLocalRandom.current().nextInt(-64, -59);
            int z = p.getLocation().getBlockZ();

            Class<?> blockChangeClass = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Object blockChange = blockChangeClass.getConstructor(Vector3i.class, int.class).newInstance(new Vector3i(x, y, z), findSign().getId());
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, blockChange);

            Class<?> nmsBlockPosClass = Class.forName("net.minecraft.server." + nmsVersion + ".BlockPosition");
            Object nmsBlockPos = nmsBlockPosClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            
            Class<?> nmsChatSerializer = Class.forName("net.minecraft.server." + nmsVersion + ".IChatBaseComponent$ChatSerializer");
            Method fromJsonMethod = nmsChatSerializer.getMethod("a", String.class);
            
            Class<?> iChatBaseComponent = Class.forName("net.minecraft.server." + nmsVersion + ".IChatBaseComponent");
            Object[] lines = (Object[]) Array.newInstance(iChatBaseComponent, 4);
            
            int i = 0;
            for (String key : info.keys.keySet()) {
                if (i >= 4) break;
                lines[i] = fromJsonMethod.invoke(null, "{\"translate\":\"" + key + "\"}");
                i++;
            }
            for (; i < 4; i++) {
                lines[i] = fromJsonMethod.invoke(null, "{\"text\":\"\"}");
            }

            Class<?> packetUpdateSignClass = Class.forName("net.minecraft.server." + nmsVersion + ".PacketPlayOutUpdateSign");
            Class<?> linesArrayClass = Array.newInstance(iChatBaseComponent, 0).getClass();
            Constructor<?> packetConstructor = packetUpdateSignClass.getConstructor(nmsBlockPosClass, linesArrayClass);
            Object updateSignPacket = packetConstructor.newInstance(nmsBlockPos, lines);
            
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, updateSignPacket);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerOpenSignEditor(new Vector3i(x, y, z), true));
        } catch (Throwable t) {
            debug("NMS fallback sign method FAILED: " + t.getClass().getName() + " " + t.getMessage());
        }
    }

    private Object buildNBT(Vector3i pos,List<String> raw)throws Exception{
        while(raw.size()<4) raw.add("");
        Class<?> tag=Class.forName("net.minecraft.server." + nmsVersion + ".NBTTagCompound");
        Object n=tag.getConstructor().newInstance();
        Method sS=tag.getMethod("setString",String.class,String.class),
               sI=tag.getMethod("setInt",String.class,int.class);
        sS.invoke(n,"id","Sign");
        sI.invoke(n,"x",pos.getX()); sI.invoke(n,"y",pos.getY()); sI.invoke(n,"z",pos.getZ());
        for(int i=0;i<4;i++) sS.invoke(n,"Text"+(i+1),"{\"translate\":\""+raw.get(i)+"\"}");
        sS.invoke(n,"is_waxed","0b"); sS.invoke(n,"is_editable","1b");
        return n;
    }

    private Material findSign(){ for(String id:new String[]{"OAK_SIGN","SIGN_POST","SIGN"})
        try{return Material.valueOf(id);}catch(IllegalArgumentException ignored){} return Material.SIGN;}
    private int minor(){ String[] v=Bukkit.getBukkitVersion().split("-")[0].split("\\."); return v.length>1?Integer.parseInt(v[1]):0; }
    private void debug(String m){ if(cfg.isDebugMode()) plugin.getLogger().info("[Translatable] "+m);}
}