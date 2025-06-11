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
 * Sequential, high-density, batched translatable-key probe that: <br>
 * • packs many keys into each sign line, separated by a delimiter;<br>
 * • fires only while the player is moving - so the GUI stays unnoticed;<br>
 * • re-tries only keys that failed in the previous round;<br>
 * • always reverts the temporary sign block.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int TIMEOUT_TICKS = 20;  // 1 s
    private static final int AIR_ID = 0;   // block id for air
    private static final double MOVE_EPSILON = 0.0001;
    private static final float ROT_EPSILON = 1.5f;

    // ================== NEW CONSTANTS FOR BATCHING ==================
    private static final String DELIMITER = "\t"; // Tab character delimiter
    private static final int MAX_COMPONENT_LENGTH = 256; // Safe length for each "translate" component string
    // ================================================================

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    /* ACTIVE PROBES ******************************************************** */
    private static final class Probe {
        Iterator<List<Map.Entry<String, String>>> iterator;
        final Set<String> translated = new HashSet<>();
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;
        /* per-round state */
        List<Map.Entry<String, String>> currentEntries = null;
        String[] keys = null;
        String uid = null;
        Vector3i sign = null;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        long sendTime;
        boolean forceSend = false;
    }

    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> pendingStart = new HashSet<>();

    /* --------------------------------------------------------------------- */
    public TranslatableKeyManager(AntiSpoofPlugin pl,
                                  DetectionManager det,
                                  ConfigManager cfg) {
        this.plugin = pl;
        this.detect = det;
        this.cfg = cfg;

        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ======================================================================
     *  UNCHANGED METHODS (onJoin, onQuit, onMove, commands)
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
        Probe p = probes.remove(e.getPlayer().getUniqueId());
        if (p != null && p.sign != null)
            sendAir(e.getPlayer(), p.sign);
        if (p != null && p.timeoutTask != null) p.timeoutTask.cancel();
        cooldown.remove(e.getPlayer().getUniqueId());
        pendingStart.remove(e.getPlayer().getUniqueId());
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
                if (cfg.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Movement detected, starting probe for " + e.getPlayer().getName());
                }
            }
        }

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            placeNextSign(e.getPlayer(), p);
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Player moved, sending next key to " + e.getPlayer().getName());
            }
        }
    }

    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        Map<String, String> single = Collections.singletonMap(key, key);
        beginProbe(target, single, 0, true, dbg, true);
    }

    public void runMods(Player target) {
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
     *  MAIN PROBE LIFECYCLE - THE ONLY MAJORLY EDITED PART
     * ==================================================================== */
    private void beginProbe(Player p,
                            Map<String, String> keys,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend) {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        long cd = cfg.getTranslatableCooldown() * 1000L;
        if (!ignoreCooldown && now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cd) return;
        if (!ignoreCooldown) cooldown.put(p.getUniqueId(), now);

        // ================== BATCHING LOGIC START ==================
        List<Map.Entry<String, String>> allEntries = new ArrayList<>(keys.entrySet());
        List<Map.Entry<String, String>> packedEntries = new ArrayList<>();

        StringBuilder currentLine = new StringBuilder();
        List<String> keysInCurrentLine = new ArrayList<>();

        for (Map.Entry<String, String> entry : allEntries) {
            // If adding the next key (plus a delimiter) exceeds the safe length,
            // finalize the current packed line and start a new one.
            if (currentLine.length() > 0 && currentLine.length() + entry.getKey().length() + DELIMITER.length() > MAX_COMPONENT_LENGTH) {
                String packedKey = String.join(DELIMITER, keysInCurrentLine);
                // The "label" for this packed entry can just be the first label, it's not critical.
                packedEntries.add(new AbstractMap.SimpleEntry<>(packedKey, keysInCurrentLine.get(0)));

                // Reset for the next line
                currentLine = new StringBuilder();
                keysInCurrentLine.clear();
            }

            // Add the current key to the line being built
            if (!keysInCurrentLine.isEmpty()) {
                currentLine.append(DELIMITER);
            }
            currentLine.append(entry.getKey());
            keysInCurrentLine.add(entry.getKey());
        }

        // Add the last remaining line batch if it's not empty
        if (!keysInCurrentLine.isEmpty()) {
            String packedKey = String.join(DELIMITER, keysInCurrentLine);
            packedEntries.add(new AbstractMap.SimpleEntry<>(packedKey, keysInCurrentLine.get(0)));
        }

        // Now, group the packed lines into chunks of 3 for the sign, same as before.
        List<List<Map.Entry<String, String>>> keyGroups = new ArrayList<>();
        for (int i = 0; i < packedEntries.size(); i += 3) {
            keyGroups.add(new ArrayList<>(packedEntries.subList(i, Math.min(packedEntries.size(), i + 3))));
        }
        // =================== BATCHING LOGIC END ===================

        Probe probe = new Probe();
        probe.iterator = keyGroups.iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting translatable probe for " +
                    p.getName() + " with " + keys.size() + " keys in " + keyGroups.size() + " sign packets (retries=" +
                    retries + ")");
        }

        advance(p, probe);
    }

    /* ======================================================================
     *  UNCHANGED METHODS (advance, placeNextSign)
     * ==================================================================== */
    private void advance(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        if (!probe.iterator.hasNext()) {
            finishRound(player, probe);
            return;
        }

        if (probe.sign != null) sendAir(player, probe.sign);

        probe.currentEntries = probe.iterator.next();
        probe.keys = new String[3];
        for (int i = 0; i < probe.currentEntries.size(); i++) {
            probe.keys[i] = probe.currentEntries.get(i).getKey();
        }
        for (int i = probe.currentEntries.size(); i < 3; i++) {
            probe.keys[i] = null;
        }
        probe.uid = randomUID();

        if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
            probe.waitingForMove = true;
        } else {
            placeNextSign(player, probe);
        }
    }

    private void placeNextSign(Player player, Probe probe) {
        Vector3i pos = buildFakeSign(player, probe.keys, probe.uid);
        probe.sign = pos;

        if (cfg.isDebugMode()) {
            List<String> nonNullKeys = new ArrayList<>();
            for (String key : probe.keys) {
                if (key != null) nonNullKeys.add(key);
            }
            plugin.getLogger().info("[Debug] Sent batched keys to " + player.getName() + " (Batch size: " + nonNullKeys.size() + ")");
        }

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<String, String> entry : probe.currentEntries) {
                // Split the packed key and handle each sub-key as a failure
                String[] originalKeys = entry.getKey().split(DELIMITER, -1);
                for (String originalKey : originalKeys) {
                    handleResult(player, probe, originalKey, cfg.getTranslatableModsWithLabels().get(originalKey), false, null);
                }
            }
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }
    
    /* ======================================================================
     *  RECEIVE UPDATE_SIGN - EDITED TO UNPACK BATCHES
     * ==================================================================== */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.keys == null) return;

        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4) return;
        if (!probe.uid.equals(recv[3])) return;

        probe.timeoutTask.cancel();

        for (int i = 0; i < probe.currentEntries.size(); i++) {
            // The key sent was a packed string of keys
            String[] originalKeys = probe.currentEntries.get(i).getKey().split(DELIMITER, -1);
            // The response is a packed string of translations
            String[] receivedTranslations = recv[i].split(DELIMITER, -1);

            for (int j = 0; j < originalKeys.length; j++) {
                String originalKey = originalKeys[j];
                // Check if the client response has a corresponding part
                String received = (j < receivedTranslations.length) ? receivedTranslations[j] : originalKey;
                
                String label = cfg.getTranslatableModsWithLabels().get(originalKey);
                if (label == null) continue; // Should not happen

                // This is your original, proven logic for determining translation.
                boolean translated = !received.isEmpty() &&
                                     !received.equals(originalKey) &&
                                     !received.startsWith("{\"translate\"");

                handleResult(p, probe, originalKey, label, translated, received);
            }
        }
        advance(p, probe);
    }

    /* ======================================================================
     *  UNCHANGED METHODS (handleResult, finishRound, buildFakeSign, etc.)
     * ==================================================================== */
    private void handleResult(Player p, Probe probe, String key, String label, boolean translated, String response) {
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

        if (cfg.isDebugMode()) {
            String res = translated ? ("\"" + response + "\"") : "<no translation>";
            plugin.getLogger().info("[Debug] Key " + key + " for " + p.getName() +
                    " => " + res + " in " + ms + "ms");
        }

        if (translated) {
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
    
    private Vector3i buildFakeSign(Player target, String[] keys, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);
        Vector3i pos = signPos(target);

        WrappedBlockState signState;
        try {
            signState = (WrappedBlockState) StateTypes.OAK_SIGN.getClass()
                    .getMethod("createBlockData").invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            signState = StateTypes.OAK_SIGN.createBlockState(cv);
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        if (modern) {
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            String key1 = keys[0] != null ? "{\"translate\":\"" + keys[0].replace("\"", "\\\"") + "\"}" : "{\"text\":\"\"}";
            String key2 = keys[1] != null ? "{\"translate\":\"" + keys[1].replace("\"", "\\\"") + "\"}" : "{\"text\":\"\"}";
            String key3 = keys[2] != null ? "{\"translate\":\"" + keys[2].replace("\"", "\\\"") + "\"}" : "{\"text\":\"\"}";
            msgs.addTag(new NBTString(key1));
            msgs.addTag(new NBTString(key2));
            msgs.addTag(new NBTString(key3));
            msgs.addTag(new NBTString("{\"text\":\"" + uid + "\"}"));
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
            String key1 = keys[0] != null ? "{\"translate\":\"" + keys[0].replace("\"", "\\\"") + "\"}" : "";
            String key2 = keys[1] != null ? "{\"translate\":\"" + keys[1].replace("\"", "\\\"") + "\"}" : "";
            String key3 = keys[2] != null ? "{\"translate\":\"" + keys[2].replace("\"", "\\\"") + "\"}" : "";
            nbt.setTag("Text1", new NBTString(key1));
            nbt.setTag("Text2", new NBTString(key2));
            nbt.setTag("Text3", new NBTString(key3));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockEntityData(pos,
                        BlockEntityTypes.SIGN, nbt));

        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        // We close the window via packet now, but this might need adjustment. The original code did not do this.
        // If issues arise, removing this line might be a good first step.
        // For now, let's assume it helps make the probe invisible.
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));


        return pos;
    }

    private void sendAir(Player p, Vector3i pos) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
    }

    private Vector3i signPos(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = Math.min(w.getMaxHeight() - 2, p.getLocation().getBlockY() + 24);
        int z = p.getLocation().getBlockZ();
        return new Vector3i(x, y, z);
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }
}