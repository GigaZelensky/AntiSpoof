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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sequential translatable-key probe that: <br>
 * • fires only while the player is moving - so the GUI stays unnoticed;<br>
 * • re-tries only keys that failed in the previous round;<br>
 * • always reverts the temporary sign block.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int   TIMEOUT_TICKS   = 20;  // 1 s
    private static final int   AIR_ID          = 0;   // block id for air
    private static final float MOVE_EPSILON    = 0.001f; // minimal x-z motion to count as “moving”

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;

    /* ACTIVE PROBES ******************************************************** */
    private static final class Probe {
        Iterator<Map.Entry<String,String>> iterator;          // remaining keys → label
        final Set<String> translated = new HashSet<>();
        final Map<String,String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;
        /* per-round state */
        String  key   = null;
        String  uid   = null;
        Vector3i sign = null;      // last fake sign position
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
    }
    private final Map<UUID, Probe> probes   = new HashMap<>();
    private final Map<UUID, Long>  cooldown = new HashMap<>();

    /* --------------------------------------------------------------------- */
    public TranslatableKeyManager(AntiSpoofPlugin pl,
                                  DetectionManager det,
                                  ConfigManager cfg)
    {
        this.plugin = pl;
        this.detect = det;
        this.cfg    = cfg;

        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ======================================================================
     *  JOIN / QUIT
     * ==================================================================== */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        int delay = cfg.getTranslatableFirstDelay();
        Bukkit.getScheduler().runTaskLater(plugin,
               () -> beginProbe(e.getPlayer(),
                                cfg.getTranslatableModsWithLabels(),
                                cfg.getTranslatableRetryCount(),
                                false),
               delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Probe p = probes.remove(e.getPlayer().getUniqueId());
        if (p != null && p.sign != null)
            sendAir(e.getPlayer(), p.sign);          // tidy just in case
        if (p != null && p.timeoutTask != null) p.timeoutTask.cancel();
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    /* ======================================================================
     *  MOVE → fire pending sign if we were waiting
     * ==================================================================== */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getX() == e.getTo().getX() &&
            e.getFrom().getZ() == e.getTo().getZ()) return;   // no horizontal move

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            placeNextSign(e.getPlayer(), p);                  // now!
        }
    }

    /* ======================================================================
     *  /antispoof keybind … overrides waiting-for-move
     * ==================================================================== */
    public void sendKeybind(Player target, String key,
                            org.bukkit.command.CommandSender dbg)
    {
        Map<String,String> single = Collections.singletonMap(key, key);
        beginProbe(target, single, 0, true);      // ignore cooldown

        // quick debug feedback
        dbg.sendMessage("Sent probe for " + key + " to " + target.getName());
    }

    /* ======================================================================
     *  MAIN PROBE LIFECYCLE
     * ==================================================================== */
    private void beginProbe(Player p,
                            Map<String,String> keys,
                            int retries,
                            boolean ignoreCooldown)
    {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        long cd  = cfg.getTranslatableCooldown()*1000L;
        if (!ignoreCooldown && now - cooldown.getOrDefault(p.getUniqueId(),0L) < cd) return;
        if (!ignoreCooldown) cooldown.put(p.getUniqueId(), now);

        Probe probe = new Probe();
        probe.iterator    = keys.entrySet().iterator();
        probe.retriesLeft = retries;
        probes.put(p.getUniqueId(), probe);

        advance(p, probe);
    }

    /** Advance to next key (or finish) */
    private void advance(Player player, Probe probe) {
        // cancel stale timeout
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        // finished?
        if (!probe.iterator.hasNext()) {
            finishRound(player, probe);
            return;
        }

        // revert previous sign (if any)
        if (probe.sign != null) sendAir(player, probe.sign);

        Map.Entry<String,String> nxt = probe.iterator.next();
        probe.key = nxt.getKey();  // current key
        probe.uid = randomUID();

        // we’ll place the sign only when player is moving
        if (player.getVelocity().lengthSquared() < MOVE_EPSILON) {
            probe.waitingForMove = true;
        } else {
            placeNextSign(player, probe);
        }
    }

    private void placeNextSign(Player player, Probe probe) {
        Vector3i pos = buildFakeSign(player, probe.key, probe.uid);
        probe.sign = pos;

        // timeout
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // no translation
            handleResult(player, probe, false);
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }

    /* ======================================================================
     *  RECEIVE UPDATE_SIGN
     * ==================================================================== */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player  p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.key == null) return;

        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4) return;
        if (!probe.uid.equals(recv[3])) return;          // not for current probe

        probe.timeoutTask.cancel();

        boolean translated =
                !recv[0].isEmpty() &&
                !recv[0].equals(probe.key) &&
                !recv[0].startsWith("{\"translate\"");
        handleResult(p, probe, translated);
        advance(p, probe);
    }

    private void handleResult(Player p, Probe probe, boolean translated) {
        if (translated) {
            probe.translated.add(probe.key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, probe.key);
        } else {
            probe.failedForNext.put(probe.key, probe.key);
        }
        // tidy sign
        if (probe.sign != null) sendAir(p, probe.sign);
    }

    /* ======================================================================
     *  ROUND FINISHED
     * ==================================================================== */
    private void finishRound(Player p, Probe probe) {
        // Required keys that failed
        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }

        // schedule retry of ONLY failed keys
        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            int interval = cfg.getTranslatableRetryInterval();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    beginProbe(p, probe.failedForNext, probe.retriesLeft-1, true),
                    interval);
        } else {
            probes.remove(p.getUniqueId());
        }
    }

    /* ======================================================================
     *  LOW-LEVEL sign building / cleanup
     * ==================================================================== */
    private Vector3i buildFakeSign(Player target, String key, String uid) {
        ClientVersion cv   = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern     = cv.isNewerThanOrEquals(ClientVersion.V_1_20);
        Vector3i pos       = signPos(target);

        // block change
        WrappedBlockState signState;
        try {
            signState = (WrappedBlockState) StateTypes.OAK_SIGN.getClass()
                    .getMethod("createBlockData").invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            signState = StateTypes.OAK_SIGN.createBlockState(cv);
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        // nbt
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        if (modern) {
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            msgs.addTag(new NBTString("{\"translate\":\"" + key + "\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"\"}"));
            msgs.addTag(new NBTString("{\"text\":\"" + uid + "\"}"));
            NBTCompound front = new NBTCompound();
            front.setTag("messages", msgs);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte)0));
            nbt.setTag("front_text", front);
        } else {
            nbt.setTag("Text1", new NBTString("{\"translate\":\"" + key + "\"}"));
            nbt.setTag("Text2", new NBTString(""));
            nbt.setTag("Text3", new NBTString(""));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockEntityData(pos,
                        BlockEntityTypes.SIGN, nbt));

        // GUI open/close
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));

        return pos;
    }

    private void sendAir(Player p, Vector3i pos) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
    }

    private Vector3i signPos(Player p) {
        World w = p.getWorld();
        int x   = p.getLocation().getBlockX();
        int y   = Math.min(w.getMaxHeight() - 2, p.getLocation().getBlockY() + 24);
        int z   = p.getLocation().getBlockZ();
        return new Vector3i(x, y, z);
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }
}
