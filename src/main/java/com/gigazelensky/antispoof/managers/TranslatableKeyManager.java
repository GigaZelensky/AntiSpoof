
package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Probes clients for translation keys using the sign‑GUI trick.
 * <p>
 * For back‑ends &lt; 1.20 we skip the check because the packet‑layout changed
 * in 1.20 and PacketEvents 2.8.1 does not understand it correctly.
 * </p>
 * The probe‑flow:
 * <ol>
 *     <li>Place a temporary sign at y = ‑64 with up to four {@code Component.translatable(key)}</li>
 *     <li>Send {@code OpenSignEditor}</li>
 *     <li>After guiVisibleTicks the inventory is closed again and the old block restored</li>
 *     <li>Once the client sends {@code UpdateSign} we compare the returned lines
 *         with the raw keys we sent:</li>
 *     <ul>
 *         <li>line still contains the key –&gt; NOT translated</li>
 *         <li>line does <strong>not</strong> contain the key –&gt; translated successfully</li>
 *     </ul>
 * </ol>
 */
public class TranslatableKeyManager extends PacketListenerAbstract implements Listener {

    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    private final DetectionManager detectionManager;

    /** last probe‑time per player (cool‑down) */
    private final Map<UUID, Long> lastProbe = new ConcurrentHashMap<>();
    /** keys that are currently probed for each player */
    private final Map<UUID, List<String>> activeProbes = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin,
                                  DetectionManager detectionManager,
                                  ConfigManager config) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.config = config;
    }

    /* --------------------------------------------------------------------- */
    /* Lifecycle                                                             */
    /* --------------------------------------------------------------------- */

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        if (!config.isTranslatableKeysEnabled()) return;

        int delay = config.getTranslatableFirstDelay();
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> probe(ev.getPlayer()),
                delay
        );
    }

    /* --------------------------------------------------------------------- */
    /* Probe logic                                                           */
    /* --------------------------------------------------------------------- */

    /**
     * Sends the fake‑sign to the player.
     */
    public void probe(Player player) {
        if (!config.isTranslatableKeysEnabled()) return;
        if (getServerMinor() < 20)            return;   // backend &lt; 1.20

        // Cool‑down
        long now = System.currentTimeMillis();
        long last = lastProbe.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < config.getTranslatableCooldown()) return;
        lastProbe.put(player.getUniqueId(), now);

        Map<String, String> tests = config.getTranslatableTestKeys();
        if (tests.isEmpty()) return;

        // We can only fit four lines. Select in a reproducible order
        List<String> keys = new ArrayList<>(tests.keySet());
        // keep insertion order (LinkedHashMap in ConfigManager)
        keys = keys.subList(0, Math.min(4, keys.size()));
        activeProbes.put(player.getUniqueId(), keys);

        // ------------------------------------------------------------------
        // Build the temporary sign
        // ------------------------------------------------------------------
        Material signMat = getMaterial("OAK_SIGN", "SIGN_POST", "SIGN");
        if (signMat == null) return;

        Location loc = player.getLocation().clone();
        loc.setY(-64);
        Block block = loc.getBlock();
        Material oldType = block.getType();
        block.setType(signMat, false);

        BlockState state = block.getState();
        if (state instanceof Sign sign) {
            for (int lineIdx = 0; lineIdx < 4 && lineIdx < keys.size(); lineIdx++) {
                String key = keys.get(lineIdx);
                try {
                    // Adventure is only present on modern runtimes; reflect to keep 1.8 compile‑compat
                    Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                    Object component = componentClass
                            .getMethod("translatable", String.class)
                            .invoke(null, key);

                    // Since 1.20 signs have two sides – obtain the "front" side reflectively if available
                    Class<?> sideEnum = Class.forName("org.bukkit.block.sign.Side");
                    Object front = Enum.valueOf((Class<Enum>) sideEnum, "FRONT");
                    Object signSide = sign.getClass().getMethod("getSide", sideEnum).invoke(sign, front);
                    signSide.getClass().getMethod("setLine", int.class, componentClass)
                            .invoke(signSide, lineIdx, component);
                } catch (Throwable reflectiveFail) {
                    // Fallback for very old versions where adventure&nbsp;components are missing
                    sign.setLine(lineIdx, key);
                }
            }
            sign.update(true, false);
        }

        /* ------------------------------------------------------------------
           Packet‑flow:
           1) update block (already done)
           2) open sign editor
           3) close & restore after guiVisibleTicks
         ------------------------------------------------------------------ */
        Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        WrapperPlayServerOpenSignEditor open = new WrapperPlayServerOpenSignEditor(pos);

        // Send the open packet one tick later so the BlockEntityData arrives first
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, open),
                1);

        // Close GUI & restore block after configured delay
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> {
                    player.closeInventory();
                    block.setType(oldType, false);
                },
                config.getTranslatableGuiVisibleTicks());
    }

    /* --------------------------------------------------------------------- */
    /* Packet listener                                                       */
    /* --------------------------------------------------------------------- */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player player = (Player) event.getPlayer();

        List<String> probed = activeProbes.remove(player.getUniqueId());
        if (probed == null || probed.isEmpty()) return;  // not our probe

        // Extract lines via reflection to stay version‑agnostic
        String[] linesArray;
        try {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
            linesArray = (String[]) WrapperPlayClientUpdateSign.class
                    .getMethod("getLines")
                    .invoke(wrapper);
        } catch (Throwable t) {
            return; // can't read – abort
        }
        List<String> lines = Arrays.asList(linesArray);

        Map<String, String> tests = config.getTranslatableTestKeys();
        Set<String> required = new HashSet<>(config.getTranslatableRequiredKeys());

        boolean anyTranslated = false;
        boolean anyRequiredMiss = false;

        for (String key : probed) {
            boolean rawPresent = lines.stream().anyMatch(l -> l.contains(key));

            if (!rawPresent) {
                anyTranslated = true;
                detectionManager.handleTranslatable(
                        player, TranslatableEventType.TRANSLATED, tests.getOrDefault(key, key));
            } else if (required.contains(key)) {
                anyRequiredMiss = true;
                detectionManager.handleTranslatable(
                        player, TranslatableEventType.REQUIRED_MISS, tests.getOrDefault(key, key));
            }
        }

        // If nothing happened at all – the probe was blocked
        if (!anyTranslated && !anyRequiredMiss) {
            detectionManager.handleTranslatable(player, TranslatableEventType.ZERO, "-");
        }
    }

    /* --------------------------------------------------------------------- */
    /* Helpers                                                               */
    /* --------------------------------------------------------------------- */
    private int getServerMinor() {
        String[] parts = Bukkit.getBukkitVersion().split("-")[0].split("\.");
        return parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
    }

    /**
     * Attempts to resolve the first {@link Material} that exists on the current server.
     */
    private Material getMaterial(String... ids) {
        for (String id : ids) {
            try {
                return Material.valueOf(id);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}
