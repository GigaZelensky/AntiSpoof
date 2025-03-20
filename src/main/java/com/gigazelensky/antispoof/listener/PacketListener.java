package com.gigazelensky.antispoof.listener;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles packet events for client brand and channel detection
 */
public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;
    
    // These are the channel names for client brand in different versions
    private static final String BRAND_CHANNEL_LEGACY = "MC|Brand";
    private static final String BRAND_CHANNEL_MODERN = "minecraft:brand";
    
    // Keep track of players we've processed for testing
    private final Map<String, Boolean> processedBrands = new HashMap<>();
    
    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Registers this listener with PacketEvents
     */
    public void register() {
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // We only care about plugin messages
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) {
            return;
        }
        
        // Get the player from the event
        User user = event.getUser();
        if (!(user.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) user.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        
        // Skip if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) {
            return;
        }
        
        // Handle plugin message
        WrapperPlayClientPluginMessage message = new WrapperPlayClientPluginMessage(event);
        String channel = message.getChannelName();
        byte[] data = message.getData();
        
        // Check if this is a brand message
        if (channel.equals(BRAND_CHANNEL_MODERN) || channel.equals(BRAND_CHANNEL_LEGACY)) {
            try {
                String brand = extractBrand(data);
                
                if (brand != null && !brand.isEmpty()) {
                    if (plugin.getConfigManager().isDebugMode()) {
                        plugin.getLogger().info("[Debug] Brand packet from " + player.getName() + 
                                            ": '" + brand + "' (raw data length: " + data.length + ")");
                        
                        // Log the data for debugging
                        StringBuilder sb = new StringBuilder();
                        sb.append("Raw data: [");
                        for (byte b : data) {
                            sb.append(String.format("%02X ", b));
                        }
                        sb.append("]");
                        plugin.getLogger().info("[Debug] " + sb.toString());
                        
                        // For testing, track if we've processed this player
                        processedBrands.put(player.getName(), true);
                    }
                    
                    // Process the brand - we need to do this on the main thread
                    final String finalBrand = brand;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getDetectionService().handleClientBrand(player, finalBrand);
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error processing brand for " + player.getName() + ": " + e.getMessage());
                if (plugin.getConfigManager().isDebugMode()) {
                    e.printStackTrace();
                }
            }
            return;
        }
        
        // Handle register/unregister channels
        if (channel.equals("minecraft:register")) {
            handleChannelRegistration(player, data);
        } else if (channel.equals("minecraft:unregister")) {
            handleChannelUnregistration(player, data);
        } else {
            // This is a direct plugin channel use - register it
            plugin.getDetectionService().handleChannelRegistration(player, channel);
        }
    }
    
    /**
     * Extracts the brand from packet data
     */
    private String extractBrand(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        
        try {
            // Try different extraction methods
            
            // Method 1: Standard format with length prefix (most common)
            if (data.length > 1 && data[0] <= data.length - 1) {
                return new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
            }
            
            // Method 2: Direct string without length prefix
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Log and return null on error
            plugin.getLogger().warning("Failed to extract brand: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Handles a channel registration message
     */
    private void handleChannelRegistration(Player player, byte[] data) {
        try {
            String payload = new String(data, StandardCharsets.UTF_8);
            String[] channels = payload.split("\0");
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Channel registration from " + player.getName() + 
                                      ": " + String.join(", ", channels));
            }
            
            for (String channel : channels) {
                if (channel.isEmpty()) continue;
                plugin.getDetectionService().handleChannelRegistration(player, channel);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing channel registration: " + e.getMessage());
        }
    }
    
    /**
     * Handles a channel unregistration message
     */
    private void handleChannelUnregistration(Player player, byte[] data) {
        try {
            String payload = new String(data, StandardCharsets.UTF_8);
            String[] channels = payload.split("\0");
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Channel unregistration from " + player.getName() + 
                                      ": " + String.join(", ", channels));
            }
            
            for (String channel : channels) {
                if (channel.isEmpty()) continue;
                plugin.getDetectionService().handleChannelUnregistration(player, channel);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing channel unregistration: " + e.getMessage());
        }
    }
}