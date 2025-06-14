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

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* ────────────────────────────────────────────────────────────────── */
    /*  CONSTANTS                                                        */
    /* ────────────────────────────────────────────────────────────────── */
    private static final int    TIMEOUT_TICKS   = 40;      // 2 s
    private static final int    AIR_ID          = 0;
    private static final double MOVE_EPSILON    = 0.0001;
    private static final float  ROT_EPSILON     = 1.5f;

    private static final String DELIMITER       = "\t";    // sign line delimiter

    private static final int ANVIL_WINDOW_ID    = 239;
    private static final short STATE_ID_ZERO    = 0;
    private static final int   ANVIL_INPUT_SLOT = 0;
    private static final int   CONTAINER_SIZE   = 3;

    private static final boolean INLINE_COMPONENTS = detectInlineComponentFormat();
    private static final int ANVIL_CONTAINER_ID    = getAnvilId();

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;

    /* ────────────────────────────────────────────────────────────────── */
    /*  INTERNAL HELPERS                                                 */
    /* ────────────────────────────────────────────────────────────────── */

    /** Returns an explicit empty stack for the current PE version. */
    private static ItemStack emptyStack() {
        return ItemStack.builder().type(ItemTypes.AIR).amount(0).build();
    }

    private enum ProbeMethod { SIGN, ANVIL }

    private static final class Probe {
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        final Set<String> translated            = new HashSet<>();

        int        retriesLeft;
        BukkitTask timeoutTask;
        boolean    waitingForMove;
        boolean    forceSend;
        long       sendTime;
        ProbeMethod method = ProbeMethod.SIGN;

        /* debugging */
        org.bukkit.command.CommandSender debug;

        /* sign path */
        Iterator<List<String>> signBatchIterator;
        List<String>  currentKeyLines;
        String        uid;
        Vector3i      signPos;
        int           totalSignBatches;
        int           currentSignBatchNum;

        /* anvil path */
        Iterator<String> anvilKeyIterator;
        String           currentAnvilKey;
    }

    private final Map<UUID, Probe> probes       = new HashMap<>();
    private final Map<UUID, Long>  cooldown     = new HashMap<>();
    private final Set<UUID>        pendingStart = new HashSet<>();

    /* ────────────────────────────────────────────────────────────────── */
    /*  CONSTRUCTOR / LIFECYCLE                                          */
    /* ────────────────────────────────────────────────────────────────── */

    public TranslatableKeyManager(AntiSpoofPlugin pl,
                                  DetectionManager det,
                                  ConfigManager cfg) {
        this.plugin = pl;
        this.detect = det;
        this.cfg    = cfg;

        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  JOIN / QUIT / MOVE                                               */
    /* ────────────────────────────────────────────────────────────────── */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;

        if (cfg.isTranslatableOnlyOnMove()) {
            pendingStart.add(e.getPlayer().getUniqueId());
        } else {
            int delay = cfg.getTranslatableFirstDelay();
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> beginProbe(e.getPlayer(),
                                     cfg.getTranslatableModsWithLabels(),
                                     cfg.getTranslatableRetryCount(),
                                     false, null, false),
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
        float dy  = Math.abs(e.getTo().getYaw()   - e.getFrom().getYaw());
        float dp  = Math.abs(e.getTo().getPitch() - e.getFrom().getPitch());

        boolean moved = dx * dx + dz * dz >= MOVE_EPSILON || dy > ROT_EPSILON || dp > ROT_EPSILON;
        if (!moved) return;

        if (cfg.isTranslatableOnlyOnMove()) {
            UUID id = e.getPlayer().getUniqueId();
            if (pendingStart.remove(id)) {
                int delay = cfg.getTranslatableFirstDelay();
                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> beginProbe(e.getPlayer(),
                                         cfg.getTranslatableModsWithLabels(),
                                         cfg.getTranslatableRetryCount(),
                                         false, null, false),
                        delay);
            }
        }

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            if (p.method == ProbeMethod.SIGN) {
                placeNextSign(e.getPlayer(), p);
            } else {
                placeNextAnvil(e.getPlayer(), p, p.currentAnvilKey);
            }
        }
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  PUBLIC CONTROL METHODS                                           */
    /* ────────────────────────────────────────────────────────────────── */

    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        Map<String,String> single = Collections.singletonMap(key, key);
        if (probes.containsKey(target.getUniqueId())) stopMods(target);
        beginProbe(target, single, 0, true, dbg, true);
    }

    public void runMods(Player target) {
        if (probes.containsKey(target.getUniqueId())) stopMods(target);
        beginProbe(target,
                   cfg.getTranslatableModsWithLabels(),
                   cfg.getTranslatableRetryCount(),
                   true, null, false);
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

    /* ────────────────────────────────────────────────────────────────── */
    /*  PROBE DISPATCH                                                   */
    /* ────────────────────────────────────────────────────────────────── */

    private void beginProbe(Player p,
                            Map<String,String> keys,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend) {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        if (!ignoreCooldown) {
            long cd = cfg.getTranslatableCooldown() * 1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        if (cfg.isTranslatableStartWithAnvil()) {
            beginAnvilProbe(p, keys, retries, dbg, forceSend);
        } else {
            beginSignProbe(p, keys, retries, dbg, forceSend);
        }
    }

    private void beginSignProbe(Player p, Map<String,String> keys,
                                int retries,
                                org.bukkit.command.CommandSender dbg,
                                boolean forceSend) {

        List<List<String>> batches = batchKeysForSign(keys);

        Probe pr = new Probe();
        pr.method              = ProbeMethod.SIGN;
        pr.signBatchIterator   = batches.iterator();
        pr.retriesLeft         = retries;
        pr.debug               = dbg;
        pr.forceSend           = forceSend;
        pr.totalSignBatches    = batches.size();

        probes.put(p.getUniqueId(), pr);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting SIGN probe for " + p.getName() +
                                     " with " + keys.size() + " keys in "
                                     + batches.size() + " sign packets.");
        }
        advanceSign(p, pr);
    }

    private void beginAnvilProbe(Player p, Map<String,String> keys,
                                 int retries,
                                 org.bukkit.command.CommandSender dbg,
                                 boolean forceSend) {

        Probe pr = new Probe();
        pr.method           = ProbeMethod.ANVIL;
        pr.anvilKeyIterator = new ArrayList<>(keys.keySet()).iterator();
        pr.retriesLeft      = retries;
        pr.debug            = dbg;
        pr.forceSend        = forceSend;

        probes.put(p.getUniqueId(), pr);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting ANVIL probe for " + p.getName() +
                                     " with " + keys.size() + " keys.");
        }
        advanceAnvil(p, pr);
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  SIGN PATH                                                        */
    /* ────────────────────────────────────────────────────────────────── */

    private void advanceSign(Player player, Probe pr) {
        if (pr.timeoutTask != null) pr.timeoutTask.cancel();

        if (!pr.signBatchIterator.hasNext()) {
            finishRound(player, pr);
            return;
        }

        if (pr.signPos != null) sendAir(player, pr.signPos);

        pr.currentKeyLines = pr.signBatchIterator.next();
        pr.uid             = randomUID();
        pr.currentSignBatchNum++;

        if (cfg.isTranslatableOnlyOnMove() && !pr.forceSend) {
            pr.waitingForMove = true;
        } else {
            placeNextSign(player, pr);
        }
    }

    private void placeNextSign(Player player, Probe pr) {
        pr.signPos = buildFakeSign(player, pr.currentKeyLines, pr.uid);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending SIGN batch "
                                     + pr.currentSignBatchNum + "/"
                                     + pr.totalSignBatches + " to "
                                     + player.getName());
        }

        pr.sendTime = System.currentTimeMillis();
        pr.timeoutTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (cfg.isDebugMode())
                        plugin.getLogger().warning("[Debug] SIGN batch "
                                + pr.currentSignBatchNum + " timed out for "
                                + player.getName());
                    for (String line : pr.currentKeyLines) {
                        for (String key : line.split(DELIMITER, -1)) {
                            if (!key.isEmpty())
                                handleResult(player, pr, key,false,null);
                        }
                    }
                    advanceSign(player, pr);
                },
                TIMEOUT_TICKS);
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  ANVIL PATH (rewritten for 1.20.5+)                               */
    /* ────────────────────────────────────────────────────────────────── */

    private void advanceAnvil(Player player, Probe pr) {
        if (pr.timeoutTask != null) pr.timeoutTask.cancel();

        if (!pr.anvilKeyIterator.hasNext()) {
            finishRound(player, pr);
            return;
        }

        pr.currentAnvilKey = pr.anvilKeyIterator.next();

        if (cfg.isTranslatableOnlyOnMove() && !pr.forceSend) {
            pr.waitingForMove = true;
        } else {
            placeNextAnvil(player, pr, pr.currentAnvilKey);
        }
    }

    private void placeNextAnvil(Player player, Probe probe, String key) {

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending ANVIL probe for key '"
                                    + key + "' to " + player.getName());
        }

        /* 1. open window */
        PacketEvents.getAPI().getPlayerManager().sendPacket(
                player,
                new WrapperPlayServerOpenWindow(
                        ANVIL_WINDOW_ID,
                        ANVIL_CONTAINER_ID,
                        Component.text("Repair & Name")));

        /* 2. build test stack */
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("display", new NBTCompound());
        nbt.getCompoundTagOrNull("display")
                .setTag("Name", new NBTString(
                        "{\"translate\":\"" + key.replace("\"","\\\"") + "\"}"));

        ItemStack testStack = ItemStack.builder()
                .type(ItemTypes.IRON_SWORD)
                .amount(1)
                .nbt(nbt)
                .build();

        /* 3. full container snapshot */
        List<ItemStack> slots = new ArrayList<>(CONTAINER_SIZE);
        slots.add(testStack);        // slot 0
        slots.add(emptyStack());     // slot 1
        slots.add(emptyStack());     // slot 2

        PacketEvents.getAPI().getPlayerManager().sendPacket(
                player,
                new WrapperPlayServerWindowItems(
                        ANVIL_WINDOW_ID,
                        STATE_ID_ZERO,
                        slots,
                        emptyStack()));                        // carried

        /* 4. level‑cost property (visual) */
        PacketEvents.getAPI().getPlayerManager().sendPacket(
                player,
                new WrapperPlayServerWindowProperty(
                        ANVIL_WINDOW_ID, 0, 0));

        /* 5. timeout scheduler */
        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (cfg.isDebugMode()) {
                        plugin.getLogger().warning("[Debug] ANVIL probe for key '"
                                + key + "' timed out for "
                                + player.getName());
                    }
                    handleResult(player, probe, key, false, null);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(
                            player, new WrapperPlayServerCloseWindow(ANVIL_WINDOW_ID));
                    advanceAnvil(player, probe);
                },
                TIMEOUT_TICKS);
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  PACKET LISTENER                                                  */
    /* ────────────────────────────────────────────────────────────────── */

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        Player p   = (Player) e.getPlayer();
        Probe  pr  = probes.get(p.getUniqueId());
        if (pr == null) return;

        if (e.getPacketType() == PacketType.Play.Client.UPDATE_SIGN &&
            pr.method == ProbeMethod.SIGN) {

            if (pr.currentKeyLines == null) return;
            String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
            if (recv.length < 4 || !pr.uid.equals(recv[3])) return;

            pr.timeoutTask.cancel();

            for (int i = 0; i < pr.currentKeyLines.size(); i++) {
                String[] originalKeys = pr.currentKeyLines.get(i).split(DELIMITER, -1);
                String[] received     = i < recv.length
                        ? recv[i].split(DELIMITER, -1)
                        : new String[0];

                for (int j = 0; j < originalKeys.length; j++) {
                    String key = originalKeys[j];
                    if (key.isEmpty()) continue;
                    String rx = j < received.length ? received[j] : key;
                    boolean translated = !rx.isEmpty() && !rx.equals(key)
                            && !rx.startsWith("{\"translate\"");
                    handleResult(p, pr, key, translated, rx);
                }
            }
            advanceSign(p, pr);

        } else if (e.getPacketType() == PacketType.Play.Client.NAME_ITEM &&
                   pr.method == ProbeMethod.ANVIL) {

            pr.timeoutTask.cancel();

            String newName = new WrapperPlayClientNameItem(e).getItemName();
            String key     = pr.currentAnvilKey;

            boolean translated = !newName.isEmpty() && !newName.equals(key);
            handleResult(p, pr, key, translated, newName);

            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    p, new WrapperPlayServerCloseWindow(ANVIL_WINDOW_ID));

            advanceAnvil(p, pr);
        }
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  RESULT HANDLING / RETRIES                                        */
    /* ────────────────────────────────────────────────────────────────── */

    private void handleResult(Player p, Probe pr, String key,
                               boolean translated, String response) {

        long ms = System.currentTimeMillis() - pr.sendTime;
        if (pr.debug != null) {
            String txt = translated ? response : "";
            pr.debug.sendMessage(org.bukkit.ChatColor.AQUA + p.getName() +
                    org.bukkit.ChatColor.DARK_GRAY + " | " +
                    org.bukkit.ChatColor.GRAY + "Key: \"" +
                    org.bukkit.ChatColor.WHITE + key +
                    org.bukkit.ChatColor.GRAY + "\" Response: \"" +
                    org.bukkit.ChatColor.AQUA + txt +
                    org.bukkit.ChatColor.GRAY + "\" Time: " +
                    org.bukkit.ChatColor.AQUA + ms + "ms");
        }

        String label = cfg.getTranslatableModsWithLabels().get(key);
        if (label == null) return;

        if (translated) {
            if (cfg.isDebugMode())
                plugin.getLogger().info("[Debug] " + p.getName()
                        + " translated: '" + key + "' -> '" + response
                        + "' (" + label + ")");
            pr.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        } else {
            pr.failedForNext.put(key, label);
        }
    }

    private void finishRound(Player p, Probe pr) {
        if (pr.retriesLeft > 0 && !pr.failedForNext.isEmpty()) {
            if (pr.method == ProbeMethod.SIGN) {
                beginAnvilRetry(p, pr);
            } else {
                beginSignRetry(p, pr);
            }
            return;
        }

        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!pr.translated.contains(req))
                detect.handleTranslatable(p,
                        TranslatableEventType.REQUIRED_MISS, req);
        }
        if (pr.signPos != null) sendAir(p, pr.signPos);
        probes.remove(p.getUniqueId());

        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] All probes finished for "
                                     + p.getName());
    }

    private void beginAnvilRetry(Player player, Probe pr) {
        pr.method           = ProbeMethod.ANVIL;
        pr.anvilKeyIterator = new ArrayList<>(pr.failedForNext.keySet()).iterator();
        pr.failedForNext.clear();
        pr.retriesLeft--;

        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] Switching to ANVIL retry "
                    + "for " + player.getName() + ". Retries left: "
                    + pr.retriesLeft);

        advanceAnvil(player, pr);
    }

    private void beginSignRetry(Player player, Probe pr) {
        Map<String,String> retry = new HashMap<>(pr.failedForNext);
        pr.failedForNext.clear();
        pr.retriesLeft--;

        List<List<String>> batches = batchKeysForSign(retry);

        pr.method             = ProbeMethod.SIGN;
        pr.signBatchIterator  = batches.iterator();
        pr.totalSignBatches   = batches.size();
        pr.currentSignBatchNum = 0;

        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] Switching to SIGN retry "
                    + "for " + player.getName() + ". Retries left: "
                    + pr.retriesLeft);

        advanceSign(player, pr);
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  SIGN HELPERS                                                     */
    /* ────────────────────────────────────────────────────────────────── */

    private List<List<String>> batchKeysForSign(Map<String,String> keys) {
        final int maxLen = cfg.getTranslatableMaxLineLength();
        List<List<String>> out = new ArrayList<>();
        if (keys.isEmpty()) return out;

        List<StringBuilder> lines = Arrays.asList(
                new StringBuilder(), new StringBuilder(), new StringBuilder());
        int idx = 0;

        for (String key : keys.keySet()) {
            if (lines.get(idx).length() > 0 &&
                lines.get(idx).length() + DELIMITER.length() + key.length() > maxLen) {
                idx++;
            }
            if (idx >= 3) {
                out.add(lines.stream().map(StringBuilder::toString).collect(Collectors.toList()));
                lines = Arrays.asList(
                        new StringBuilder(), new StringBuilder(), new StringBuilder());
                idx = 0;
            }
            if (lines.get(idx).length() > 0) lines.get(idx).append(DELIMITER);
            lines.get(idx).append(key);
        }
        if (lines.get(0).length() > 0)
            out.add(lines.stream().map(StringBuilder::toString).collect(Collectors.toList()));
        return out;
    }

    private String createComponentJson(String packed) {
        if (packed == null || packed.isEmpty()) return "{\"text\":\"\"}";
        String extras = Arrays.stream(packed.split(DELIMITER,-1))
                .map(k -> "{\"translate\":\"" + k.replace("\"","\\\"") + "\"}")
                .collect(Collectors.joining(
                        "," + "{\"text\":\"" + DELIMITER + "\"}" + ","));
        return "{\"text\":\"\",\"extra\":[" + extras + "]}";
    }

    private static boolean shouldUseModernFormat(ClientVersion cv) {
        if (cv == null) return true;
        try {
            if (cv.isNewerThanOrEquals(ClientVersion.V_1_20)) return true;
        } catch (Throwable ignored) {}
        try {
            return cv.getProtocolVersion() >= ClientVersion.V_1_20.getProtocolVersion();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectInlineComponentFormat() {
        try {
            String[] p = Bukkit.getBukkitVersion().split("-",2)[0].split("\\.");
            int major = p.length>1 ? Integer.parseInt(p[1]) : 0;
            int patch = p.length>2 ? Integer.parseInt(p[2]) : 0;
            return major > 21 || (major == 21 && patch >= 5);
        } catch (Throwable ignored) { return true; }
    }

    private static NBTCompound translateComponent(String key) {
        NBTCompound c = new NBTCompound();
        c.setTag("translate", new NBTString(key)); return c;
    }
    private static NBTCompound textComponent(String txt) {
        NBTCompound c = new NBTCompound();
        c.setTag("text", new NBTString(txt)); return c;
    }
    private static NBTCompound createComponentNbt(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return textComponent("");
        NBTCompound root = textComponent("");
        NBTList<NBTCompound> extra = new NBTList<>(NBTType.COMPOUND);
        String[] parts = packedKeys.split(DELIMITER,-1);
        for (int i=0;i<parts.length;i++) {
            extra.addTag(translateComponent(parts[i]));
            if (i<parts.length-1) extra.addTag(textComponent(DELIMITER));
        }
        root.setTag("extra", extra);
        return root;
    }

    private Vector3i buildFakeSign(Player target,
                                   List<String> lines,
                                   String uid) {

        ClientVersion cv = PacketEvents.getAPI()
                .getPlayerManager().getClientVersion(target);
        boolean modern = shouldUseModernFormat(cv);
        Vector3i pos   = signPos(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState();
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockChange(
                        pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));

        String l1 = lines.size()>0 ? lines.get(0) : null;
        String l2 = lines.size()>1 ? lines.get(1) : null;
        String l3 = lines.size()>2 ? lines.get(2) : null;

        if (modern) {
            if (INLINE_COMPONENTS) {
                NBTList<NBTCompound> msgs = new NBTList<>(NBTType.COMPOUND);
                msgs.addTag(createComponentNbt(l1));
                msgs.addTag(createComponentNbt(l2));
                msgs.addTag(createComponentNbt(l3));
                msgs.addTag(textComponent(uid));
                for (String side : new String[]{"front_text","back_text"}) {
                    NBTCompound t = new NBTCompound();
                    t.setTag("messages", msgs);
                    t.setTag("color", new NBTString("black"));
                    t.setTag("has_glowing_text", new NBTByte((byte)0));
                    nbt.setTag(side, t);
                }
            } else {
                String j1 = createComponentJson(l1);
                String j2 = createComponentJson(l2);
                String j3 = createComponentJson(l3);
                String ju = "{\"text\":\"" + uid + "\"}";

                NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
                msgs.addTag(new NBTString(j1));
                msgs.addTag(new NBTString(j2));
                msgs.addTag(new NBTString(j3));
                msgs.addTag(new NBTString(ju));

                for (String side : new String[]{"front_text","back_text"}) {
                    NBTCompound t = new NBTCompound();
                    t.setTag("messages", msgs);
                    t.setTag("color", new NBTString("black"));
                    t.setTag("has_glowing_text", new NBTByte((byte)0));
                    nbt.setTag(side, t);
                }
            }
        } else {
            nbt.setTag("Text1", new NBTString(createComponentJson(l1)));
            nbt.setTag("Text2", new NBTString(createComponentJson(l2)));
            nbt.setTag("Text3", new NBTString(createComponentJson(l3)));
            nbt.setTag("Text4", new NBTString(uid));
        }

        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockEntityData(
                        pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerCloseWindow(0));
        return pos;
    }

    private void sendAir(Player p, Vector3i pos) {
        if (p != null && p.isOnline())
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
    }

    private Vector3i signPos(Player p) {
        World w = p.getWorld();
        org.bukkit.Chunk ch = p.getLocation().getChunk();
        int x = (ch.getX()+1)*16;
        int z = (ch.getZ()+1)*16;
        int y = w.getMaxHeight()-5;
        return new Vector3i(x,y,z);
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current()
                .nextInt(0x10000));
    }

    /* ────────────────────────────────────────────────────────────────── */
    /*  REFLECTION: GET ANVIL CONTAINER ID                               */
    /* ────────────────────────────────────────────────────────────────── */

    private static int getAnvilId() {
        try {
            Inventory inv = Bukkit.createInventory(null, InventoryType.ANVIL);
            Method m = Class.forName(cbClass("inventory.CraftContainer"))
                    .getMethod("getNotchInventoryType", Inventory.class);
            Object val = m.invoke(null, inv);

            try {
                for (Field f : Class.forName("net.minecraft.world.inventory.Containers").getFields())
                    if (f.get(null) == val) return f.getInt(null);
            } catch (Exception ignored) {}
            try {
                for (Field f : Class.forName("net.minecraft.world.inventory.MenuType").getFields())
                    if (f.get(null) == val) return f.getInt(null);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return 8; // 1.20.3+ fallback
    }
    private static String cbClass(String c) {
        return Bukkit.getServer().getClass().getPackage().getName() + "." + c;
    }
}