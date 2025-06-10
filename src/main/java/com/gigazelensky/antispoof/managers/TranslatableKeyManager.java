package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.*;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private static final class Probe {
        final Iterator<Map.Entry<String, String>> iterator;
        final Set<String> translated = new HashSet<>();
        String currentKey = null;

        Probe(Map<String, String> src) {
            iterator = src.entrySet().iterator();
        }
    }

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;
    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detect,
                                  ConfigManager cfg) {
        this.plugin = plugin;
        this.detect = detect;
        this.cfg = cfg;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /** Compatibility shim for legacy code that still calls probe() directly. */
    public void probe(Player player) {
        startProbe(player);
    }

    /* ------------------------------------------------------------------ */
    /*  Join hook                                                         */
    /* ------------------------------------------------------------------ */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> startProbe(e.getPlayer()),
                cfg.getTranslatableFirstDelay()
        );
    }

    /* ------------------------------------------------------------------ */
    /*  Probe lifecycle                                                   */
    /* ------------------------------------------------------------------ */
    private void startProbe(Player p) {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(p.getUniqueId(), 0L)
                < cfg.getTranslatableCooldown() * 1000L) return;
        cooldown.put(p.getUniqueId(), now);

        Map<String, String> keys = cfg.getTranslatableModsWithLabels();
        if (keys.isEmpty()) return;

        Probe probe = new Probe(keys);
        probes.put(p.getUniqueId(), probe);
        sendNextKey(p, probe);
    }

    private void sendNextKey(Player p, Probe probe) {
        if (!p.isOnline()) {
            probes.remove(p.getUniqueId());
            return;
        }

        if (!probe.iterator.hasNext()) {
            finishProbe(p, probe);
            probes.remove(p.getUniqueId());
            return;
        }

        Map.Entry<String, String> entry = probe.iterator.next();
        probe.currentKey = entry.getKey();
        sendSignProbe(p, entry.getKey());

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> sendNextKey(p, probe),
                Math.max(1, cfg.getTranslatableGuiTicks())
        );
    }

    private void finishProbe(Player p, Probe probe) {
        for (String required : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(required)) {
                detect.handleTranslatable(p,
                        TranslatableEventType.REQUIRED_MISS,
                        required);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Sign-probe packet burst                                           */
    /* ------------------------------------------------------------------ */
    private void sendSignProbe(Player target, String key) {
        Vector3i pos = new Vector3i(
                target.getLocation().getBlockX(),
                target.getLocation().getBlockY() + 3,
                target.getLocation().getBlockZ());

        ClientVersion cv =
                PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));

        if (modern) {
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            msgs.addTag(new NBTString("{\"translate\":\"" + key + "\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));

            NBTCompound front = new NBTCompound();
            front.setTag("messages", msgs);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte) 0));
            nbt.setTag("front_text", front);
        } else {
            String json = "{\"translate\":\"" + key + "\"}";
            nbt.setTag("Text1", new NBTString(json));
            nbt.setTag("Text2", new NBTString(""));
            nbt.setTag("Text3", new NBTString(""));
            nbt.setTag("Text4", new NBTString(""));
        }

        WrappedBlockState state;
        try {
            state = (WrappedBlockState) StateTypes.OAK_SIGN
                    .getClass()
                    .getMethod("createBlockData")
                    .invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            state = StateTypes.OAK_SIGN.createBlockState(cv);
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockChange(pos, state.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerCloseWindow(0));
    }

    /* ------------------------------------------------------------------ */
    /*  UPDATE_SIGN handler                                               */
    /* ------------------------------------------------------------------ */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKey == null) return;

        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (lines.length == 0) return;
        String out = lines[0];

        if (!out.isEmpty() && !out.contains("\"translate\"")) {
            probe.translated.add(probe.currentKey);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, probe.currentKey);
        }
    }
}
