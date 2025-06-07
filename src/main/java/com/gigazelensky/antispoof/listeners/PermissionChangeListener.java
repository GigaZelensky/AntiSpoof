package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Listener to track permission changes that could affect alert recipients
 */
public class PermissionChangeListener implements Listener {
    private final AntiSpoofPlugin plugin;
    
    public PermissionChangeListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Listen for permission-related commands to update alert recipients
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreProcess(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase();
        
        // Only check for permission-related commands
        if (command.startsWith("/perm") || command.startsWith("/permission") || 
            command.startsWith("/lp") || command.startsWith("/luckperms") ||
            command.startsWith("/op") || command.startsWith("/deop") || 
            command.startsWith("/group") || command.contains("permission")) {
            
            // Schedule a check after command execution completes
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                updateAllPlayerPermissions();
            }, 1L);
        }
    }
    
    /**
     * Update permissions when a permission plugin is enabled or disabled
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        String pluginName = event.getPlugin().getName().toLowerCase();
        if (isPermissionPlugin(pluginName)) {
            // Wait a bit longer for plugin to fully initialize
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                updateAllPlayerPermissions();
            }, 20L); // 1 second delay
        }
    }
    
    /**
     * Update permissions when a permission plugin is disabled
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String pluginName = event.getPlugin().getName().toLowerCase();
        if (isPermissionPlugin(pluginName)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                updateAllPlayerPermissions();
            }, 1L);
        }
    }
    
    /**
     * Check if the plugin is a permission management plugin
     */
    private boolean isPermissionPlugin(String pluginName) {
        return pluginName.contains("perm") || 
               pluginName.equals("luckperms") || 
               pluginName.equals("permissionsex") ||
               pluginName.equals("groupmanager");
    }
    
    /**
     * Update permission status for all online players
     */
    private void updateAllPlayerPermissions() {
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Updating alert permission status for all players");
        }
        
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.getAlertManager().updatePlayerAlertStatus(player);
        }
    }
}