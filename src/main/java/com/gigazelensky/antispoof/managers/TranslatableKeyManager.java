package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sequential, race-free translatable-key probe.
 * <p>
 *  • Sends exactly one invisible sign at a time and waits for the response (or timeout) before the next.<br>
 *  • Fourth line of every probe carries a short UID so late replies are ignored.<br>
 *  • After each reply / timeout the fake sign is reverted for that player (air, which is what was there before).<br>
 *  • Works on 1.8.8 – no use of {@code Block#getBlockData()} or modern Spigot APIs.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* -------------------------------------------------------------------------
     *  Small holder for a running probe on one player
     * ---------------------------------------------------------------------- */
    private static final class Probe {
        final Iterator<Map.Entry<String, String>> keys;          // key -> label
        final Set<String> translated = new HashSet<>();
        final int retriesLeft;
        String currentKey = null;                                // key we’re waiting on
        String uid = null;                                       // 4-char unique tag on line-4
        BukkitTask timeoutTask = null;
        long startTime = System.currentTimeMillis();

        Probe(Map<String, String> src, int retries) {
            this.keys        = src.entrySet().iterator();
            this.retriesLeft = retries;
        }

        void cancelTimeout() { if (timeoutTask != null) timeoutTask.cancel(); }
    }

    /* --------------------------------------------------------------------- */
    private final AntiSpoofPlugin plugin;
    private final DetectionManager  detect;
    private final ConfigManager     cfg;

    private final Map<UUID, Probe>  probes   = new HashMap<>();
    private final Map<UUID, Long>   cooldown = new HashMap<>();

    private static final int RESPONSE_TIMEOUT_TICKS = 20;             // 1 s
    private static final int AIR_GLOBAL_ID          = 0;              // id for air block packets

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detect,
                                  ConfigManager cfg)
    {
        this.plugin = plugin;
        this.detect = detect;
        this.cfg    = cfg;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* =======================================================================
     *  Join / quit
     * ==================================================================== */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        int firstDelay = cfg.getTranslatableFirstDelay();
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> beginProbe(e.getPlayer(), cfg.getTranslatableRetryCount(), false),
                firstDelay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Probe p = probes.remove(e.getPlayer().getUniqueId());
        if (p != null) p.cancelTimeout();
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    /* =======================================================================
     *  Public helper (used by /antispoof keybind ...)
     * ==================================================================== */
    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender debug) {
        Probe p = new Probe(Collections.singletonMap(key, key), 0);
        probes.put(target.getUniqueId(), p);
        p.currentKey = key;
        p.uid        = randomUID();
        p.startTime  = System.currentTimeMillis();
        sendSign(target, p);

        p.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            debug.sendMessage(target.getName() + " | timeout");
            probes.remove(target.getUniqueId());
        }, RESPONSE_TIMEOUT_TICKS);
    }

    /* =======================================================================
     *  Core probing logic
     * ==================================================================== */
    private void beginProbe(Player player, int retries, boolean ignoreCd) {
        if (!player.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now  = System.currentTimeMillis();
        long cool = cfg.getTranslatableCooldown() * 1000L;
        if (!ignoreCd && now - cooldown.getOrDefault(player.getUniqueId(), 0L) < cool) return;
        if (!ignoreCd) cooldown.put(player.getUniqueId(), now);

        Map<String,String> keys = cfg.getTranslatableModsWithLabels();
        if (keys.isEmpty()) return;

        Probe probe = new Probe(keys, retries);
        probes.put(player.getUniqueId(), probe);
        advance(player, probe);
    }

    /** Move to next key (called after each response / timeout). */
    private void advance(Player p, Probe probe) {
        probe.cancelTimeout();

        if (!probe.keys.hasNext()) {
            finish(p, probe);
            probes.remove(p.getUniqueId());
            return;
        }

        Map.Entry<String,String> nxt = probe.keys.next();
        probe.currentKey = nxt.getKey();
        probe.uid        = randomUID();
        sendSign(p, probe);

        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // treat as "no translation"
            recordResult(p, probe, false, probe.currentKey);
            advance(p, probe);
        }, RESPONSE_TIMEOUT_TICKS);
    }

    /* --------------------------------------------------------------------- */
    private void finish(Player p, Probe probe) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Finished key probe for "
                    + p.getName() + ". Translated: " + probe.translated);
        }

        // check required keys
        List<String> required = cfg.getTranslatableRequiredKeys();
        if (!required.isEmpty()) {
            for (String r : required) {
                if (!probe.translated.contains(r)) {
                    detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, r);
                }
            }
        }

        // retries?
        if (probe.retriesLeft > 0) {
            int left = probe.retriesLeft - 1;
            int  ivl = cfg.getTranslatableRetryInterval();
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> beginProbe(p, left, true), ivl);
        }
    }

    /* =======================================================================
     *  Packet → response
     * ==================================================================== */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player p = (Player) e.getPlayer();
        Probe  probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKey == null) return;

        String[] lines = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (lines.length < 4) return;
        // make sure this response belongs to the current probe (line-4 UID)
        if (!probe.uid.equals(lines[3])) return;

        probe.cancelTimeout();

        boolean translated =
                !lines[0].isEmpty() &&
                !lines[0].equals(probe.currentKey) &&
                !lines[0].startsWith("{\"translate\"");
        recordResult(p, probe, translated, probe.currentKey);

        // small delay so client has closed the sign GUI
        Bukkit.getScheduler().runTaskLater(plugin, () -> advance(p, probe),
                cfg.getTranslatableKeyDelay());
    }

    private void recordResult(Player p, Probe probe, boolean translated, String key) {
        if (translated) {
            probe.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        }
        // clean up fake sign for that player (send AIR)
        Vector3i pos = signPosFor(p);
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_GLOBAL_ID));
    }

    /* =======================================================================
     *  Sign helpers
     * ==================================================================== */
    private void sendSign(Player target, Probe probe) {
        Vector3i pos  = signPosFor(target);
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        // -- block change to sign
        WrappedBlockState signState;
        try {
            signState = (WrappedBlockState)
                    StateTypes.OAK_SIGN.getClass().getMethod("createBlockData")
                                       .invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            signState = StateTypes.OAK_SIGN.createBlockState(cv);
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target,
                        new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        // -- sign NBT
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        if (modern) {
            // front_text compound
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            msgs.addTag(new NBTString("{\"translate\":\"" + probe.currentKey + "\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"" + probe.uid + "\"}"));

            NBTCompound front = new NBTCompound();
            front.setTag("messages", msgs);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte)0));
            nbt.setTag("front_text", front);
        } else {
            nbt.setTag("Text1", new NBTString("{\"translate\":\"" + probe.currentKey + "\"}"));
            nbt.setTag("Text2", new NBTString(""));
            nbt.setTag("Text3", new NBTString(""));
            nbt.setTag("Text4", new NBTString(probe.uid));
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockEntityData(pos,
                        BlockEntityTypes.SIGN, nbt));

        // -- open sign GUI then close instantly (invisible)
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerCloseWindow(0));
    }

    /** The fake sign is always placed 24 blocks above the player (or world max-2). */
    private Vector3i signPosFor(Player p) {
        World w   = p.getWorld();
        int x     = p.getLocation().getBlockX();
        int y     = Math.min(w.getMaxHeight() - 2, p.getLocation().getBlockY() + 24);
        int z     = p.getLocation().getBlockZ();
        return new Vector3i(x, y, z);
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }
}
