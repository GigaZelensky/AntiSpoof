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
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Sign‑based translation probe with retry‑based anvil fallback.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int   TIMEOUT_TICKS   = 40;   // 2 s
    private static final int   AIR_ID          = 0;
    private static final double MOVE_EPSILON   = 0.0001;
    private static final float ROT_EPSILON     = 1.5f;

    private static final String DELIMITER = "\t";

    private static final boolean INLINE_COMPONENTS = detectInlineComponentFormat();

    /* -------- Anvil constants -------- */
    private static final int WINDOW_ID  = 239;
    private static final int ANVIL_ID   = getAnvilId();
    private static final WrapperPlayServerOpenWindow  OPEN_ANVIL =
            new WrapperPlayServerOpenWindow(WINDOW_ID, ANVIL_ID, Component.text("Repair & Name"));
    private static final WrapperPlayServerCloseWindow CLOSE_ANVIL =
            new WrapperPlayServerCloseWindow(WINDOW_ID);

    /* --------------------------------------------------------------------- */
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager    cfg;

    /* ACTIVE PROBES */
    private static final class Probe {
        Iterator<List<String>> signIterator;   // sign batches
        Iterator<String>       anvilIterator;  // single keys for anvil
        final Set<String> translated = new HashSet<>();
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;
        boolean anvilMode = false;

        /* per‑round state */
        List<String> currentKeyLines = null;   // sign mode
        String       currentAnvilKey = null;   // anvil mode
        String  uid   = null;
        Vector3i sign = null;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        long sendTime;
        boolean forceSend = false;
        int totalBatches;
        int currentBatchNum = 0;
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
     *  JOIN/QUIT/MOVE
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
                                    false,
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
                                         false,
                                         false),
                        delay);
            }
        }

        Probe p = probes.get(e.getPlayer().getUniqueId());
        if (p != null && p.waitingForMove) {
            p.waitingForMove = false;
            if (p.anvilMode) placeNextAnvil(e.getPlayer(), p);
            else             placeNextSign(e.getPlayer(), p);
        }
    }

    /* ======================================================================
     *  PUBLIC COMMAND‑LEVEL UTILS
     * ==================================================================== */
    public void sendKeybind(Player target, String key, org.bukkit.command.CommandSender dbg) {
        Map<String,String> single = Collections.singletonMap(key, key);
        if (probes.containsKey(target.getUniqueId())) stopMods(target);
        beginProbe(target, single, 0, true, dbg, true, false);
    }

    public void runMods(Player target) {
        if (probes.containsKey(target.getUniqueId())) stopMods(target);
        beginProbe(target,
                   cfg.getTranslatableModsWithLabels(),
                   cfg.getTranslatableRetryCount(),
                   true,
                   null,
                   false,
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
     *  PROBE INITIALISATION
     * ==================================================================== */
    private void beginProbe(Player p,
                            Map<String, String> keys,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend,
                            boolean anvilMode)
    {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        if (!ignoreCooldown) {
            long cd  = cfg.getTranslatableCooldown()*1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(),0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        Probe probe = new Probe();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probe.anvilMode = anvilMode || cfg.isTranslatableStartWithAnvil();

        if (!probe.anvilMode) {
            /* -------- sign batching -------- */
            final int maxLineLength = cfg.getTranslatableMaxLineLength();
            List<List<String>> allSignBatches = new ArrayList<>();
            if (!keys.isEmpty()) {
                List<StringBuilder> current = Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder());
                int line = 0;
                for (String key : keys.keySet()) {
                    if (current.get(line).length() > 0 &&
                            current.get(line).length() + DELIMITER.length() + key.length() > maxLineLength) {
                        line++;
                    }
                    if (line >= 3) {
                        allSignBatches.add(current.stream().map(StringBuilder::toString).collect(Collectors.toList()));
                        current = Arrays.asList(new StringBuilder(), new StringBuilder(), new StringBuilder());
                        line = 0;
                    }
                    if (current.get(line).length() > 0) current.get(line).append(DELIMITER);
                    current.get(line).append(key);
                }
                if (current.get(0).length() > 0)
                    allSignBatches.add(current.stream().map(StringBuilder::toString).collect(Collectors.toList()));
            }
            probe.signIterator = allSignBatches.iterator();
            probe.totalBatches = allSignBatches.size();
        } else {
            /* -------- anvil iterator (single key per window) -------- */
            probe.anvilIterator = new ArrayList<>(keys.keySet()).iterator();
            probe.totalBatches  = keys.size();
        }

        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting " + (probe.anvilMode ? "anvil" : "sign") +
                    " probe for " + p.getName() + " with " + keys.size() +
                    (probe.anvilMode ? " keys" : " keys in " + probe.totalBatches + " sign packets."));
        }
        advance(p, probe);
    }

    /* ======================================================================
     *  ADVANCE LOGIC FOR BOTH MODES
     * ==================================================================== */
    private void advance(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        boolean hasNext = probe.anvilMode ? probe.anvilIterator.hasNext() : probe.signIterator.hasNext();
        if (!hasNext) {
            finishRound(player, probe);
            return;
        }

        if (!probe.anvilMode && probe.sign != null) sendAir(player, probe.sign);

        probe.currentBatchNum++;

        if (probe.anvilMode) {
            probe.currentAnvilKey = probe.anvilIterator.next();
            if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
                probe.waitingForMove = true;
            } else {
                placeNextAnvil(player, probe);
            }
        } else {
            probe.currentKeyLines = probe.signIterator.next();
            probe.uid = randomUID();
            if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
                probe.waitingForMove = true;
            } else {
                placeNextSign(player, probe);
            }
        }
    }

    /* ======================================================================
     *  SIGN MODE
     * ==================================================================== */
    private void placeNextSign(Player player, Probe probe) {
        Vector3i pos = buildFakeSign(player, probe.currentKeyLines, probe.uid);
        probe.sign = pos;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending sign batch " + probe.currentBatchNum + "/" +
                    probe.totalBatches + " to " + player.getName());
        }

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(cfg.isDebugMode()) plugin.getLogger().warning("[Debug] Sign batch " +
                    probe.currentBatchNum + " timed out for " + player.getName());
            for (String line : probe.currentKeyLines) {
                for (String key : line.split(DELIMITER,-1)) {
                    if (!key.isEmpty()) handleResult(player, probe, key, false, null);
                }
            }
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }

    /* ======================================================================
     *  ANVIL MODE
     * ==================================================================== */
    private void placeNextAnvil(Player player, Probe probe) {
        String key = probe.currentAnvilKey;

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Sending anvil key " + probe.currentBatchNum + "/" +
                    probe.totalBatches + " (" + key + ") to " + player.getName());
        }

        sendAnvilWindow(player, key);

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(cfg.isDebugMode()) plugin.getLogger().warning("[Debug] Anvil key '" +
                    key + "' timed out for " + player.getName());
            handleResult(player, probe, key, false, null);
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }

    /* ======================================================================
     *  PACKET RECEIVE
     * ==================================================================== */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        PacketTypeCommon type = e.getPacketType();
        if (type == PacketType.Play.Client.UPDATE_SIGN) {
            handleSignPacket(e);
        } else if (type == PacketType.Play.Client.NAME_ITEM) {
            handleNameItemPacket(e);
        }
    }

    private void handleSignPacket(PacketReceiveEvent e) {
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.anvilMode || probe.currentKeyLines == null) return;

        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4 || !probe.uid.equals(recv[3])) return;

        probe.timeoutTask.cancel();

        for (int i = 0; i < probe.currentKeyLines.size(); i++) {
            String[] originalKeys = probe.currentKeyLines.get(i).split(DELIMITER, -1);
            String[] receivedTranslations = (i < recv.length) ? recv[i].split(DELIMITER,-1) : new String[0];

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

    private void handleNameItemPacket(PacketReceiveEvent e) {
        Player p = (Player) e.getPlayer();
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || !probe.anvilMode || probe.currentAnvilKey == null) return;

        String key = probe.currentAnvilKey;
        String name = new WrapperPlayClientNameItem(e).getItemName();

        probe.timeoutTask.cancel();

        boolean translated = !name.isEmpty() && !name.equals(key) && !name.startsWith("{\"translate\"");
        handleResult(p, probe, key, translated, name);

        advance(p, probe);
    }

    /* ======================================================================
     *  RESULT HANDLER
     * ==================================================================== */
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
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] " + p.getName() + " translated: '" +
                    key + "' -> '" + response + "' (" + label + ")");
            probe.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED, key);
        } else {
            probe.failedForNext.put(key, label);
        }
    }

    /* ======================================================================
     *  ROUND FINISH / RETRY LOGIC
     * ==================================================================== */
    private void finishRound(Player p, Probe probe) {
        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }
        if (probe.sign != null) sendAir(p, probe.sign);

        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            boolean nextAnvil = !probe.anvilMode;
            if(cfg.isDebugMode()) plugin.getLogger().info("[Debug] Retrying " +
                    probe.failedForNext.size() + " failed keys for " + p.getName() +
                    " using " + (nextAnvil ? "anvil" : "sign") + " mode");

            int interval = cfg.getTranslatableRetryInterval();
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> beginProbe(p,
                                     probe.failedForNext,
                                     probe.retriesLeft - 1,
                                     true,
                                     probe.debug,
                                     probe.forceSend,
                                     nextAnvil),
                    interval);
        } else {
            probes.remove(p.getUniqueId());
            if (cfg.isDebugMode()) plugin.getLogger().info("[Debug] Probe finished for " + p.getName());
        }
    }

    /* ======================================================================
     *  ANVIL WINDOW SENDER
     * ==================================================================== */
    private void sendAnvilWindow(Player target, String key) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(target);

        user.sendPacket(OPEN_ANVIL);

        NBTCompound nbt = new NBTCompound();
        NBTCompound display = new NBTCompound();
        display.setTag("Name", new NBTString("{\"translate\":\"" + key + "\"}"));
        nbt.setTag("display", display);

        ItemStack stack = ItemStack.builder().type(ItemTypes.IRON_SWORD).amount(1).nbt(nbt).build();
        user.sendPacket(new WrapperPlayServerWindowItems(WINDOW_ID, 1, List.of(stack), stack));

        user.sendPacket(CLOSE_ANVIL);
    }

    /* ======================================================================
     *  SIGN BUILDERS
     * ==================================================================== */
    private String createComponentJson(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return "{\"text\":\"\"}";
        String extra = Arrays.stream(packedKeys.split(DELIMITER,-1))
                .map(k -> "{\"translate\":\"" + k.replace("\"","\\\"") + "\"}")
                .collect(Collectors.joining(",{\"text\":\"" + DELIMITER + "\"},"));
        return "{\"text\":\"\",\"extra\":[" + extra + "]}";
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
        String[] parts = packedKeys.split(DELIMITER,-1);
        for (int i=0;i<parts.length;i++) {
            extra.addTag(translateComponent(parts[i]));
            if (i<parts.length-1) extra.addTag(textComponent(DELIMITER));
        }
        root.setTag("extra", extra);
        return root;
    }

    private Vector3i buildFakeSign(Player target, List<String> lines, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern   = shouldUseModernFormat(cv);

        Vector3i pos = signPos(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState();
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

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
                String j1 = createComponentJson(l1);
                String j2 = createComponentJson(l2);
                String j3 = createComponentJson(l3);
                String ju = "{\"text\":\""+uid+"\"}";

                NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
                msgs.addTag(new NBTString(j1));
                msgs.addTag(new NBTString(j2));
                msgs.addTag(new NBTString(j3));
                msgs.addTag(new NBTString(ju));

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
            }
        } else {
            String j1 = createComponentJson(l1);
            String j2 = createComponentJson(l2);
            String j3 = createComponentJson(l3);

            nbt.setTag("Text1", new NBTString(j1));
            nbt.setTag("Text2", new NBTString(j2));
            nbt.setTag("Text3", new NBTString(j3));
            nbt.setTag("Text4", new NBTString(uid));
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerOpenSignEditor(pos,true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerCloseWindow(0));
        return pos;
    }

    /* ======================================================================
     *  UTILITIES
     * ==================================================================== */
    private void sendAir(Player p, Vector3i pos) {
        if (p != null && p.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
        }
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
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }

    /* -------- version helpers -------- */
    private static boolean shouldUseModernFormat(ClientVersion cv) {
        if (cv==null) return true;
        try { if (cv.isNewerThanOrEquals(ClientVersion.V_1_20)) return true; } catch(Throwable ignored){}
        try { return cv.getProtocolVersion() >= ClientVersion.V_1_20.getProtocolVersion(); } catch(Throwable ignored){}
        return false;
    }

    private static boolean detectInlineComponentFormat() {
        try {
            String ver = Bukkit.getBukkitVersion().split("-",2)[0];
            String[] p = ver.split("\\.");
            int major=(p.length>1)?Integer.parseInt(p[1]):0;
            int patch=(p.length>2)?Integer.parseInt(p[2]):0;
            return major>21 || (major==21 && patch>=5);
        } catch(Throwable ignored){return true;}
    }

    /* -------- anvil id reflection (from TranslationDetector) -------- */
    private static int getAnvilId() {
        try {
            String craft = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> invTypeClazz = Class.forName("org.bukkit.event.inventory.InventoryType");
            Object anvilInv = org.bukkit.Bukkit.createInventory(null, (org.bukkit.event.inventory.InventoryType)
                    invTypeClazz.getField("ANVIL").get(null));
            Class<?> cc = Class.forName(craft + ".inventory.CraftContainer");
            Method m = cc.getMethod("getNotchInventoryType", org.bukkit.inventory.Inventory.class);
            Object notch = m.invoke(null, anvilInv);

            try {
                Field[] f = Class.forName("net.minecraft.world.inventory.Containers").getFields();
                for (int i=0;i<f.length;i++) if (f[i].get(null)==notch) return i;
            } catch (Throwable ignored){}

            try {
                Field[] f = Class.forName("net.minecraft.world.inventory.MenuType").getFields();
                for (int i=0;i<f.length;i++) if (f[i].get(null)==notch) return i;
            } catch (Throwable ignored){}
        } catch (Throwable ignored){}
        return 8; // fallback (1.20.3+)
    }
}