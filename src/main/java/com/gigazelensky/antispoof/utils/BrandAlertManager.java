package com.gigazelensky.antispoof.utils;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton manager for brand alerts to ensure no duplicate alerts are sent
 */
public class BrandAlertManager {
    private static final BrandAlertManager INSTANCE = new BrandAlertManager();
    private final Map<UUID, Long> alertTracker = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private static final long ALERT_COOLDOWN = 10000; // 10 seconds
    
    // Private constructor to enforce singleton
    private BrandAlertManager() {
        Bukkit.getLogger().info("[AntiSpoof] BrandAlertManager initialized");
    }
    
    /**
     * Get the singleton instance
     */
    public static BrandAlertManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Send a brand alert with duplicate prevention
     * 
     * @param plugin The plugin instance
     * @param player The player to alert about
     * @param brand The brand to alert about
     * @return true if the alert was sent, false if it was suppressed as a duplicate
     */
    public boolean sendBrandAlert(AntiSpoofPlugin plugin, Player player, String brand) {
        if (player == null) return false;
        
        UUID uuid = player.getUniqueId();
        ConfigManager config = plugin.getConfigManager();
        
        // Use a lock to ensure thread safety during the check-and-update operation
        lock.lock();
        try {
            // Check if we've recently sent a brand alert for this player
            long now = System.currentTimeMillis();
            Long lastAlert = alertTracker.get(uuid);
            
            if (lastAlert != null && now - lastAlert <= ALERT_COOLDOWN) {
                // Log the skipped alert
                plugin.getLogger().info("[AntiSpoof] [Debug] Skipping duplicate brand alert for " + player.getName() + " - within cooldown period (managed)");
                return false;
            }
            
            // Update the alert time BEFORE sending the alert
            alertTracker.put(uuid, now);
        } finally {
            lock.unlock();
        }
        
        // Log that we're about to send an alert (useful for debugging)
        if (config.isDebugMode()) {
            plugin.getLogger().info("[AntiSpoof] [Debug] Sending brand alert for " + player.getName() + " with brand: " + brand);
        }
        
        // Format the player alert message with placeholders
        String playerAlert = config.getBlockedBrandsAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown");
        
        // Format the console alert message with placeholders
        String consoleAlert = config.getBlockedBrandsConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown");
        
        // Convert color codes for player messages
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        
        // Log to console directly using the console format (no need to strip colors)
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if enabled for join brand alerts
        if (config.isDiscordWebhookEnabled() && config.isJoinBrandAlertsEnabled()) {
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                "Joined using client brand: " + brand, 
                brand, 
                null,
                null,
                "JOIN_BRAND"
            );
        }
        
        return true;
    }
    
    /**
     * Remove player from tracking on disconnect
     */
    public void playerDisconnected(UUID uuid) {
        if (uuid != null) {
            alertTracker.remove(uuid);
        }
    }
    
    /**
     * Clear all tracked alerts
     */
    public void clearAll() {
        alertTracker.clear();
    }
}