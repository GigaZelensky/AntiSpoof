package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private static final class ProbeInfo {
        final LinkedHashMap<String, String> keys;
        ProbeInfo(LinkedHashMap<String, String> k) { keys = k; }
    }

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;
    private final Map<UUID, ProbeInfo> probes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager dm, ConfigManager cfg) {
        this.plugin = pl;
        this.detect = dm;
        this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> probe(e.getPlayer()),
                cfg.getTranslatableFirstDelay());
    }

    public void probe(Player p) {
        if (!cfg.isTranslatableKeysEnabled() || !p.isOnline()) return;

        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < (cfg.getTranslatableCooldown() * 1000L)) return;
        cooldown.put(p.getUniqueId(), now);

        LinkedHashMap<String, String> map = new LinkedHashMap<>(cfg.getTranslatableModsWithLabels());
        if (map.isEmpty()) {
            debug("no keys configured");
            return;
        }

        ProbeInfo info = new ProbeInfo(map);
        probes.put(p.getUniqueId(), info);

        // Try the bundle method first; if it fails, use the fixed fallback.
        if (!sendBundleReflectively(p, info)) {
            fallbackWithPackets(p, info);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player p = (Player) e.getPlayer();
        ProbeInfo pi = probes.remove(p.getUniqueId());
        if (pi == null) return;

        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        boolean any = false;
        int idx = 0;
        for (Map.Entry<String, String> en : pi.keys.entrySet()) {
            if (idx >= lines.length) break;
            
            if (!lines[idx].equals(en.getKey()) && !lines[idx].isEmpty()) {
                any = true;
                detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, en.getValue());
            }
            idx++;
        }
        if (!any) {
            detect.handleTranslatable(p, TranslatableEventType.ZERO, "-");
        }
    }

    private boolean sendBundleReflectively(Player player, ProbeInfo info) {
        try {
            Class<?> bcCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange");
            Class<?> beCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            Class<?> cwCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow");
            Class<?> buCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle");

            Vector3i pos = new Vector3i(player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64, -59),
                    player.getLocation().getBlockZ());

            Object bc = createBlockChange(bcCls, pos);

            Object be = beCls.getConstructor().newInstance();
            setField(be, "blockEntityPosition", pos);
            setField(be, "actionId", (byte) 9);
            setField(be, "nbtData", buildNmsNBT(pos, new ArrayList<>(info.keys.keySet())));

            Object open = new WrapperPlayServerOpenSignEditor(pos, true);
            Object close = cwCls.getConstructor(int.class).newInstance(0);

            Object bundle = buCls.getConstructor(Object[].class)
                    .newInstance((Object) new Object[]{bc, be, open, close});
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bundle);
            debug("bundle sent");
            return true;
        } catch (Exception e) {
            debug("bundle reflect err: " + e.getMessage());
            return false;
        }
    }
    
    // THE FIX IS HERE: This is the fallback method, now corrected to send proper packets.
    @SuppressWarnings("deprecation")
    private void fallbackWithPackets(Player player, ProbeInfo info) {
        debug("Using packet-based fallback.");
        try {
            Vector3i pos = new Vector3i(player.getLocation().getBlockX(), -64, player.getLocation().getBlockZ());
            
            // Build the raw NMS NBT object.
            Object nmsNbtObject = buildNmsNBT(pos, new ArrayList<>(info.keys.keySet()));
            
            // Create the wrapper and inject the NBT data via reflection.
            WrapperPlayServerBlockEntityData blockEntityData = new WrapperPlayServerBlockEntityData(null);
            setField(blockEntityData, "blockEntityPosition", pos);
            setField(blockEntityData, "actionId", (byte) 9);
            setField(blockEntityData, "nbtData", nmsNbtObject);

            // Create the other required packets.
            int blockId = findSignMaterial().getId();
            WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(pos, blockId);
            WrapperPlayServerOpenSignEditor openSign = new WrapperPlayServerOpenSignEditor(pos, true);

            // Send the packets individually.
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChange);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntityData);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSign);

        } catch (Exception e) {
            debug("Packet-based fallback failed: " + e.getMessage());
        }
    }

    private Object createBlockChange(Class<?> bcCls, Vector3i pos) throws Exception {
        try {
            return bcCls.getConstructor(Vector3i.class, int.class).newInstance(pos, 63);
        } catch (NoSuchMethodException ignore) {}
        try {
            Object bc = bcCls.getConstructor().newInstance();
            bcCls.getMethod("setBlockPosition", Vector3i.class).invoke(bc, pos);
            Class<?> wbd = Class.forName(bcCls.getName() + "$WrappedBlockData");
            Object blk = wbd.getMethod("create", Material.class).invoke(null, findSignMaterial());
            bcCls.getMethod("setBlockData", wbd).invoke(bc, blk);
            return bc;
        } catch (NoSuchMethodException ignore) {}
        return bcCls.getConstructor(Material.class, Vector3i.class).newInstance(findSignMaterial(), pos);
    }
    
    private void setField(Object target, String fieldName, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object buildNmsNBT(Vector3i pos, List<String> raw) throws Exception {
        while (raw.size() < 4) raw.add("");
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tag = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
        Object n = tag.getConstructor().newInstance();
        Method sS = tag.getMethod("setString", String.class, String.class);
        Method sI = tag.getMethod("setInt", String.class, int.class);
        sS.invoke(n, "id", "Sign");
        sI.invoke(n, "x", pos.getX());
        sI.invoke(n, "y", pos.getY());
        sI.invoke(n, "z", pos.getZ());
        for (int i = 0; i < 4; i++) {
            sS.invoke(n, "Text" + (i + 1), "{\"translate\":\"" + raw.get(i) + "\"}");
        }
        sS.invoke(n, "is_waxed", "0b");
        sS.invoke(n, "is_editable", "1b");
        return n;
    }

    private Material findSignMaterial() {
        for (String id : new String[]{"OAK_SIGN", "SIGN_POST", "SIGN"}) {
            try {
                return Material.valueOf(id);
            } catch (IllegalArgumentException ignored) {}
        }
        return Material.SIGN;
    }

    private void debug(String m) {
        if (cfg.isDebugMode()) plugin.getLogger().info("[Translatable] " + m);
    }
}