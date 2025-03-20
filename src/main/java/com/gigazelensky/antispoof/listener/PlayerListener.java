package com.gigazelensky.antispoof.listener;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * Handles player events
 */
public class PlayerListener implements Listener {
    private final AntiSpoofPlugin plugin;
    
    public PlayerListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Registers this listener with Bukkit
     */
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Register the player for alert notifications if they have permission
        plugin.getAlertService().registerPlayer(player);
        
        // Skip further processing if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Player " + player.getName() + " bypassed detection (permission)");
            }
            return;
        }
        
        // Create player session
        plugin.registerPlayerSession(player);
        
        // Schedule detection with configured delay
        int delay = plugin.getConfigManager().getCheckDelay();
        if (delay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getDetectionService().checkPlayer(player);
                }
            }, delay * 20L); // Convert to ticks
        } else {
            // Immediate check
            plugin.getDetectionService().checkPlayer(player);
        }
        
        // Update checker - notify admins about available updates
        if (player.hasPermission("antispoof.admin") && 
            plugin.getConfigManager().isUpdateCheckerEnabled() && 
            plugin.getConfigManager().isUpdateNotifyOnJoinEnabled()) {
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    // VersionChecker will handle the notification
                }
            }, 40L); // 2 seconds delay
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerLeave(event.getPlayer());
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerLeave(event.getPlayer());
    }
    
    /**
     * Shared handler for player leave events
     */
    private void handlePlayerLeave(Player player) {
        if (player == null) return;
        
        // Clean up player data
        plugin.getAlertService().unregisterPlayer(player.getUniqueId());
        plugin.getDiscordService().handlePlayerQuit(player.getUniqueId());
        plugin.removePlayerSession(player.getUniqueId());
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Cleaned up player data for " + player.getName());
        }
    }
}