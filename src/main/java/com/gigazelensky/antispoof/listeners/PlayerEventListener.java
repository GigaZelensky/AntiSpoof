package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.managers.TranslatableKeyManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign; // ADDED IMPORT
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerEventListener extends PacketListenerAbstract implements Listener {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    private final TranslatableKeyManager translatableKeyManager;
    
    private static final long REQUIRED_CHANNEL_CHECK_DELAY = 5 * 20L;

    public PlayerEventListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.translatableKeyManager = plugin.getTranslatableKeyManager();
    }

    public void register() {
        // This only registers the Bukkit events. The packet listener is registered in onEnable.
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        
        if (player.hasPermission("antispoof.bypass")) return;
        
        // CONSOLIDATED LISTENER LOGIC
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        } else if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            // If it's a sign update, delegate to the TranslatableKeyManager
            WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
            translatableKeyManager.handleSignUpdate(player, packet.getTextLines());
        }
    }
    
    private boolean handlePluginMessage(Player player, String channel, byte[] data) {
        boolean channelRegistered = false;
        
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            channelRegistered = handleChannelRegistration(player, channel, data);
        } else {
            channelRegistered = plugin.getDetectionManager().addPlayerChannel(player, channel, true);
        }
        
        return channelRegistered;
    }
    
    private boolean handleChannelRegistration(Player player, String channel, byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        boolean didRegister = false;
        
        for (String registeredChannel : channels) {
            if (channel.equals("minecraft:register")) {
                if (plugin.getDetectionManager().addPlayerChannel(player, registeredChannel, true)) {
                    didRegister = true;
                }
            } else {
                plugin.getDetectionManager().removePlayerChannel(player, registeredChannel);
            }
        }
        
        return didRegister;
    }
    
    private void scheduleInitialBrandCheck(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Running initial brand check for " + player.getName() + 
                                         " (without required channels check)");
                }
                plugin.getDetectionManager().checkPlayerAsync(player, true, false);
            }
        }, delayTicks);
    }

    private void scheduleRequiredChannelsCheck(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Running complete check with required channels for " + player.getName());
                }
                plugin.getDetectionManager().checkPlayerAsync(player, false, true);
            }
        }, delayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        plugin.getAlertManager().registerPlayer(player);
        
        if (player.hasPermission("antispoof.bypass")) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = new PlayerData();
        plugin.getPlayerDataMap().put(uuid, data);
        
        if (config.isNoBrandCheckEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && plugin.getClientBrand(player) == null) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Processing no-brand alert for " + player.getName());
                    }
                    
                    Map<String, String> noBrandViolation = new HashMap<>();
                    noBrandViolation.put("NO_BRAND", "No client brand detected");
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getDetectionManager().processViolation(player, "NO_BRAND", "No client brand detected");
                    });
                }
            }, 20L);
        }
        
        int standardDelay = config.getCheckDelay();
        
        if (standardDelay >= 0) {
            scheduleInitialBrandCheck(player, standardDelay * 20L);
        }
        
        scheduleRequiredChannelsCheck(player, REQUIRED_CHANNEL_CHECK_DELAY);

        int tDelay = config.getTranslatableFirstDelay();
        if (tDelay >= 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    translatableKeyManager.probe(player);
                }
            }, tDelay);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.handlePlayerQuit(uuid);
    }
}