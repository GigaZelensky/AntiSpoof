package com.gigazelensky.antispoof.utils;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the brand message channel directly to prevent duplicate messages
 */
public class BrandChannelHandler implements PluginMessageListener {
    private final AntiSpoofPlugin plugin;
    // Track already processed brand messages
    private final ConcurrentHashMap<UUID, String> processedBrands = new ConcurrentHashMap<>();
    // Track players that have already received alerts
    private final Set<UUID> alertedPlayers = new HashSet<>();
    
    public BrandChannelHandler(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        // Extract brand name (skip first byte which is the length)
        String brand = new String(message).substring(1);
        UUID playerUUID = player.getUniqueId();
        
        // Store in plugin's brand map
        plugin.getPlayerBrands().put(player.getName(), brand);
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] BrandChannelHandler: Received brand for " + 
                                  player.getName() + ": " + brand);
        }
        
        // If player already got an alert for this brand, don't send another one
        if (processedBrands.containsKey(playerUUID) && 
            processedBrands.get(playerUUID).equals(brand) &&
            alertedPlayers.contains(playerUUID)) {
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] BrandChannelHandler: Skipping duplicate brand alert for " + 
                                      player.getName());
            }
            return;
        }
        
        // Store the brand to prevent duplicates
        processedBrands.put(playerUUID, brand);
        
        // Only do brand alert check on the scheduled server thread to avoid race conditions
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Check if this brand is not in the whitelist/is in the blacklist
            if (!player.hasPermission("antispoof.bypass") && 
                plugin.getConfigManager().isBlockedBrandsEnabled() && 
                !plugin.getConfigManager().matchesBrandPattern(brand) && 
                !alertedPlayers.contains(playerUUID)) {
                
                // Mark player as alerted
                alertedPlayers.add(playerUUID);
                
                // Format the player alert message
                String playerAlert = plugin.getConfigManager().getBlockedBrandsAlertMessage()
                        .replace("%player%", player.getName())
                        .replace("%brand%", brand);
                
                // Format the console alert message
                String consoleAlert = plugin.getConfigManager().getBlockedBrandsConsoleAlertMessage()
                        .replace("%player%", player.getName())
                        .replace("%brand%", brand);
                
                // Convert color codes for player messages
                String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
                
                // Log to console
                plugin.getLogger().info(consoleAlert);
                
                // Notify players with permission
                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("antispoof.alerts"))
                        .forEach(p -> p.sendMessage(coloredPlayerAlert));
                
                // Send to Discord if enabled
                if (plugin.getConfigManager().isDiscordWebhookEnabled() && 
                    plugin.getConfigManager().isJoinBrandAlertsEnabled()) {
                    plugin.getDiscordWebhookHandler().sendAlert(
                        player, 
                        "Joined using client brand: " + brand, 
                        brand, 
                        null,
                        null,
                        "JOIN_BRAND"
                    );
                }
            }
        });
    }
    
    /**
     * Cleanup method to remove player data on disconnect
     */
    public void playerDisconnected(UUID playerUUID) {
        processedBrands.remove(playerUUID);
        alertedPlayers.remove(playerUUID);
    }
    
    /**
     * Clear all tracking data
     */
    public void clearAll() {
        processedBrands.clear();
        alertedPlayers.clear();
    }
}