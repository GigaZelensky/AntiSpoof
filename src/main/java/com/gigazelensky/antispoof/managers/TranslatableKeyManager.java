package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Manages translatable key probes for detecting client modifications.
 * Can start with either a sign-based or anvil-based probe, with the other method used for retries.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    // --- COMMON CONSTANTS ---
    private static final int   TIMEOUT_TICKS   = 40;  // 2s, safer for potentially slow clients
    private static final int   AIR_ID          = 0;
    private static final double MOVE_EPSILON    = 0.0001;
    private static final float ROT_EPSILON     = 1.5f;

    // --- SIGN BATCHING CONSTANTS ---
    private static final String DELIMITER = "\t"; // Tab character is a safe, non-printable delimiter

    // --- ANVIL PROBE CONSTANTS ---
    private static final int ANVIL_WINDOW_ID = 239; // Arbitrary ID for the fake window

    // --- SERVER-SIDE FORMAT DETECTION ---
    private static final boolean INLINE_COMPONENTS = detectInlineComponentFormat();

    // --- VERSION-DEPENDENT ANVIL ID ---
    private static final int ANVIL_CONTAINER_ID = getAnvilId();

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;


    /* --------------------------------------------------------------------- */
    //  PROBE STATE MANAGEMENT
    /* --------------------------------------------------------------------- */

    /**
     * Enum to define the current method being used by a probe.
     */
    private enum ProbeMethod {
        SIGN, ANVIL
    }

    /**
     * Holds the state for an active probe session for a single player.
     */
    private static final class Probe {
        // General state
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        final Set<String> translated = new HashSet<>();
        int retriesLeft;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        boolean forceSend = false;
        long sendTime;
        ProbeMethod method = ProbeMethod.SIGN; // Current method (sign or anvil)

        // Sign-specific state
        Iterator<List<String>> signBatchIterator;
        List<String> currentKeyLines = null;
        String  uid   = null;
        Vector3i signPos = null;
        int totalSignBatches;
        int currentSignBatchNum = 0;

        // Anvil-specific state
        Iterator<String> anvilKeyIterator;
        String currentAnvilKey;
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
            if (p.method == ProbeMethod.SIGN) {
                placeNextSign(e.getPlayer(), p);
            } else if (p.method == ProbeMethod.ANVIL) {
                placeNextAnvil(e.getPlayer(), p, p.currentAnvilKey);
            }
        }
    }

    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        Map<String,String> single = Collections.singletonMap(key, key);
        if (probes.containsKey(target.getUniqueId())) {
            stopMods(target);
        }
        beginProbe(target, single, 0, true, dbg, true);
    }

    public void runMods(Player target) {
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
            if (p.signPos != null) sendAir(target, p.signPos);
        }
    }

    /* ======================================================================
     *  MAIN PROBE LIFECYCLE & DISPATCHER
     * ==================================================================== */

    /**
     * Main entry point for starting a probe. Dispatches to the correct initial
     * method based on configuration.
     */
    private void beginProbe(Player p,
                            Map<String, String> keys,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend) {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        // Cooldown check
        long now = System.currentTimeMillis();
        if (!ignoreCooldown) {
            long cd = cfg.getTranslatableCooldown() * 1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        // Dispatch to the configured starting method
        if (cfg.isTranslatableStartWithAnvil()) {
            beginAnvilProbe(p, keys, retries, dbg, forceSend);
        } else {
            beginSignProbe(p, keys, retries, dbg, forceSend);
        }
    }

    /**
     * Begins the initial sign-based probe.
     */
    private void beginSignProbe(Player p, Map<String, String> keys, int retries, org.bukkit.command.CommandSender dbg, boolean forceSend) {
        List<List<String>> allSignBatches = batchKeysForSign(keys);

        Probe probe = new Probe();
        probe.method = ProbeMethod.SIGN;
        probe.signBatchIterator = allSignBatches.iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probe.totalSignBatches = allSignBatches.size();
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting SIGN probe for " + p.getName() + " with " + keys.size() + " keys in " + allSignBatches.size() + " sign packets.");
        }
        advanceSign(p, probe);
    }

    /**
     * Begins the initial anvil-based probe.
     */
    private void beginAnvilProbe(Player p, Map<String, String> keys, int retries, org.bukkit.command.CommandSender dbg, boolean forceSend) {
        Probe probe = new Probe();
        probe.method = ProbeMethod.ANVIL;
        probe.anvilKeyIterator = new ArrayList<>(keys.keySet()).iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting ANVIL probe for " + p.getName() + " with " + keys.size() + " keys.");
        }
        advanceAnvil(p, probe);
    }

    /**
     * Advances the sign-based probe to the next batch.
     */
    private void advanceSign(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        if (!probe.signBatchIterator.hasNext()) {
            finishRound(player, probe);
            return;
        }

        if (probe.signPos != null) sendAir(player, probe.signPos);

        probe.currentKeyLines = probe.signBatchIterator.next();
        probe.uid = randomUID();
        probe.currentSignBatchNum++;

        if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
            probe.waitingForMove = true;
        } else {
            placeNextSign(player, probe);
        }
    }

    /**
     * Advances the anvil-based probe to the next key.
     */
    private void advanceAnvil(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        if (!probe.anvilKeyIterator.hasNext()) {
            finishRound(player, probe); // No more anvil keys, finish the round
            return;
        }

        probe.currentAnvilKey = probe.anvilKeyIterator.next();

        if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
            probe.waitingForMove = true;
        } else {
            placeNextAnvil(player, probe, probe.currentAnvilKey);
        }
    }

    /**
     * Sends a sign batch to the player.
     */
    private void placeNextSign(Player player, Probe probe) {
        probe.signPos = buildFakeSign(player, probe.currentKeyLines, probe.uid);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending SIGN batch " + probe.currentSignBatchNum + "/" + probe.totalSignBatches + " to " + player.getName());
        }

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cfg.isDebugMode()) plugin.getLogger().warning("[Debug] SIGN batch " + probe.currentSignBatchNum + " timed out for " + player.getName());
            for (String line : probe.currentKeyLines) {
                String[] originalKeys = line.split(DELIMITER, -1);
                for (String key : originalKeys) {
                    if (!key.isEmpty()) handleResult(player, probe, key, false, null);
                }
            }
            advanceSign(player, probe);
        }, TIMEOUT_TICKS);
    }

    /**
     * Sends an anvil probe for a single key to the player.
     */
    private void placeNextAnvil(Player player, Probe probe, String key) {
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending ANVIL probe for key '" + key + "' to " + player.getName());
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerOpenWindow(ANVIL_WINDOW_ID, ANVIL_CONTAINER_ID, Component.text("Repair & Name")));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("display", new NBTCompound());
        nbt.getCompoundTagOrNull("display").setTag("Name", new NBTString("{\"translate\":\"" + key.replace("\"", "\\\"") + "\"}"));
        ItemStack stack = ItemStack.builder().type(ItemTypes.PAPER).amount(1).nbt(nbt).build();

        WrapperPlayServerWindowItems setSlot = new WrapperPlayServerWindowItems(ANVIL_WINDOW_ID, 1, List.of(stack), stack);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, setSlot);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerCloseWindow(ANVIL_WINDOW_ID));

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cfg.isDebugMode()) {
                plugin.getLogger().warning("[Debug] ANVIL probe for key '" + key + "' timed out for " + player.getName());
            }
            handleResult(player, probe, key, false, null); // Timed out = failed
            advanceAnvil(player, probe);
        }, TIMEOUT_TICKS);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null) return;

        if (e.getPacketType() == PacketType.Play.Client.UPDATE_SIGN && probe.method == ProbeMethod.SIGN) {
            if (probe.currentKeyLines == null) return;
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
            advanceSign(p, probe);
        } else if (e.getPacketType() == PacketType.Play.Client.NAME_ITEM && probe.method == ProbeMethod.ANVIL) {
            probe.timeoutTask.cancel();
            WrapperPlayClientNameItem nameItem = new WrapperPlayClientNameItem(e);
            String receivedName = nameItem.getItemName();
            String originalKey = probe.currentAnvilKey;

            boolean translated = !receivedName.isEmpty() && !receivedName.equals(originalKey);
            handleResult(p, probe, originalKey, translated, receivedName);
            advanceAnvil(p, probe);
        }
    }

    /**
     * Processes the result of a single key probe.
     */
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
            if (cfg.isDebugMode()) plugin.getLogger().info("[Debug] " + p.getName() + " translated: '" + key + "' -> '" + response + "' (" + label + ")");
            probe.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        } else {
            probe.failedForNext.put(key, label);
        }
    }

    /* ======================================================================
     *  PROBE RETRY AND FINISHING LOGIC
     * ==================================================================== */

    /**
     * Finishes a probe round and decides whether to retry with the alternate method or stop.
     */
    private void finishRound(Player p, Probe probe) {
        // Check if a retry is possible and necessary
        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            if (probe.method == ProbeMethod.SIGN) {
                // We just finished a SIGN probe, retry with ANVIL
                beginAnvilRetry(p, probe);
            } else { // probe.method == ProbeMethod.ANVIL
                // We just finished an ANVIL probe, retry with SIGN
                beginSignRetry(p, probe);
            }
            return; // A retry has been started, so we exit here
        }

        // If no retry is happening, this is the end of the probe. Do final cleanup.
        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }
        if (probe.signPos != null) sendAir(p, probe.signPos);
        probes.remove(p.getUniqueId());
        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] All probes finished for " + p.getName());
        }
    }

    /**
     * Initiates a retry using the Anvil method for keys that failed the Sign probe.
     */
    private void beginAnvilRetry(Player player, Probe probe) {
        probe.method = ProbeMethod.ANVIL;
        probe.anvilKeyIterator = new ArrayList<>(probe.failedForNext.keySet()).iterator();
        probe.failedForNext.clear();
        probe.retriesLeft--;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Switching to ANVIL retry probe for " + player.getName() + ". Retries left: " + probe.retriesLeft);
        }
        advanceAnvil(player, probe);
    }

    /**
     * Initiates a retry using the Sign method for keys that failed the Anvil probe.
     */
    private void beginSignRetry(Player player, Probe probe) {
        Map<String, String> keysToRetry = new HashMap<>(probe.failedForNext);
        probe.failedForNext.clear();
        probe.retriesLeft--;

        List<List<String>> signBatches = batchKeysForSign(keysToRetry);

        // Update the existing probe state for a sign retry
        probe.method = ProbeMethod.SIGN;
        probe.signBatchIterator = signBatches.iterator();
        probe.totalSignBatches = signBatches.size();
        probe.currentSignBatchNum = 0;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Switching to SIGN retry probe for " + player.getName() + ". Retries left: " + probe.retriesLeft);
        }
        advanceSign(player, probe);
    }


    /* ---------------------------------------------------------------------
     *  PACKET CONSTRUCTION & HELPERS
     * ------------------------------------------------------------------- */

    /**
     * Helper method to batch a map of keys into lists suitable for a sign.
     */
    private List<List<String>> batchKeysForSign(Map<String, String> keys) {
        final int maxLineLength = cfg.getTranslatableMaxLineLength();
        List<List<String>> allSignBatches = new ArrayList<>();
        if (keys.isEmpty()) return allSignBatches;

        List<StringBuilder> currentSignBuilders = new ArrayList<>(Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder()));
        int currentLineIndex = 0;

        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String key = entry.getKey();
            if (currentSignBuilders.get(currentLineIndex).length() > 0 &&
                currentSignBuilders.get(currentLineIndex).length() + DELIMITER.length() + key.length() > maxLineLength) {
                currentLineIndex++;
            }
            if (currentLineIndex >= 3) {
                allSignBatches.add(currentSignBuilders.stream().map(StringBuilder::toString).collect(Collectors.toList()));
                currentSignBuilders = new ArrayList<>(Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder()));
                currentLineIndex = 0;
            }
            if (currentSignBuilders.get(currentLineIndex).length() > 0) {
                currentSignBuilders.get(currentLineIndex).append(DELIMITER);
            }
            currentSignBuilders.get(currentLineIndex).append(key);
        }
        if (currentSignBuilders.get(0).length() > 0) {
            allSignBatches.add(currentSignBuilders.stream().map(StringBuilder::toString).collect(Collectors.toList()));
        }
        return allSignBatches;
    }

    private String createComponentJson(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return "{\"text\":\"\"}";
        String extraContent = Arrays.stream(packedKeys.split(DELIMITER, -1))
            .map(key -> "{\"translate\":\"" + key.replace("\"", "\\\"") + "\"}")
            .collect(Collectors.joining("," + "{\"text\":\"" + DELIMITER + "\"}" + ","));
        return "{\"text\":\"\",\"extra\":[" + extraContent + "]}";
    }

    private static boolean shouldUseModernFormat(ClientVersion cv) {
        if (cv == null) return true;
        try {
            if (cv.isNewerThanOrEquals(ClientVersion.V_1_20))
                return true;
        } catch (Throwable ignored) {}
        try {
            return cv.getProtocolVersion() >= ClientVersion.V_1_20.getProtocolVersion();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectInlineComponentFormat() {
        try {
            String ver = Bukkit.getBukkitVersion().split("-", 2)[0];
            String[] p = ver.split("\\.");
            int major = (p.length > 1) ? Integer.parseInt(p[1]) : 0;
            int patch = (p.length > 2) ? Integer.parseInt(p[2]) : 0;
            return major > 21 || (major == 21 && patch >= 5);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static NBTCompound translateComponent(String key) {
        NBTCompound c = new NBTCompound();
        c.setTag("translate", new NBTString(key));
        return c;
    }

    private static NBTCompound textComponent(String text) {
        NBTCompound c = new NBTCompound();
        c.setTag("text", new NBTString(text));
        return c;
    }

    private static NBTCompound createComponentNbt(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return textComponent("");
        NBTCompound root = textComponent("");
        NBTList<NBTCompound> extra = new NBTList<>(NBTType.COMPOUND);
        String[] parts = packedKeys.split(DELIMITER, -1);
        for (int i = 0; i < parts.length; i++) {
            extra.addTag(translateComponent(parts[i]));
            if (i < parts.length - 1) extra.addTag(textComponent(DELIMITER));
        }
        root.setTag("extra", extra);
        return root;
    }

    private Vector3i buildFakeSign(Player target, List<String> lines, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = shouldUseModernFormat(cv);
        Vector3i pos = signPos(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState();
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        String l1 = lines.size() > 0 ? lines.get(0) : null;
        String l2 = lines.size() > 1 ? lines.get(1) : null;
        String l3 = lines.size() > 2 ? lines.get(2) : null;

        if (modern) {
            if (INLINE_COMPONENTS) {
                NBTList<NBTCompound> msgs = new NBTList<>(NBTType.COMPOUND);
                msgs.addTag(createComponentNbt(l1));
                msgs.addTag(createComponentNbt(l2));
                msgs.addTag(createComponentNbt(l3));
                msgs.addTag(textComponent(uid));
                NBTCompound front = new NBTCompound();
                front.setTag("messages", msgs);
                front.setTag("color", new NBTString("black"));
                front.setTag("has_glowing_text", new NBTByte((byte) 0));
                nbt.setTag("front_text", front);
                NBTCompound back = new NBTCompound();
                back.setTag("messages", msgs);
                back.setTag("color", new NBTString("black"));
                back.setTag("has_glowing_text", new NBTByte((byte) 0));
                nbt.setTag("back_text", back);
            } else {
                String key1_json = createComponentJson(l1);
                String key2_json = createComponentJson(l2);
                String key3_json = createComponentJson(l3);
                String uid_json = "{\"text\":\"" + uid + "\"}";
                NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
                msgs.addTag(new NBTString(key1_json));
                msgs.addTag(new NBTString(key2_json));
                msgs.addTag(new NBTString(key3_json));
                msgs.addTag(new NBTString(uid_json));
                NBTCompound front = new NBTCompound();
                front.setTag("messages", msgs);
                front.setTag("color", new NBTString("black"));
                front.setTag("has_glowing_text", new NBTByte((byte) 0));
                nbt.setTag("front_text", front);
                NBTCompound back = new NBTCompound();
                back.setTag("messages", msgs);
                back.setTag("color", new NBTString("black"));
                back.setTag("has_glowing_text", new NBTByte((byte) 0));
                nbt.setTag("back_text", back);
            }
        } else {
            String key1_json = createComponentJson(l1);
            String key2_json = createComponentJson(l2);
            String key3_json = createComponentJson(l3);
            nbt.setTag("Text1", new NBTString(key1_json));
            nbt.setTag("Text2", new NBTString(key2_json));
            nbt.setTag("Text3", new NBTString(key3_json));
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


    /* ---------------------------------------------------------------------
     *  ANVIL ID REFLECTION (Ported from TranslationDetector)
     * ------------------------------------------------------------------- */

    private static int getAnvilId() {
        try {
            Inventory inventory = Bukkit.createInventory(null, InventoryType.ANVIL);
            Method notchInventory = Class.forName(cbClass("inventory.CraftContainer")).getMethod("getNotchInventoryType", Inventory.class);
            Object value = notchInventory.invoke(null, inventory);

            try {
                Field[] fields = Class.forName("net.minecraft.world.inventory.Containers").getFields();
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].get(null) == value) return i;
                }
            } catch (Exception ignored) {}

            try {
                Field[] fields = Class.forName("net.minecraft.world.inventory.MenuType").getFields();
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].get(null) == value) return i;
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}

        return 8; // Fallback for modern versions
    }

    private static String cbClass(String clazz) {
        return Bukkit.getServer().getClass().getPackage().getName() + "." + clazz;
    }
}