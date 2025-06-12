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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Sign + Anvil translatable-key probe – PacketEvents 2.8.1-SNAPSHOT compliant.
 */
public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int    TIMEOUT_TICKS   = 40;     // 2 s
    private static final int    AIR_ID          = 0;
    private static final double MOVE_EPSILON    = 0.0001;
    private static final float  ROT_EPSILON     = 1.5f;
    private static final String DELIMITER       = "\t";

    private static final boolean INLINE_COMPONENTS = detectInlineComponentFormat();

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    /* -------------------------------- probe bookkeeping ------------------ */
    private enum Channel { SIGN, ANVIL }

    private static final class Probe {
        Iterator<List<String>> iterator;
        final Set<String> translated = new HashSet<>();
        final Map<String,String> failedNext = new LinkedHashMap<>();
        Channel channel;
        int  retriesLeft;
        int  totalBatches;
        int  currentBatch;
        List<String> currentLines;
        String   uid;
        Vector3i signPos;       // only for SIGN channel
        int      syncId = -1;   // only for ANVIL channel
        BukkitTask timeout;
        boolean waitingForMove;
        org.bukkit.command.CommandSender debug;
        long sendTime;
        boolean forceSend;
    }

    private final Map<UUID,Probe> probes   = new HashMap<>();
    private final Map<UUID,Long>  cooldown = new HashMap<>();
    private final Set<UUID>       pending = new HashSet<>();

    /* --------------------------------------------------------------------- */
    public TranslatableKeyManager(AntiSpoofPlugin pl,
                                  DetectionManager det,
                                  ConfigManager cfg) {
        this.plugin = pl;
        this.detect = det;
        this.cfg    = cfg;

        Bukkit.getPluginManager().registerEvents(this, pl);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /* =====================================================================
     * PLAYER LIFECYCLE
     * =================================================================== */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.isTranslatableKeysEnabled()) return;
        if (cfg.isTranslatableOnlyOnMove()) {
            pending.add(e.getPlayer().getUniqueId());
        } else {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> startFullProbe(e.getPlayer(), false, null),
                    cfg.getTranslatableFirstDelay());
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
        if (dx*dx + dz*dz < MOVE_EPSILON && dy <= ROT_EPSILON && dp <= ROT_EPSILON) return;

        Player p = e.getPlayer();
        if (cfg.isTranslatableOnlyOnMove() && pending.remove(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> startFullProbe(p, false, null),
                    cfg.getTranslatableFirstDelay());
        }

        Probe pr = probes.get(p.getUniqueId());
        if (pr != null && pr.waitingForMove) {
            pr.waitingForMove = false;
            dispatchBatch(p, pr);
        }
    }

    /* =====================================================================
     *  PUBLIC COMMAND HOOKS
     * =================================================================== */
    public void sendKeybind(Player target, String key,
                            org.bukkit.command.CommandSender dbg) {
        stopMods(target);
        Map<String,String> single = Collections.singletonMap(key,key);
        beginProbe(target, single, 0, true, dbg, true,
                   cfg.getTranslatableScanOrder() == ConfigManager.ScanOrder.ANVIL_THEN_SIGN
                           ? Channel.ANVIL : Channel.SIGN);
    }

    public void runMods(Player target) {
        stopMods(target);
        startFullProbe(target, true, null);
    }

    public void stopMods(Player target) {
        UUID id = target.getUniqueId();
        pending.remove(id);
        Probe p = probes.remove(id);
        if (p != null) {
            if (p.timeout != null) p.timeout.cancel();
            if (p.signPos != null) sendAir(target, p.signPos);
            if (p.channel == Channel.ANVIL && p.syncId != -1) closeAnvil(target, p.syncId);
        }
    }

    /* =====================================================================
     *  PROBE ENTRY
     * =================================================================== */
    private void startFullProbe(Player player,
                                boolean ignoreCooldown,
                                org.bukkit.command.CommandSender dbg) {
        Map<String,String> keys = cfg.getTranslatableModsWithLabels();
        Channel first = cfg.getTranslatableScanOrder() ==
                        ConfigManager.ScanOrder.ANVIL_THEN_SIGN
                            ? Channel.ANVIL : Channel.SIGN;
        beginProbe(player, keys, cfg.getTranslatableRetryCount(),
                   ignoreCooldown, dbg, false, first);
    }

    private void beginProbe(Player p,
                            Map<String,String> keys,
                            int retries,
                            boolean ignoreCd,
                            org.bukkit.command.CommandSender dbg,
                            boolean force,
                            Channel firstChan) {

        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled()) return;

        long now = System.currentTimeMillis();
        if (!ignoreCd) {
            long cd = cfg.getTranslatableCooldown()*1000L;
            if (now - cooldown.getOrDefault(p.getUniqueId(),0L) < cd) return;
            cooldown.put(p.getUniqueId(), now);
        }

        /* -------- pack keys into batches (1 line per anvil, 3 per sign) --- */
        int maxLine = (firstChan == Channel.ANVIL) ? 2048
                                                      : cfg.getTranslatableMaxLineLength();
        int maxLines = (firstChan == Channel.ANVIL) ? 1 : 3;

        List<List<String>> batches = new ArrayList<>();
        if (!keys.isEmpty()) {
            List<StringBuilder> buf = new ArrayList<>();
            for (int i=0;i<maxLines;i++) buf.add(new StringBuilder());
            int line = 0;
            for (String key : keys.keySet()) {
                if (buf.get(line).length() > 0 &&
                    buf.get(line).length() + DELIMITER.length() + key.length() > maxLine) {
                    line++;
                }
                if (line >= maxLines) {
                    batches.add(buf.stream()
                                   .map(StringBuilder::toString)
                                   .collect(Collectors.toList()));
                    buf.clear();
                    for (int i=0;i<maxLines;i++) buf.add(new StringBuilder());
                    line = 0;
                }
                if (buf.get(line).length() > 0) buf.get(line).append(DELIMITER);
                buf.get(line).append(key);
            }
            if (buf.get(0).length() > 0)
                batches.add(buf.stream()
                               .map(StringBuilder::toString)
                               .collect(Collectors.toList()));
        }

        Probe pr = new Probe();
        pr.iterator      = batches.iterator();
        pr.retriesLeft   = retries;
        pr.debug         = dbg;
        pr.forceSend     = force;
        pr.totalBatches  = batches.size();
        pr.channel       = firstChan;
        probes.put(p.getUniqueId(), pr);

        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] Starting "+firstChan+
                                    " probe for "+p.getName()+" with "+keys.size()+
                                    " keys in "+batches.size()+" packets");
        advance(p, pr);
    }

    /* =====================================================================
     *  STATE MACHINE
     * =================================================================== */
    private void advance(Player player, Probe pr) {
        if (pr.timeout != null) pr.timeout.cancel();

        if (!pr.iterator.hasNext()) {
            finishRound(player, pr);
            return;
        }

        if (pr.channel == Channel.SIGN && pr.signPos != null) sendAir(player, pr.signPos);

        pr.currentLines = pr.iterator.next();
        pr.uid          = randomUID();
        pr.currentBatch++;

        if (cfg.isTranslatableOnlyOnMove() && !pr.forceSend) {
            pr.waitingForMove = true;
        } else {
            dispatchBatch(player, pr);
        }
    }

    private void dispatchBatch(Player player, Probe pr) {
        pr.sendTime = System.currentTimeMillis();

        if (pr.channel == Channel.SIGN) {
            pr.signPos = buildFakeSign(player, pr.currentLines, pr.uid);
        } else {
            pr.syncId  = buildFakeAnvil(player, pr.currentLines, pr.uid);
        }

        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] Batch "+pr.currentBatch+ "/"+pr.totalBatches+
                                    " via "+pr.channel+" -> "+player.getName());

        pr.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cfg.isDebugMode())
                plugin.getLogger().warning("[Debug] Batch "+pr.currentBatch+
                                           " timed-out for "+player.getName());
            pr.currentLines.forEach(l -> Arrays.stream(l.split(DELIMITER,-1))
                                                  .filter(s -> !s.isEmpty())
                                                  .forEach(k -> handleResult(player, pr, k,false,null)));
            advance(player, pr);
        }, TIMEOUT_TICKS);
    }

    /* =====================================================================
     *  PACKET HANDLER
     * =================================================================== */
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        Player p = (Player) e.getPlayer();
        Probe pr = probes.get(p.getUniqueId());
        if (pr == null || pr.currentLines == null) return;

        if (pr.channel == Channel.SIGN &&
            e.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            handleSign(new WrapperPlayClientUpdateSign(e), p, pr);
            return;
        }

        if (pr.channel == Channel.ANVIL &&
            e.getPacketType() == PacketType.Play.Client.NAME_ITEM) {
            handleAnvil(new WrapperPlayClientNameItem(e), p, pr);
        }
    }

    private void handleSign(WrapperPlayClientUpdateSign pkt,
                            Player p, Probe pr) {
        String[] recv = pkt.getTextLines();
        if (recv.length < 4 || !pr.uid.equals(recv[3])) return;
        pr.timeout.cancel();

        for (int i=0;i<pr.currentLines.size();i++) {
            if (i >= recv.length) break;
            String[] original  = pr.currentLines.get(i).split(DELIMITER,-1);
            String[] translated= recv[i].split(DELIMITER,-1);
            for (int j=0;j<original.length;j++) {
                String key = original[j]; if (key.isEmpty()) continue;
                String resp= j<translated.length?translated[j]:key;
                boolean ok = !resp.isEmpty() && !resp.equals(key)
                                               && !resp.startsWith("{\"translate\"");
                handleResult(p, pr, key, ok, resp);
            }
        }
        advance(p, pr);
    }

    private void handleAnvil(WrapperPlayClientNameItem pkt,
                             Player p, Probe pr) {
        pr.timeout.cancel();

        String[] originalKeys   = pr.currentLines.get(0).split(DELIMITER,-1);
        String[] receivedNames  = pkt.getItemName().split(DELIMITER,-1);

        for (int i=0;i<originalKeys.length;i++) {
            String key = originalKeys[i];
            if (key.isEmpty()) continue;
            String translated = i<receivedNames.length ? receivedNames[i] : key;
            boolean ok = !translated.equals(key) && !translated.isEmpty();
            handleResult(p, pr, key, ok, translated);
        }
        advance(p, pr);
    }

    /* =====================================================================
     *  RESULT & ROUND END
     * =================================================================== */
    private void handleResult(Player p, Probe pr,
                              String key, boolean ok, String response) {
        if (pr.debug != null) {
            long ms = System.currentTimeMillis() - pr.sendTime;
            pr.debug.sendMessage(org.bukkit.ChatColor.AQUA+p.getName()+
                    org.bukkit.ChatColor.DARK_GRAY+" | "+
                    org.bukkit.ChatColor.GRAY+"Key: \""+
                    org.bukkit.ChatColor.WHITE+key+
                    org.bukkit.ChatColor.GRAY+"\" Response: \""+
                    org.bukkit.ChatColor.AQUA+(ok?response:"")+
                    org.bukkit.ChatColor.GRAY+"\" Time: "+
                    org.bukkit.ChatColor.AQUA+ms+"ms");
        }

        String label = cfg.getTranslatableModsWithLabels().get(key);
        if (label == null) return;

        if (ok) {
            if (cfg.isDebugMode())
                plugin.getLogger().info("[Debug] "+p.getName()+" translated ("+
                        pr.channel+"): '"+key+"' -> '"+response+"' ("+label+")");
            pr.translated.add(key);
            detect.handleTranslatable(p, TranslatableEventType.TRANSLATED,key);
        } else {
            pr.failedNext.put(key,label);
        }
    }

    private void finishRound(Player p, Probe pr) {
        /* channel-switch logic (sign ⇄ anvil) */
        if (pr.channel == Channel.SIGN &&
            cfg.getTranslatableScanOrder() == ConfigManager.ScanOrder.SIGN_THEN_ANVIL &&
            !pr.failedNext.isEmpty() && pr.retriesLeft >= 0) {
            beginProbe(p, pr.failedNext, pr.retriesLeft,
                       true, pr.debug, pr.forceSend, Channel.ANVIL);
            return;
        }
        if (pr.channel == Channel.ANVIL &&
            cfg.getTranslatableScanOrder() == ConfigManager.ScanOrder.ANVIL_THEN_SIGN &&
            !pr.failedNext.isEmpty() && pr.retriesLeft >= 0) {
            beginProbe(p, pr.failedNext, pr.retriesLeft,
                       true, pr.debug, pr.forceSend, Channel.SIGN);
            return;
        }

        /* required-key checks and clean-up */
        for (String req : cfg.getTranslatableRequiredKeys())
            if (!pr.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);

        if (pr.signPos != null) sendAir(p, pr.signPos);
        if (pr.channel == Channel.ANVIL && pr.syncId != -1) closeAnvil(p, pr.syncId);

        probes.remove(p.getUniqueId());
        if (cfg.isDebugMode())
            plugin.getLogger().info("[Debug] Probe finished for "+p.getName());
    }

    /* =====================================================================
     *  SIGN BUILD
     * =================================================================== */
    private Vector3i buildFakeSign(Player target,
                                   List<String> lines, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager()
                                       .getClientVersion(target);
        boolean modern = shouldUseModernFormat(cv);

        Vector3i pos = signPos(target);

        WrappedBlockState state = StateTypes.OAK_SIGN.createBlockState();
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockChange(pos, state.getGlobalId()));

        /* nbt compose identical to original code (unchanged) ------------- */
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

            } else { /* modern – JSON strings */
                NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
                msgs.addTag(new NBTString(createComponentJson(l1)));
                msgs.addTag(new NBTString(createComponentJson(l2)));
                msgs.addTag(new NBTString(createComponentJson(l3)));
                msgs.addTag(new NBTString("{\"text\":\""+uid+"\"}"));

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
        } else {                        /* legacy sign */
            nbt.setTag("Text1", new NBTString(createComponentJson(l1)));
            nbt.setTag("Text2", new NBTString(createComponentJson(l2)));
            nbt.setTag("Text3", new NBTString(createComponentJson(l3)));
            nbt.setTag("Text4", new NBTString(uid));
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerCloseWindow(0));

        return pos;
    }

    /* =====================================================================
     *  ANVIL BUILD
     * =================================================================== */
    /** Builds the anvil GUI & paper slot using ItemStack.Builder (no Bukkit → PE conversion). */
    private int buildFakeAnvil(Player p, List<String> lines, String uid) {
        int sync = ThreadLocalRandom.current().nextInt(1, 200);
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p, new WrapperPlayServerOpenWindow(sync, 8, Component.empty()));

        /* PacketEvents item stack with NBT display name */
        String packed = lines.isEmpty() ? "" : lines.get(0);
        String jsonName = createComponentJson(packed);

        NBTCompound display = new NBTCompound();
        display.setTag("Name", new NBTString(jsonName));
        NBTCompound tagRoot = new NBTCompound();
        tagRoot.setTag("display", display);

        ItemStack paper = ItemStack.builder()
                .type(ItemTypes.PAPER)
                .amount(1)
                .nbt(tagRoot)
                .build();

        PacketEvents.getAPI().getPlayerManager().sendPacket(
                p, new WrapperPlayServerSetSlot(sync, 0, 0, paper));
        return sync;
    }

    private void closeAnvil(Player p, int syncId) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(
                p, new WrapperPlayServerCloseWindow(syncId));
    }

    /* =====================================================================
     *  LOW-LEVEL HELPERS
     * =================================================================== */
    private String createComponentJson(String packedKeys) {
        if (packedKeys == null || packedKeys.isEmpty()) return "{\"text\":\"\"}";
        String extra = Arrays.stream(packedKeys.split(DELIMITER,-1))
                .map(k -> "{\"translate\":\""+k.replace("\"","\\\"")+"\"}")
                .collect(Collectors.joining(",{\"text\":\""+DELIMITER+"\"},"));
        return "{\"text\":\"\",\"extra\":["+extra+"]}";
    }

    private static boolean shouldUseModernFormat(ClientVersion cv) {
        if (cv == null) return true;
        try { if (cv.isNewerThanOrEquals(ClientVersion.V_1_20)) return true; }
        catch (Throwable ignore) {}
        try { return cv.getProtocolVersion() >=
                     ClientVersion.V_1_20.getProtocolVersion(); }
        catch (Throwable ignore) {}
        return false;
    }

    private static boolean detectInlineComponentFormat() {
        try {
            String[] ver = Bukkit.getBukkitVersion().split("-",2)[0].split("\\.");
            int minor = Integer.parseInt(ver[1]);
            int patch = ver.length > 2 ? Integer.parseInt(ver[2]) : 0;
            return minor > 20 || (minor == 20 && patch >= 5); // 1.20.5+ uses inline components
        } catch (Throwable t) {
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
    private static NBTCompound createComponentNbt(String packed) {
        if (packed == null || packed.isEmpty()) return textComponent("");
        NBTCompound root = textComponent("");
        NBTList<NBTCompound> extra = new NBTList<>(NBTType.COMPOUND);
        String[] parts = packed.split(DELIMITER,-1);
        for (int i=0;i<parts.length;i++) {
            extra.addTag(translateComponent(parts[i]));
            if (i < parts.length-1) extra.addTag(textComponent(DELIMITER));
        }
        root.setTag("extra", extra);
        return root;
    }

    private Vector3i signPos(Player p) {
        World w = p.getWorld();
        var ch  = p.getLocation().getChunk();
        int x = (ch.getX()+1)*16;
        int z = (ch.getZ()+1)*16;
        int y = w.getMaxHeight()-5;
        return new Vector3i(x,y,z);
    }

    private void sendAir(Player p, Vector3i pos) {
        if (p.isOnline())
            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    p, new WrapperPlayServerBlockChange(pos, AIR_ID));
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current()
                                                    .nextInt(0x10000));
    }
}