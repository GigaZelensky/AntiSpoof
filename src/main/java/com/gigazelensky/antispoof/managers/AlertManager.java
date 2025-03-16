package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AlertManager {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Track alert cooldowns by player UUID and alert type
    private final Map<UUID, Map<String, Long>> lastAlerts = new ConcurrentHashMap<>();
    
    // Alert cooldown in milliseconds (3 seconds by default)
    private static final long ALERT_COOLDOWN = 3000;
    
    public AlertManager(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Checks if an alert can be sent for a player
     * @param playerUUID The UUID of the player
     * @param alertType The type of alert
     * @return True if an alert can be sent, false otherwise
     */
    public boolean canSendAlert(UUID playerUUID, String alertType) {
        Map<String, Long> playerAlerts = lastAlerts.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>());
        
        long now = System.currentTimeMillis();
        Long lastAlert = playerAlerts.get(alertType);
        
        // Allow alert if no previous alert or if cooldown passed
        if (lastAlert == null || now - lastAlert > ALERT_COOLDOWN) {
            playerAlerts.put(alertType, now);
            return true;
        }
        
        return false;
    }
    
    /**
     * Sends a brand join alert for a player
     * @param player The player who joined
     * @param brand The player's client brand
     */
    public void sendBrandJoinAlert(Player player, String brand) {
        if (!config.isJoinBrandAlertsEnabled() || !canSendAlert(player.getUniqueId(), "JOIN_BRAND")) {
            return;
        }
        
        // Format the player alert message with placeholders
        String playerAlert = config.getBlockedBrandsAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown");
        
        // Format the console alert message with placeholders
        String consoleAlert = config.getBlockedBrandsConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown");
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if brand join alerts are enabled
        if (config.isDiscordWebhookEnabled() && 
            config.isBlockedBrandsDiscordAlertEnabled() && 
            config.isJoinBrandAlertsEnabled()) {
            
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                "Joined with client brand: " + brand,
                brand,
                null,
                null
            );
        }
    }
    
    /**
     * Sends a modified channel alert for a player
     * @param player The player who modified a channel
     * @param channel The modified channel
     */
    public void sendModifiedChannelAlert(Player player, String channel) {
        if (!config.isModifiedChannelsEnabled() || !canSendAlert(player.getUniqueId(), "MODIFIED_CHANNEL")) {
            return;
        }
        
        // Format the player alert message
        String alertMessage = config.getModifiedChannelsAlertMessage()
                .replace("%player%", player.getName())
                .replace("%channel%", channel);
        
        // Format the console alert message
        String consoleAlertMessage = config.getModifiedChannelsConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%channel%", channel);
        
        // Log to console
        plugin.getLogger().info(consoleAlertMessage);
        
        // Notify players with permission
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', alertMessage);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord webhook if enabled
        if (config.isModifiedChannelsDiscordEnabled()) {
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                "Modified channel: " + channel,
                plugin.getClientBrand(player), 
                channel, 
                null
            );
        }
    }
    
    /**
     * Sends a multiple violations alert for a player
     * @param player The player with violations
     * @param violations The list of violation reasons
     * @param brand The player's client brand
     */
    public void sendMultipleViolationsAlert(Player player, List<String> violations, String brand) {
        if (!canSendAlert(player.getUniqueId(), "MULTIPLE_VIOLATIONS")) {
            return;
        }
        
        // Join all reasons with commas
        String reasonsList = String.join(", ", violations);
        
        // Format the player alert message for multiple violations
        String playerAlert = config.getMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reasons%", reasonsList);
        
        // Format the console alert message for multiple violations
        String consoleAlert = config.getConsoleMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reasons%", reasonsList);
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if enabled
        plugin.getDiscordWebhookHandler().sendAlert(player, "Multiple Violations", brand, null, violations);
    }
    
    /**
     * Sends a violation alert for a player
     * @param player The player with the violation
     * @param reason The violation reason
     * @param brand The player's client brand
     * @param violatedChannel The violated channel (if applicable)
     * @param violationType The type of violation
     */
    public void sendViolationAlert(Player player, String reason, String brand, 
                                  String violatedChannel, String violationType) {
        if (!canSendAlert(player.getUniqueId(), violationType)) {
            return;
        }
        
        // Select the appropriate alert message based on violation type
        String alertTemplate;
        String consoleAlertTemplate;
        boolean sendDiscordAlert = false;
        
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                alertTemplate = config.getVanillaCheckAlertMessage();
                consoleAlertTemplate = config.getVanillaCheckConsoleAlertMessage();
                sendDiscordAlert = config.isVanillaCheckDiscordAlertEnabled();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                alertTemplate = config.getNonVanillaCheckAlertMessage();
                consoleAlertTemplate = config.getNonVanillaCheckConsoleAlertMessage();
                sendDiscordAlert = config.isNonVanillaCheckDiscordAlertEnabled();
                break;
                
            case "BLOCKED_CHANNEL":
                alertTemplate = config.getBlockedChannelsAlertMessage();
                consoleAlertTemplate = config.getBlockedChannelsConsoleAlertMessage();
                sendDiscordAlert = config.isBlockedChannelsDiscordAlertEnabled();
                break;
                
            case "CHANNEL_WHITELIST":
                // Use whitelist-specific messages
                alertTemplate = config.getChannelWhitelistAlertMessage();
                consoleAlertTemplate = config.getChannelWhitelistConsoleAlertMessage();
                sendDiscordAlert = config.isBlockedChannelsDiscordAlertEnabled();
                break;
                
            case "BLOCKED_BRAND":
                alertTemplate = config.getBlockedBrandsAlertMessage();
                consoleAlertTemplate = config.getBlockedBrandsConsoleAlertMessage();
                sendDiscordAlert = config.isBlockedBrandsDiscordAlertEnabled();
                break;
                
            case "GEYSER_SPOOF":
                alertTemplate = config.getGeyserSpoofAlertMessage();
                consoleAlertTemplate = config.getGeyserSpoofConsoleAlertMessage();
                sendDiscordAlert = config.isGeyserSpoofDiscordAlertEnabled();
                break;
                
            default:
                // Fallback to global messages
                alertTemplate = config.getAlertMessage();
                consoleAlertTemplate = config.getConsoleAlertMessage();
                sendDiscordAlert = true; // Default to true for unknown types
        }
        
        // Always include brand information in the reason if not already present
        if (brand != null && !reason.contains(brand)) {
            reason += " (Client: " + brand + ")";
        }
        
        // Format the player alert message with placeholders
        String playerAlert = alertTemplate
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reason%", reason);
        
        // Format the console alert message with placeholders
        String consoleAlert = consoleAlertTemplate
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reason%", reason);
        
        if (violatedChannel != null && violationType.equals("BLOCKED_CHANNEL")) {
            playerAlert = playerAlert.replace("%channel%", violatedChannel);
            consoleAlert = consoleAlert.replace("%channel%", violatedChannel);
        }
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if enabled and this type should send alerts
        if (config.isDiscordWebhookEnabled() && sendDiscordAlert) {
            List<String> singleViolation = new ArrayList<>();
            singleViolation.add(reason);
            plugin.getDiscordWebhookHandler().sendAlert(player, reason, brand, violatedChannel, singleViolation);
        }
    }
    
    /**
     * Executes punishment for a player
     * @param player The player to punish
     * @param reason The reason for punishment
     * @param brand The player's client brand
     * @param violationType The type of violation
     * @param violatedChannel The violated channel (if applicable)
     */
    public void executePunishment(Player player, String reason, String brand, 
                                 String violationType, String violatedChannel) {
        List<String> punishments;
        
        // Select the appropriate punishments based on violation type
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                punishments = config.getVanillaCheckPunishments();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                punishments = config.getNonVanillaCheckPunishments();
                break;
                
            case "BLOCKED_CHANNEL":
            case "CHANNEL_WHITELIST":
                punishments = config.getBlockedChannelsPunishments();
                break;
                
            case "BLOCKED_BRAND":
                punishments = config.getBlockedBrandsPunishments();
                break;
                
            case "GEYSER_SPOOF":
                punishments = config.getGeyserSpoofPunishments();
                break;
                
            default:
                // Fallback to global punishments
                punishments = config.getPunishments();
        }
        
        // If no specific punishments defined, fall back to global
        if (punishments.isEmpty()) {
            punishments = config.getPunishments();
        }
        
        // Execute the punishments
        for (String command : punishments) {
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", reason)
                                     .replace("%brand%", brand != null ? brand : "unknown");
            
            if (violatedChannel != null && violationType.equals("BLOCKED_CHANNEL")) {
                formatted = formatted.replace("%channel%", violatedChannel);
            }
            
            // Execute command on the main thread
            final String finalCommand = formatted;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            });
        }
    }
    
    /**
     * Cleans up alert data when a player disconnects
     * @param playerUUID The UUID of the player who disconnected
     */
    public void handlePlayerQuit(UUID playerUUID) {
        lastAlerts.remove(playerUUID);
    }
}