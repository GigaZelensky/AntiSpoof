package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
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

    public PlayerEventListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public void register() {
        // Register Bukkit event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);
        
        // Register packet event listener
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        
        // Skip if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) return;

        boolean wasChannelRegistered = false;
        
        // Handle plugin messages based on the packet type
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            wasChannelRegistered = handlePluginMessage(player, packet.getChannelName(), packet.getData());
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            wasChannelRegistered = handlePluginMessage(player, packet.getChannelName(), packet.getData());
        }
    }
    
    private boolean handlePluginMessage(Player player, String channel, byte[] data) {
        boolean channelRegistered = false;
        
        // Handle channel registration/unregistration (for Fabric/Forge mods)
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            channelRegistered = handleChannelRegistration(player, channel, data);
        } else {
            // Direct channel usage - check if this is a new channel
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
                // Register the channel and trigger checks if needed
                if (plugin.getDetectionManager().addPlayerChannel(player, registeredChannel, true)) {
                    didRegister = true;
                }
            } else {
                // Unregister the channel
                plugin.getDetectionManager().removePlayerChannel(player, registeredChannel);
            }
        }
        
        return didRegister;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Register player for alerts if they have permission
        plugin.getAlertManager().registerPlayer(player);
        
        // Skip if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) return;
        
        // Create initial player data
        UUID uuid = player.getUniqueId();
        plugin.getPlayerDataMap().computeIfAbsent(uuid, k -> new PlayerData());
        
        // Special handling for no-brand detection
        if (config.isNoBrandCheckEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Only process if still null after 1 second (allows time for brand packet)
                if (player.isOnline() && plugin.getClientBrand(player) == null) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Processing no-brand alert for " + player.getName());
                    }
                    
                    // Manually trigger alert for NO_BRAND violation
                    Map<String, String> noBrandViolation = new HashMap<>();
                    noBrandViolation.put("NO_BRAND", "No client brand detected");
                    
                    // Process on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getDetectionManager().processViolation(player, "NO_BRAND", "No client brand detected");
                    });
                }
            }, 20L); // 1 second delay to allow brand packet to arrive
        }
        
        // Schedule the standard check based on configured delay
        int delay = config.getCheckDelay();
        
        // If delay is greater than 0, schedule the check
        if (delay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, 
                () -> plugin.getDetectionManager().checkPlayerAsync(player, true), 
                delay * 20L);
        } else if (delay == 0) {
            // For zero delay, check immediately
            plugin.getDetectionManager().checkPlayerAsync(player, true);
        }
        // If delay is negative, rely on packet listener checks only
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        
        // Use the centralized quit handler in plugin to clean up everything
        plugin.handlePlayerQuit(uuid);
    }
}