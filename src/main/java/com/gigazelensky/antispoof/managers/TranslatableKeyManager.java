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
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Your original, stable, sequential probe, minimally modified for high-density batching.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int   TIMEOUT_TICKS   = 40;  // 2s, safer for potentially slow clients
    private static final int   AIR_ID          = 0;
    private static final double MOVE_EPSILON    = 0.0001;
    private static final float ROT_EPSILON     = 1.5f;

    // --- CONSTANT FOR BATCHING ---
    private static final String DELIMITER = "\t"; // Tab character is a safe, non-printable delimiter

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;

    /* ACTIVE PROBES (Original structure is preserved) */
    private static final class Probe {
        Iterator<List<String>> iterator;
        final Set<String> translated = new HashSet<>();
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;
        int totalBatches;
        int currentBatchNum = 0;
        /* per-round state */
        List<String> currentKeyLines = null;
        String  uid   = null;
        Vector3i sign = null;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        long sendTime;
        boolean forceSend = false;
    }

    private final Map<UUID, Probe> probes   = new HashMap<>();
    private final Map<UUID, Long>  cooldown = new HashMap<>();
    private final Set<UUID> pendingStart = new HashSet<>();

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
     *  UNCHANGED LIFECYCLE & COMMAND METHODS
     * ==================================================================== */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        if (cfg.isTranslatableOnlyOnMove()) {
            pendingStart.add(e.getPlayer().getUniqueId());
        } else {
            int delay = cfg.getTranslatableFirstDelay();
            Bukkit.getScheduler().runTaskLater(plugin,
                   () -> beginProbe(e.getPlayer(),
                                    cfg.getTranslatableModsWithLabels(),
                                    cfg.getTranslatableRetryCount(),
                                    false,
                                    null,
                                    false),
                   delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // This now calls the restored stopMods method for clean cleanup
        stopMods(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        float dy = Math.abs(e.getTo().getYaw() - e.getFrom().getYaw());
        float dp = Math.abs(e.getTo().getPitch() - e.getFrom().getPitch());
        boolean moved = dx * dx + dz * dz >= MOVE_EPSILON || dy > ROT_EPSILON || dp > ROT_EPSILON;
        if (!moved) return;

        if (cfg.isTranslatableOnlyOnMove()) {
            UUID id = e.getPlayer().getUniqueId();
            if (pendingStart.remove(id)) {
                int delay = cfg.getTranslatableFirstDelay();
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> beginProbe(e.getPlayer(),
                                         cfg.getTranslatableModsWithLabels(),
                                         cfg.getTranslatableRetryCount(),
                                         false,
                                         null,
                                         false),
                        delay);
            }
        }

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            placeNextSign(e.getPlayer(), p);
        }
    }

    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        Map<String,String> single = Collections.singletonMap(key, key);
        // Force re-probe for debug command
        if (probes.containsKey(target.getUniqueId())) {
            stopMods(target);
        }
        beginProbe(target, single, 0, true, dbg, true);
    }

    public void runMods(Player target) {
        // Force re-probe by stopping any existing probe first
        if (probes.containsKey(target.getUniqueId())) {
            stopMods(target);
        }
        beginProbe(target,
                   cfg.getTranslatableModsWithLabels(),
                   cfg.getTranslatableRetryCount(),
                   true,
                   null,
                   false);
    }

    public void stopMods(Player target) {
        UUID id = target.getUniqueId();
        pendingStart.remove(id);
        Probe p = probes.remove(id);
        if (p != null) {
            if (p.timeoutTask != null) p.timeoutTask.cancel();
            if (p.sign != null) sendAir(target, p.sign);
        }
    }

    /* ======================================================================
     *  MAIN PROBE LIFECYCLE (EDITED FOR BATCHING)
     * ==================================================================== */
    private void beginProbe(Player p,
                            Map<String, String> keys,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend)
    {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        // Cooldown check
        long now = System.currentTimeMillis();
        if (!ignoreCooldown) {
            long cd  = cfg.getTranslatableCooldown()*1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(),0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        final int maxLineLength = cfg.getTranslatableMaxLineLength();

        // --- BATCHING LOGIC: Fills all 3 lines of a sign before moving to the next ---
        List<List<String>> allSignBatches = new ArrayList<>();
        if (!keys.isEmpty()) {
            List<StringBuilder> currentSignBuilders = new ArrayList<>(Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder()));
            int currentLineIndex = 0;

            for (Map.Entry<String, String> entry : keys.entrySet()) {
                String key = entry.getKey();

                // If current line is full, try next line.
                if (currentSignBuilders.get(currentLineIndex).length() > 0 &&
                    currentSignBuilders.get(currentLineIndex).length() + DELIMITER.length() + key.length() > maxLineLength) {
                    currentLineIndex++;
                }

                // If all lines on this sign are full, finalize the sign and start a new one.
                if (currentLineIndex >= 3) {
                    allSignBatches.add(currentSignBuilders.stream().map(StringBuilder::toString).collect(Collectors.toList()));
                    currentSignBuilders = new ArrayList<>(Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder()));
                    currentLineIndex = 0;
                }

                // Add the key to the current line.
                if (currentSignBuilders.get(currentLineIndex).length() > 0) {
                    currentSignBuilders.get(currentLineIndex).append(DELIMITER);
                }
                currentSignBuilders.get(currentLineIndex).append(key);
            }

            // Add the last sign if it has any content.
            if (currentSignBuilders.get(0).length() > 0) {
                allSignBatches.add(currentSignBuilders.stream().map(StringBuilder::toString).collect(Collectors.toList()));
            }
        }
        // --- END BATCHING LOGIC ---

        Probe probe = new Probe();
        probe.iterator = allSignBatches.iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probe.totalBatches = allSignBatches.size(); // Store total for debug messages
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting translatable probe for " + p.getName() + " with " + keys.size() + " keys in " + allSignBatches.size() + " sign packets.");
        }
        advance(p, probe);
    }

    private void advance(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        if (!probe.iterator.hasNext()) {
            finishRound(player, probe);
            return;
        }

        if (probe.sign != null) sendAir(player, probe.sign);

        probe.currentKeyLines = probe.iterator.next();
        probe.uid = randomUID();
        probe.currentBatchNum++;

        if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
            probe.waitingForMove = true;
        } else {
            placeNextSign(player, probe);
        }
    }

    private void placeNextSign(Player player, Probe probe) {
        Vector3i pos = buildFakeSign(player, probe.currentKeyLines, probe.uid);
        probe.sign = pos;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending batch " + probe.currentBatchNum + "/" + probe.totalBatches + " to " + player.getName());
        }

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(cfg.isDebugMode()) plugin.getLogger().warning("[Debug] Batch " + probe.currentBatchNum + " timed out for " + player.getName());
            // Unpack and handle each key as a failure on timeout
            for (String line : probe.currentKeyLines) {
                String[] originalKeys = line.split(DELIMITER, -1);
                for (String key : originalKeys) {
                    if (!key.isEmpty()) handleResult(player, probe, key, false, null);
                }
            }
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.currentKeyLines == null) return;

        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4 || !probe.uid.equals(recv[3])) return;

        probe.timeoutTask.cancel();

        for (int i = 0; i < probe.currentKeyLines.size(); i++) {
            if (i >= recv.length) break;
            String[] originalKeys = probe.currentKeyLines.get(i).split(DELIMITER, -1);
            String[] receivedTranslations = recv[i].split(DELIMITER, -1);

            for (int j = 0; j < originalKeys.length; j++) {
                String key = originalKeys[j];
                if (key.isEmpty()) continue;
                String received = (j < receivedTranslations.length) ? receivedTranslations[j] : key;
                boolean translated = !received.isEmpty() && !received.equals(key) && !received.startsWith("{\"translate\"");
                handleResult(p, probe, key, translated, received);
            }
        }
        advance(p, probe);
    }

    private void handleResult(Player p, Probe probe, String key, boolean translated, String response) {
        long ms = System.currentTimeMillis() - probe.sendTime;
        if (probe.debug != null) {
            String text = translated ? response : "";
            probe.debug.sendMessage(org.bukkit.ChatColor.AQUA + p.getName() +
                    org.bukkit.ChatColor.DARK_GRAY + " | " +
                    org.bukkit.ChatColor.GRAY + "Key: \"" +
                    org.bukkit.ChatColor.WHITE + key +
                    org.bukkit.ChatColor.GRAY + "\" Response: \"" +
                    org.bukkit.ChatColor.AQUA + text +
                    org.bukkit.ChatColor.GRAY + "\" Time: " +
                    org.bukkit.ChatColor.AQUA + ms + "ms");
        }

        String label = cfg.getTranslatableModsWithLabels().get(key);
        if (label == null) return;

        if (translated) {
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] " + p.getName() + " translated: '" + key + "' -> '" + response + "' (" + label + ")");
            probe.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        } else {
            probe.failedForNext.put(key, label);
        }
    }

    private void finishRound(Player p, Probe probe) {
        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }
        if (probe.sign != null) sendAir(p, probe.sign);
        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Retrying " + probe.failedForNext.size() + " failed keys for " + p.getName());
            int interval = cfg.getTranslatableRetryInterval();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    beginProbe(p, probe.failedForNext, probe.retriesLeft - 1, true, probe.debug, probe.forceSend),
                    interval);
        } else {
            probes.remove(p.getUniqueId());
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Probe finished for " + p.getName());
            }
        }
    }

    private String createComponentJson(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return "{\"text\":\"\"}";
        String extraContent = Arrays.stream(packedKeys.split(DELIMITER, -1))
            .map(key -> "{\"translate\":\"" + key.replace("\"", "\\\"") + "\"}")
            .collect(Collectors.joining("," + "{\"text\":\"" + DELIMITER + "\"}" + ","));
        return "{\"text\":\"\",\"extra\":[" + extraContent + "]}";
    }

    /* ---------------------------------------------------------------------
     *  VERSION‑ROBUST FORMAT SELECTION
     * ------------------------------------------------------------------- */
    private static boolean shouldUseModernFormat(ClientVersion cv) {
        if (cv == null) return true;                            // default: assume new
        try {
            if (cv.isNewerThanOrEquals(ClientVersion.V_1_20))   // recognised modern
                return true;
        } catch (Throwable ignored) { }
        try {                                                   // unknown but higher protocol
            return cv.getProtocolVersion() >= ClientVersion.V_1_20.getProtocolVersion();
        } catch (Throwable ignored) { }
        return false;                                           // definitely legacy
    }

    private Vector3i buildFakeSign(Player target, List<String> lines, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern   = shouldUseModernFormat(cv);           // <<<< single‑point decision

        Vector3i pos = signPos(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState();
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        String key1_json = createComponentJson(lines.size() > 0 ? lines.get(0) : null);
        String key2_json = createComponentJson(lines.size() > 1 ? lines.get(1) : null);
        String key3_json = createComponentJson(lines.size() > 2 ? lines.get(2) : null);
        String uid_json  = "{\"text\":\"" + uid + "\"}";

        if (modern) {
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            msgs.addTag(new NBTString(key1_json));
            msgs.addTag(new NBTString(key2_json));
            msgs.addTag(new NBTString(key3_json));
            msgs.addTag(new NBTString(uid_json));
            NBTCompound front = new NBTCompound();
            front.setTag("messages", msgs);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte)0));
            nbt.setTag("front_text", front);
            NBTCompound back = new NBTCompound();
            back.setTag("messages", msgs);
            back.setTag("color", new NBTString("black"));
            back.setTag("has_glowing_text", new NBTByte((byte)0));
            nbt.setTag("back_text", back);
        } else {
            nbt.setTag("Text1", new NBTString(lines.size() > 0 ? key1_json : ""));
            nbt.setTag("Text2", new NBTString(lines.size() > 1 ? key2_json : ""));
            nbt.setTag("Text3", new NBTString(lines.size() > 2 ? key3_json : ""));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));
        return pos;
    }

    private void sendAir(Player p, Vector3i pos) {
        if (p != null && p.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
        }
    }

    private Vector3i signPos(Player p) {
        // Place the sign at the corner of an adjacent chunk, high up, to keep it out of sight.
        World w = p.getWorld();
        org.bukkit.Chunk playerChunk = p.getLocation().getChunk();
        int x = (playerChunk.getX() + 1) * 16;
        int z = (playerChunk.getZ() + 1) * 16;
        int y = w.getMaxHeight() - 5;
        return new Vector3i(x, y, z);
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }
}
