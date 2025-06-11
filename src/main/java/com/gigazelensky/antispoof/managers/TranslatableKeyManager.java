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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * High-density, single-packet translatable-key probe driven by configuration.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    // --- Constants ---
    private static final int MAX_LINE_LENGTH = 380;
    private static final String DELIMITER = "\t";
    private static final String DELIMITER_JSON = "{\"text\":\"" + DELIMITER + "\"}";
    private static final int PROBE_TIMEOUT_TICKS = 40;

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    // --- State Tracking ---
    private static final class ProbeData {
        final List<String> modKeys;
        final List<String> vanillaTranslationKeys;
        final List<String> vanillaKeybindKeys;

        ProbeData(List<String> modKeys, List<String> vanillaTranslationKeys, List<String> vanillaKeybindKeys) {
            this.modKeys = modKeys;
            this.vanillaTranslationKeys = vanillaTranslationKeys;
            this.vanillaKeybindKeys = vanillaKeybindKeys;
        }
    }
    
    private final Map<UUID, String> playerToProbeId = new ConcurrentHashMap<>();
    private final Map<String, ProbeData> probeIdToData = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> pendingMoveProbes = Collections.newSetFromMap(new ConcurrentHashMap<>());


    public TranslatableKeyManager(AntiSpoofPlugin pl, DetectionManager det, ConfigManager cfg) {
        this.plugin = pl;
        this.detect = det;
        this.cfg = cfg;

        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    // --- Event Handlers for Probe Lifecycle ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        
        Player player = e.getPlayer();
        if (cfg.isTranslatableOnlyOnMove()) {
            pendingMoveProbes.add(player.getUniqueId());
        } else {
            int delay = cfg.getTranslatableFirstDelay();
            Bukkit.getScheduler().runTaskLater(plugin, () -> runProbe(player, false), delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        pendingMoveProbes.remove(uuid);
        cooldowns.remove(uuid);
        String probeId = playerToProbeId.remove(uuid);
        if (probeId != null) {
            probeIdToData.remove(probeId);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()) {
            UUID uuid = e.getPlayer().getUniqueId();
            if (pendingMoveProbes.remove(uuid)) {
                runProbe(e.getPlayer(), false);
            }
        }
    }

    // --- Public API for Commands ---

    public void runMods(Player target) {
        if (target != null && target.isOnline()) {
            runProbe(target, true);
        }
    }

    public void stopMods(Player target) {
        pendingMoveProbes.remove(target.getUniqueId());
    }

    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        dbg.sendMessage("Debug keybind sending is not adapted for the massive probe system yet.");
    }


    // --- Core Probing Logic ---

    private void runProbe(Player player, boolean ignoreCooldown) {
        if (!player.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        // Cooldown check
        long now = System.currentTimeMillis();
        long cd = cfg.getTranslatableCooldown() * 1000L;
        if (!ignoreCooldown && now - cooldowns.getOrDefault(player.getUniqueId(), 0L) < cd) return;
        if (!ignoreCooldown) cooldowns.put(player.getUniqueId(), now);

        // 1. GATHER KEYS FROM CONFIG
        List<String> modKeys = new ArrayList<>(cfg.getTranslatableModsWithLabels().keySet());
        List<String> vanillaTranslationKeys = cfg.getVanillaTranslationKeys();
        List<String> vanillaKeybindKeys = cfg.getVanillaKeybindKeys();

        // 2. Pack keys into JSON payloads
        String line1Payload = buildPackedJsonPayload(modKeys, "translate");
        String line2Payload = buildPackedJsonPayload(vanillaTranslationKeys, "translate");
        String line3Payload = buildPackedJsonPayload(vanillaKeybindKeys, "keybind");

        // 3. Prepare probe data for response validation
        String probeId = randomUID();
        ProbeData data = new ProbeData(
            getKeysFromPayload(line1Payload, "translate"),
            getKeysFromPayload(line2Payload, "translate"),
            getKeysFromPayload(line3Payload, "keybind")
        );

        playerToProbeId.put(player.getUniqueId(), probeId);
        probeIdToData.put(probeId, data);

        // 4. Send the sign packet
        sendMassiveSignProbe(player, probeId, line1Payload, line2Payload, line3Payload);
        
        // 5. Schedule timeout cleanup
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (probeId.equals(playerToProbeId.get(player.getUniqueId()))) {
                playerToProbeId.remove(player.getUniqueId());
                probeIdToData.remove(probeId);
                if (cfg.isDebugMode()) {
                    plugin.getLogger().warning("Player " + player.getName() + " did not respond to probe " + probeId);
                }
            }
        }, PROBE_TIMEOUT_TICKS);
    }

    // --- Packet Construction and Sending ---

    private String buildPackedJsonPayload(List<String> keys, String type) {
        if (keys == null || keys.isEmpty()) return "{\"text\":\"\"}";

        String components = keys.stream()
            .map(key -> "{\"" + type + "\":\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
            .collect(Collectors.joining("," + DELIMITER_JSON + ","));

        String fullJson = "{\"text\":\"\",\"extra\":[" + components + "]}";

        if (fullJson.length() > MAX_LINE_LENGTH) {
            int lastComma = fullJson.lastIndexOf(',', MAX_LINE_LENGTH);
            return (lastComma != -1) ? fullJson.substring(0, lastComma) + "]}" : "{\"text\":\"\"}";
        }
        return fullJson;
    }

    private List<String> getKeysFromPayload(String jsonPayload, String type) {
        List<String> keys = new ArrayList<>();
        String keyPattern = "\"" + type + "\":\"";
        int lastIndex = 0;
        while(lastIndex != -1){
            lastIndex = jsonPayload.indexOf(keyPattern, lastIndex);
            if(lastIndex != -1){
                int startIndex = lastIndex + keyPattern.length();
                int endIndex = jsonPayload.indexOf("\"", startIndex);
                if (endIndex != -1) {
                    keys.add(jsonPayload.substring(startIndex, endIndex).replace("\\\"", "\"").replace("\\\\", "\\"));
                    lastIndex = endIndex;
                } else break;
            }
        }
        return keys;
    }
    
    private void sendMassiveSignProbe(Player target, String uid, String line1, String line2, String line3) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        Vector3i pos = getFakeSignPosition(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState(cv);
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        if (cv.isNewerThanOrEquals(ClientVersion.V_1_20)) {
            NBTCompound frontText = new NBTCompound();
            NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
            messages.addTag(new NBTString(line1));
            messages.addTag(new NBTString(line2));
            messages.addTag(new NBTString(line3));
            messages.addTag(new NBTString("{\"text\":\"" + uid + "\"}"));
            frontText.setTag("messages", messages);
            frontText.setTag("color", new NBTString("black"));
            frontText.setTag("has_glowing_text", new NBTByte((byte)0));
            
            nbt.setTag("front_text", frontText);
            
            // ================== THE FIX ==================
            // Create a new, separate NBTCompound for the back text
            // and copy the values manually. This avoids the protected clone() method.
            NBTCompound backText = new NBTCompound();
            backText.setTag("messages", messages); // Re-use the same messages list
            backText.setTag("color", new NBTString("black"));
            backText.setTag("has_glowing_text", new NBTByte((byte)0));
            nbt.setTag("back_text", backText);
            // =============================================

        } else {
            nbt.setTag("Text1", new NBTString(line1));
            nbt.setTag("Text2", new NBTString(line2));
            nbt.setTag("Text3", new NBTString(line3));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));

        boolean isFrontSide = cv.isNewerThanOrEquals(ClientVersion.V_1_20);
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, isFrontSide));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));
    }

    // --- Packet Reception and Processing ---

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player p = (Player) e.getPlayer();
        String expectedProbeId = playerToProbeId.get(p.getUniqueId());
        if (expectedProbeId == null) return;

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(e);
        String[] receivedLines = packet.getTextLines();
        if (receivedLines.length < 4 || !expectedProbeId.equals(receivedLines[3])) return;

        ProbeData sentData = probeIdToData.get(expectedProbeId);
        if (sentData == null) return;

        playerToProbeId.remove(p.getUniqueId());
        probeIdToData.remove(expectedProbeId);

        processLine(p, "Mod Key", sentData.modKeys, receivedLines[0]);
        processLine(p, "Vanilla Translation", sentData.vanillaTranslationKeys, receivedLines[1]);
        processLine(p, "Vanilla Keybind", sentData.vanillaKeybindKeys, receivedLines[2]);
    }

    private void processLine(Player player, String lineType, List<String> sentKeys, String receivedLine) {
        String[] translations = receivedLine.split(DELIMITER, -1);

        for (int i = 0; i < sentKeys.size() && i < translations.length; i++) {
            String originalKey = sentKeys.get(i);
            String clientResponse = translations[i];

            if (!originalKey.equals(clientResponse)) {
                if (cfg.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Player " + player.getName() + " translated " + lineType + ": '" + originalKey + "' -> '" + clientResponse + "'");
                }
                
                if (lineType.equals("Mod Key")) {
                    detect.handleTranslatable(player, TranslatableEventType.TRANSLATED, originalKey);
                }
            }
        }
    }

    // --- Helper Methods ---

    private Vector3i getFakeSignPosition(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = Math.min(w.getMaxHeight() - 2, p.getLocation().getBlockY() + 50);
        int z = p.getLocation().getBlockZ();
        return new Vector3i(x, y, z);
    }
    
    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }
}