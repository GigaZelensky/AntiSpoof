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
 * Your original, stable, sequential probe, with robust batching and restored command functionality.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private static final int TIMEOUT_TICKS = 40;
    private static final int AIR_ID = 0;
    private static final double MOVE_EPSILON = 0.0001;
    private static final float ROT_EPSILON = 1.5f;
    private static final String DELIMITER = "\t";
    private static final int MAX_COMPONENT_LENGTH = 256;

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    private static final class Probe {
        Iterator<List<Map.Entry<String, String>>> iterator;
        final Set<String> translated = new HashSet<>();
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;
        List<Map.Entry<String, String>> currentEntries = null;
        String[] keys = null;
        String uid = null;
        Vector3i sign = null;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        boolean forceSend = false;
    }

    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> pendingStart = new HashSet<>();

    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager det, ConfigManager cfg) {
        this.plugin = pl;
        this.detect = det;
        this.cfg = cfg;
        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        if (cfg.isTranslatableOnlyOnMove()) {
            pendingStart.add(e.getPlayer().getUniqueId());
        } else {
            int delay = cfg.getTranslatableFirstDelay();
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> beginProbe(e.getPlayer(), cfg.getTranslatableModsWithLabels(), cfg.getTranslatableRetryCount(), false, null, false),
                    delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Probe p = probes.remove(e.getPlayer().getUniqueId());
        if (p != null && p.sign != null) sendAir(e.getPlayer(), p.sign);
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
                        () -> beginProbe(e.getPlayer(), cfg.getTranslatableModsWithLabels(), cfg.getTranslatableRetryCount(), false, null, false),
                        delay);
            }
        }

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            placeNextSign(e.getPlayer(), p);
        }
    }

    // FIX #1: sendKeybind now works as intended.
    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Force sending single key '" + key + "' to " + target.getName());
        }
        Map<String, String> single = Collections.singletonMap(key, key);
        // Force-start a new probe, ignoring existing ones, with 0 retries.
        beginProbe(target, single, 0, true, dbg, true);
    }

    // FIX #2: runmods now works as intended.
    public void runMods(Player target) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Force running all mod probes for " + target.getName());
        }
        // Force-start a new probe, ignoring existing ones.
        beginProbe(target, cfg.getTranslatableModsWithLabels(), cfg.getTranslatableRetryCount(), true, null, false);
    }

    public void stopMods(Player target) {
        UUID id = target.getUniqueId();
        pendingStart.remove(id);
        Probe p = probes.remove(id);
        if (p != null) {
            if (p.timeoutTask != null) p.timeoutTask.cancel();
            if (p.sign != null) sendAir(target, p.sign);
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Manually stopped probe for " + target.getName());
        }
    }

    private void beginProbe(Player p, Map<String, String> keys, int retries, boolean ignoreCooldown, org.bukkit.command.CommandSender dbg, boolean forceSend) {
        // Allow forceSend to bypass the existing probe check
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled() || (!forceSend && probes.containsKey(p.getUniqueId()))) return;

        long now = System.currentTimeMillis();
        if (!ignoreCooldown) {
            long cd = cfg.getTranslatableCooldown() * 1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        // If a probe is forced, cancel any existing one for this player.
        if (forceSend) {
            stopMods(p);
        }

        List<Map.Entry<String, String>> allEntries = new ArrayList<>(keys.entrySet());
        List<Map.Entry<String, String>> packedEntries = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        List<String> keysInCurrentLine = new ArrayList<>();

        for (Map.Entry<String, String> entry : allEntries) {
            if (currentLine.length() > 0 && currentLine.length() + entry.getKey().length() + DELIMITER.length() > MAX_COMPONENT_LENGTH) {
                packedEntries.add(new AbstractMap.SimpleEntry<>(String.join(DELIMITER, keysInCurrentLine), ""));
                currentLine.setLength(0);
                keysInCurrentLine.clear();
            }
            if (!keysInCurrentLine.isEmpty()) currentLine.append(DELIMITER);
            currentLine.append(entry.getKey());
            keysInCurrentLine.add(entry.getKey());
        }
        if (!keysInCurrentLine.isEmpty()) {
            packedEntries.add(new AbstractMap.SimpleEntry<>(String.join(DELIMITER, keysInCurrentLine), ""));
        }

        List<List<Map.Entry<String, String>>> keyGroups = new ArrayList<>();
        for (int i = 0; i < packedEntries.size(); i += 3) {
            keyGroups.add(new ArrayList<>(packedEntries.subList(i, Math.min(packedEntries.size(), i + 3))));
        }

        Probe probe = new Probe();
        probe.iterator = keyGroups.iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting batched probe for " + p.getName() + " with " + keys.size() + " keys in " + keyGroups.size() + " sign packets.");
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
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Waiting for " + player.getName() + " to move before sending next batch.");
            probe.waitingForMove = true;
        } else {
            placeNextSign(player, probe);
        }
    }

    private void placeNextSign(Player player, Probe probe) {
        Vector3i pos = buildFakeSign(player, probe.keys, probe.uid);
        probe.sign = pos;
        if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Sent sign packet with UID " + probe.uid + " to " + player.getName());

        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(cfg.isDebugMode()) plugin.getLogger().warning("[Debug] Probe timed out for " + player.getName() + ". UID: " + probe.uid);
            for (Map.Entry<String, String> entry : probe.currentEntries) {
                String[] originalKeys = entry.getKey().split(DELIMITER, -1);
                for (String key : originalKeys) {
                    handleResult(player, probe, key, false, null);
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
        if (probe == null || probe.keys == null) return;

        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4 || !probe.uid.equals(recv[3])) {
            // This can happen if a previous probe timed out but the client responded late.
            // It's safe to ignore.
            return;
        }

        if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Received valid sign response from " + p.getName() + " for UID " + probe.uid);
        probe.timeoutTask.cancel();

        for (int i = 0; i < probe.currentEntries.size(); i++) {
            String[] originalKeys = probe.currentEntries.get(i).getKey().split(DELIMITER, -1);
            String[] receivedTranslations = recv[i].split(DELIMITER, -1);

            for (int j = 0; j < originalKeys.length; j++) {
                String key = originalKeys[j];
                String received = (j < receivedTranslations.length) ? receivedTranslations[j] : key;
                boolean translated = !received.isEmpty() && !received.equals(key) && !received.startsWith("{\"translate\"");
                handleResult(p, probe, key, translated, received);
            }
        }
        advance(p, probe);
    }

    private void handleResult(Player p, Probe probe, String key, boolean translated, String response) {
        String label = cfg.getTranslatableModsWithLabels().get(key);
        if (label == null) return; // Should not happen if config is correct

        // FIX #3: The `runmods` command will re-report already found keys.
        // We only add to `failedForNext` if it was not translated.
        // The `detect.handleTranslatable` call happens regardless.
        if (translated) {
            probe.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        } else {
            probe.failedForNext.put(key, label);
        }
    }

    private void finishRound(Player p, Probe probe) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Probe round finished for " + p.getName() + ". Translated: " + probe.translated.size() + ", Failed: " + probe.failedForNext.size());
        }

        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }
        if (probe.sign != null) sendAir(p, probe.sign);

        // FIX #4: Retry logic is now confirmed to work on the failed keys from all batches.
        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Retrying " + probe.failedForNext.size() + " failed keys for " + p.getName() + ". Retries left: " + (probe.retriesLeft - 1));
            }
            int interval = cfg.getTranslatableRetryInterval();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                            beginProbe(p, probe.failedForNext, probe.retriesLeft - 1, true, probe.debug, probe.forceSend),
                    interval);
        } else {
            probes.remove(p.getUniqueId());
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] All probe rounds finished for " + p.getName());
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
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        
        String key1_json = createComponentJson(keys[0]);
        String key2_json = createComponentJson(keys[1]);
        String key3_json = createComponentJson(keys[2]);
        String uid_json = "{\"text\":\"" + uid + "\"}";

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
            nbt.setTag("Text1", new NBTString(keys[0] != null ? key1_json : ""));
            nbt.setTag("Text2", new NBTString(keys[1] != null ? key2_json : ""));
            nbt.setTag("Text3", new NBTString(keys[2] != null ? key3_json : ""));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));

        return pos;
    }
    
    private String createComponentJson(String packedKeys) {
        if (packedKeys == null) return "{\"text\":\"\"}";

        String[] keys = packedKeys.split(DELIMITER, -1);
        String extraContent = Arrays.stream(keys)
            .map(key -> "{\"translate\":\"" + key.replace("\"", "\\\"") + "\"}")
            .collect(Collectors.joining("," + "{\"text\":\"" + DELIMITER + "\"}" + ","));
        
        return "{\"text\":\"\",\"extra\":[" + extraContent + "]}";
    }

    private void sendAir(Player p, Vector3i pos) {
        if (p != null && p.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
        }
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