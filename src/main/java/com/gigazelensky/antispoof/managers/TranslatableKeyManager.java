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
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    /* --------------------------------------------------------------------- */
    private static final int TIMEOUT_TICKS = 20;  // 1 s
    private static final int AIR_ID = 0;   // block id for air
    private static final double MOVE_EPSILON = 0.0001; // ~1cm^2
    private static final float ROT_EPSILON = 1.5f;

    private final AntiSpoofPlugin plugin;
    private final DetectionManager detect;
    private final ConfigManager cfg;

    public enum ProbeType {
        SIGN, ANVIL
    }

    /* ACTIVE PROBES ******************************************************** */
    private static final class Probe {
        Iterator<List<Map.Entry<String, String>>> iterator;
        final Set<String> translated = new HashSet<>();
        final Map<String, String> failedForNext = new LinkedHashMap<>();
        int retriesLeft;

        List<Map.Entry<String, String>> currentEntries = null;
        String[] keys = null;
        String uid = null;
        Vector3i lastBlockPos = null;
        BukkitTask timeoutTask;
        boolean waitingForMove = false;
        org.bukkit.command.CommandSender debug;
        long sendTime;
        boolean forceSend = false;

        ProbeType currentProbeType;
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

    private ProbeType getInitialProbeType() {
        String methodName = cfg.getInitialProbeMethod();
        return ProbeType.valueOf(methodName);
    }

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
                            getInitialProbeType(),
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
        if (p != null && p.lastBlockPos != null)
            sendAir(e.getPlayer(), p.lastBlockPos);
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

        UUID id = e.getPlayer().getUniqueId();

        if (cfg.isTranslatableOnlyOnMove()) {
            if (pendingStart.remove(id)) {
                int delay = cfg.getTranslatableFirstDelay();
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> beginProbe(e.getPlayer(),
                                cfg.getTranslatableModsWithLabels(),
                                getInitialProbeType(),
                                cfg.getTranslatableRetryCount(),
                                false,
                                null,
                                false),
                        delay);
                if (cfg.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Movement detected, starting initial probe for " + e.getPlayer().getName());
                }
            }
        }

        Probe p = probes.get(id);
        if (p != null && p.waitingForMove) {
            if(cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Player moved, sending next probe to " + e.getPlayer().getName());
            }
            p.waitingForMove = false;
            placeNextProbe(e.getPlayer(), p);
        }
    }

    public void sendKeybind(Player target, String key,
                            org.bukkit.command.CommandSender dbg) {
        Map<String, String> single = Collections.singletonMap(key, key);
        beginProbe(target, single, ProbeType.SIGN, 0, true, dbg, true);
    }

    public void runMods(Player target) {
        beginProbe(target,
                cfg.getTranslatableModsWithLabels(),
                getInitialProbeType(),
                cfg.getTranslatableRetryCount(),
                true,
                null,
                true); // Manually running mods should always be forced
    }

    public void stopMods(Player target) {
        UUID id = target.getUniqueId();
        pendingStart.remove(id);
        Probe p = probes.remove(id);
        if (p != null) {
            if (p.timeoutTask != null) p.timeoutTask.cancel();
            if (p.lastBlockPos != null) sendAir(target, p.lastBlockPos);
        }
    }

    private void beginProbe(Player p,
                            Map<String, String> keys,
                            ProbeType probeType,
                            int retries,
                            boolean ignoreCooldown,
                            org.bukkit.command.CommandSender dbg,
                            boolean forceSend) {
        if (!p.isOnline() || !cfg.isTranslatableKeysEnabled() || keys.isEmpty()) return;

        long now = System.currentTimeMillis();
        long cd = cfg.getTranslatableCooldown() * 1000L;
        if (!ignoreCooldown && now - cooldown.getOrDefault(p.getUniqueId(), 0L) < cd) return;
        if (!ignoreCooldown) cooldown.put(p.getUniqueId(), now);

        int chunkSize = (probeType == ProbeType.SIGN) ? 3 : 1;

        List<Map.Entry<String, String>> interleavedKeys = new ArrayList<>(keys.entrySet());
        List<List<Map.Entry<String, String>>> keyGroups = new ArrayList<>();
        for (int i = 0; i < interleavedKeys.size(); i += chunkSize) {
            keyGroups.add(new ArrayList<>(interleavedKeys.subList(i, Math.min(interleavedKeys.size(), i + chunkSize))));
        }

        Probe probe = new Probe();
        probe.iterator = keyGroups.iterator();
        probe.retriesLeft = retries;
        probe.debug = dbg;
        probe.forceSend = forceSend;
        probe.currentProbeType = probeType;
        probes.put(p.getUniqueId(), probe);

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting " + probeType + " probe for " +
                    p.getName() + " with " + keys.size() + " keys (retries=" +
                    retries + ")");
        }

        advance(p, probe);
    }

    private void advance(Player player, Probe probe) {
        if (probe.timeoutTask != null) probe.timeoutTask.cancel();

        if (!probe.iterator.hasNext()) {
            finishRound(player, probe);
            return;
        }

        if (probe.lastBlockPos != null) sendAir(player, probe.lastBlockPos);

        probe.currentEntries = probe.iterator.next();
        probe.keys = new String[probe.currentEntries.size()];
        for (int i = 0; i < probe.currentEntries.size(); i++) {
            probe.keys[i] = probe.currentEntries.get(i).getKey();
        }
        probe.uid = randomUID();

        if (cfg.isTranslatableOnlyOnMove() && !probe.forceSend) {
            probe.waitingForMove = true;
        } else {
            placeNextProbe(player, probe);
        }
    }

    private void placeNextProbe(Player player, Probe probe) {
        if (probe.currentProbeType == ProbeType.SIGN) {
            probe.lastBlockPos = buildFakeSign(player, probe.keys, probe.uid);
        } else {
            probe.lastBlockPos = buildFakeAnvil(player, probe.keys[0], probe.uid);
        }

        if (cfg.isDebugMode()) {
            plugin.getLogger().info("[Debug] [" + probe.currentProbeType + "] Sent keys '" + String.join("', '", probe.keys) + "' to " +
                    player.getName());
        }

        probe.sendTime = System.currentTimeMillis();
        probe.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<String, String> entry : probe.currentEntries) {
                handleResult(player, probe, entry.getKey(), entry.getValue(), false, null);
            }
            advance(player, probe);
        }, TIMEOUT_TICKS);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        Player p = (Player) e.getPlayer();
        if (p == null) return;
        Probe probe = probes.get(p.getUniqueId());
        if (probe == null || probe.keys == null) return;

        if (e.getPacketType() == PacketType.Play.Client.UPDATE_SIGN && probe.currentProbeType == ProbeType.SIGN) {
            handleSignUpdate(e, p, probe);
        } else if (e.getPacketType() == PacketType.Play.Client.NAME_ITEM && probe.currentProbeType == ProbeType.ANVIL) {
            handleAnvilUpdate(e, p, probe);
        }
    }

    private void handleSignUpdate(PacketReceiveEvent e, Player p, Probe probe) {
        String[] recv = new WrapperPlayClientUpdateSign(e).getTextLines();
        if (recv.length < 4 || !probe.uid.equals(recv[3])) return;

        probe.timeoutTask.cancel();

        for (int i = 0; i < probe.currentEntries.size(); i++) {
            Map.Entry<String, String> entry = probe.currentEntries.get(i);
            boolean translated = !recv[i].isEmpty() && !recv[i].equals(entry.getKey()) && !recv[i].startsWith("{\"translate\"");
            handleResult(p, probe, entry.getKey(), entry.getValue(), translated, recv[i]);
        }
        advance(p, probe);
    }

    private void handleAnvilUpdate(PacketReceiveEvent e, Player p, Probe probe) {
        String newName = new PacketWrapper<>(e).readString();

        if (newName == null || newName.contains(probe.uid)) return;

        probe.timeoutTask.cancel();

        Map.Entry<String, String> entry = probe.currentEntries.get(0);
        boolean translated = !newName.isEmpty() && !newName.equals(entry.getKey());
        handleResult(p, probe, entry.getKey(), entry.getValue(), translated, newName);

        advance(p, probe);
    }


    private void handleResult(Player p, Probe probe, String key, String label, boolean translated, String response) {
        long ms = System.currentTimeMillis() - probe.sendTime;
        if (probe.debug != null) {
            String text = translated ? response : "";
            probe.debug.sendMessage(org.bukkit.ChatColor.AQUA + p.getName() +
                    org.bukkit.ChatColor.DARK_GRAY + " | [" + probe.currentProbeType + "] " +
                    org.bukkit.ChatColor.GRAY + "Key: \"" +
                    org.bukkit.ChatColor.WHITE + key +
                    org.bukkit.ChatColor.GRAY + "\" Response: \"" +
                    org.bukkit.ChatColor.AQUA + text +
                    org.bukkit.ChatColor.GRAY + "\" Time: " +
                    org.bukkit.ChatColor.AQUA + ms + "ms");
        }

        if (cfg.isDebugMode()) {
            String res = translated ? ("\"" + response + "\"") : "<no translation>";
            plugin.getLogger().info("[Debug] [" + probe.currentProbeType + "] Key " + key + " for " + p.getName() +
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
        if (probe.lastBlockPos != null) sendAir(p, probe.lastBlockPos);

        if (probe.currentProbeType == ProbeType.SIGN && !probe.failedForNext.isEmpty()) {
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] Sign probe round finished for " + p.getName() +
                        ". Retrying " + probe.failedForNext.size() + " failed keys with ANVIL probe.");
            }
            // CORRECTED: The anvil follow-up probe should ALWAYS be forced, ignoring the move check.
            beginProbe(p, probe.failedForNext, ProbeType.ANVIL, probe.retriesLeft, true, probe.debug, true);
            return;
        }

        for (String req : cfg.getTranslatableRequiredKeys()) {
            if (!probe.translated.contains(req))
                detect.handleTranslatable(p, TranslatableEventType.REQUIRED_MISS, req);
        }

        if (probe.retriesLeft > 0 && !probe.failedForNext.isEmpty()) {
            int interval = cfg.getTranslatableRetryInterval();
            final ProbeType nextRetryType = probe.currentProbeType;
            // CORRECTED: A retry should also be forced to run immediately.
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                            beginProbe(p, probe.failedForNext, nextRetryType, probe.retriesLeft - 1, true, probe.debug, true),
                    interval);
        } else {
            probes.remove(p.getUniqueId());
            if (cfg.isDebugMode()) {
                plugin.getLogger().info("[Debug] All probe rounds finished for " + p.getName());
            }
        }
    }

    private Vector3i getBlockPos(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = Math.min(w.getMaxHeight() - 2, p.getLocation().getBlockY() + 24);
        int z = p.getLocation().getBlockZ();
        return new Vector3i(x, y, z);
    }

    private void sendAir(Player p, Vector3i pos) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p, new WrapperPlayServerBlockChange(pos, AIR_ID));
    }

    private static String randomUID() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }

    private Vector3i buildFakeSign(Player target, String[] keys, String uid) {
        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);
        Vector3i pos = getBlockPos(target);

        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState(cv);
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        if (modern) {
            NBTList<NBTString> msgs = new NBTList<>(NBTType.STRING);
            String key1 = keys.length > 0 ? keys[0] : "";
            String key2 = keys.length > 1 ? keys[1] : "";
            String key3 = keys.length > 2 ? keys[2] : "";
            msgs.addTag(new NBTString("{\"translate\":\"" + key1 + "\"}"));
            msgs.addTag(new NBTString("{\"translate\":\"" + key2 + "\"}"));
            msgs.addTag(new NBTString("{\"translate\":\"" + key3 + "\"}"));
            msgs.addTag(new NBTString("{\"text\":\"" + uid + "\"}"));
            NBTCompound front = new NBTCompound();
            front.setTag("messages", msgs);
            nbt.setTag("front_text", front);
        } else {
            String key1 = keys.length > 0 ? keys[0] : "";
            String key2 = keys.length > 1 ? keys[1] : "";
            String key3 = keys.length > 2 ? keys[2] : "";
            nbt.setTag("Text1", new NBTString("{\"translate\":\"" + key1 + "\"}"));
            nbt.setTag("Text2", new NBTString("{\"translate\":\"" + key2 + "\"}"));
            nbt.setTag("Text3", new NBTString("{\"translate\":\"" + key3 + "\"}"));
            nbt.setTag("Text4", new NBTString(uid));
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerCloseWindow(0));

        return pos;
    }

    private Vector3i buildFakeAnvil(Player target, String key, String uid) {
        Vector3i pos = getBlockPos(target);

        WrappedBlockState anvilState = StateTypes.ANVIL.createBlockState(PacketEvents.getAPI().getPlayerManager().getClientVersion(target));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, anvilState.getGlobalId()));

        Component itemName = Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.translatable(key))
                .append(Component.text("§0§0§0§f" + uid))
                .build();

        NBTCompound display = new NBTCompound();
        display.setTag("Name", new NBTString(GsonComponentSerializer.gson().serialize(itemName)));
        NBTCompound tag = new NBTCompound();
        tag.setTag("display", display);
        
        ItemStack probeItem = ItemStack.builder()
                .type(ItemTypes.PAPER)
                .amount(1)
                .nbt(tag)
                .build();

        int containerId = 1;
        Component title = Component.text(" ");
        WrapperPlayServerOpenWindow openWindow = new WrapperPlayServerOpenWindow(containerId, 8, title);
        WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(containerId, 0, 0, probeItem);
        WrapperPlayServerCloseWindow closeWindow = new WrapperPlayServerCloseWindow(containerId);

        PacketEvents.getAPI().getPlayerManager().sendPacket(target, openWindow);
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, setSlot);
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, closeWindow);

        sendAir(target, pos);

        return pos;
    }
}