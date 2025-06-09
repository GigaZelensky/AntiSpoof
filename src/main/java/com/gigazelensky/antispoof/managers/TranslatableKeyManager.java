package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.managers.ConfigManager.TranslatableModConfig;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder manager for translatable key detection.
 * Actual packet logic omitted due to environment limitations.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detectionManager;
    private final ConfigManager config;

    private final Map<UUID, ProbeInfo> probes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin, DetectionManager detectionManager, ConfigManager config) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.config = config;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    private static class ProbeInfo {
        final LinkedHashMap<String, TranslatableModConfig> keys;
        final long timestamp;
        final boolean required;

        ProbeInfo(LinkedHashMap<String, TranslatableModConfig> keys, boolean required) {
            this.keys = keys;
            this.required = required;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /** Simple integer vector for block positions. */
    private static class Vector3i {
        private final int x, y, z;
        Vector3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int getX() { return x; }
        int getY() { return y; }
        int getZ() { return z; }
    }

    public void probe(Player player) {
        if (!config.isTranslatableKeysEnabled() || minor() < 20) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(id, 0L) < config.getTranslatableCooldown()) {
            return;
        }
        cooldown.put(id, now);

        LinkedHashMap<String, TranslatableModConfig> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.getTranslatableTestKeysPlain().entrySet()) {
            keys.put(e.getKey(), config.getTranslatableModConfig(e.getKey()));
        }
        boolean required = !config.getTranslatableRequiredKeys().isEmpty();

        ProbeInfo info = new ProbeInfo(keys, required);
        probes.put(id, info);
        sendBundle(player, info);
    }

    private void handleReply(Player player, ProbeInfo info, String[] lines) {
        if (info == null) return;
        boolean anyTranslated = false;
        int i = 0;
        for (Map.Entry<String, TranslatableModConfig> entry : info.keys.entrySet()) {
            if (i >= lines.length) break;
            String raw = entry.getKey();
            String label = entry.getValue().getLabel();
            if (!lines[i].equals(raw)) {
                anyTranslated = true;
                detectionManager.handleTranslatable(player, TranslatableEventType.TRANSLATED, label);
            } else if (info.required && config.getTranslatableRequiredKeys().contains(raw)) {
                detectionManager.handleTranslatable(player, TranslatableEventType.REQUIRED_MISS, label);
            }
            i++;
        }
        if (!anyTranslated) {
            detectionManager.handleTranslatable(player, TranslatableEventType.ZERO, "-");
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        ProbeInfo info = probes.remove(p.getUniqueId());
        if (info == null) return;
        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        handleReply(p, info, lines);
    }

    // Bukkit minor version, e.g. 21 for "1.21.1"
    private int minor() {
        String[] s = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        return s.length > 1 ? Integer.parseInt(s[1]) : 0;
    }

    // Write a VAR_INT per Mojang spec (full 32-bit)
    private static byte[] varInt(int v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            if ((v & 0xFFFFFF80) == 0) {
                out.write(v);
                break;
            }
            out.write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        return out.toByteArray();
    }

    // Block pos (Long) 26|12|26 bits (same as NMS)
    private static byte[] blockPos(Vector3i p) {
        long val = ((p.getX() & 0x3ffffffL) << 38) | ((p.getY() & 0xfffL) << 26) | (p.getZ() & 0x3ffffffL);
        return ByteBuffer.allocate(8).putLong(val).array();
    }

    // Convert CraftBukkit NBTTagCompound → byte[]
    private static byte[] nbtBytes(Object nbt) throws Exception {
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tools = Class.forName("net.minecraft.server." + v + ".NBTCompressedStreamTools");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        tools.getMethod("a", Class.forName("net.minecraft.server." + v + ".NBTBase"), DataOutput.class)
                .invoke(null, nbt, dos);
        return out.toByteArray();
    }

    private Object buildSignNBT(Vector3i pos, List<String> keys) throws Exception {
        String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> tag = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
        Object nbt = tag.newInstance();
        Method setS = tag.getMethod("setString", String.class, String.class);
        Method setI = tag.getMethod("setInt", String.class, int.class);
        setS.invoke(nbt, "id", "Sign");
        setI.invoke(nbt, "x", pos.getX());
        setI.invoke(nbt, "y", pos.getY());
        setI.invoke(nbt, "z", pos.getZ());
        for (int i = 0; i < 4; i++) {
            setS.invoke(nbt, "Text" + (i + 1), "{\"translate\":\"" + keys.get(i) + "\"}");
        }
        setS.invoke(nbt, "is_waxed", "0b");
        setS.invoke(nbt, "is_editable", "1b");
        return nbt;
    }

    private void sendBundle(Player player, ProbeInfo info) {
        try {
            Vector3i pos = new Vector3i(player.getLocation().getBlockX(),
                    ThreadLocalRandom.current().nextInt(-64, -59),
                    player.getLocation().getBlockZ());

            List<String> raw = new ArrayList<>(info.keys.keySet());
            while (raw.size() < 4) raw.add("");
            byte[] nbt = nbtBytes(buildSignNBT(pos, raw));

            List<byte[]> packets = new ArrayList<>();

            byte[] p1 = concat(new byte[]{0x0B}, blockPos(pos), varInt(63));
            packets.add(concat(varInt(p1.length), p1));

            byte[] p2 = concat(new byte[]{0x0C}, blockPos(pos), new byte[]{9}, nbt);
            packets.add(concat(varInt(p2.length), p2));

            byte[] p3 = concat(new byte[]{0x32}, blockPos(pos));
            packets.add(concat(varInt(p3.length), p3));

            byte[] p4 = concat(new byte[]{0x2D}, varInt(0));
            packets.add(concat(varInt(p4.length), p4));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x66);
            out.write(varInt(packets.size()));
            for (byte[] b : packets) out.write(b);

            Object pm = PacketEvents.getAPI().getPlayerManager();
            try {
                pm.getClass().getMethod("sendRawPacket", Player.class, byte[].class)
                        .invoke(pm, player, out.toByteArray());
            } catch (NoSuchMethodException ex) {
                // Fallback for older PacketEvents versions
                pm.getClass().getMethod("sendPacket", Player.class, byte[].class)
                        .invoke(pm, player, out.toByteArray());
            }
        } catch (Exception ex) {
            if (config.isDebugMode()) {
                plugin.getLogger().warning("[Debug] Failed to send translatable probe: " + ex.getMessage());
            }
        }
    }

    private static byte[] concat(byte[]... arr) {
        int len = 0; for (byte[] a : arr) len += a.length;
        byte[] out = new byte[len]; int pos = 0;
        for (byte[] a : arr) { System.arraycopy(a, 0, out, pos, a.length); pos += a.length; }
        return out;
    }
}
