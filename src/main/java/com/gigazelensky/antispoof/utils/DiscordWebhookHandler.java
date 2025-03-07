package com.gigazelensky.antispoof.utils;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DiscordWebhookHandler {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Map to track the channels a player has at the time of their last alert
    private final Map<UUID, Set<String>> lastAlertChannels = new ConcurrentHashMap<>();
    
    // Set to track the types of alerts already sent for each player in the current session
    private final Map<UUID, Set<String>> alertTypesSent = new ConcurrentHashMap<>();
    
    // Track when the last alert was sent for each player
    private final Map<UUID, Long> lastAlertTime = new ConcurrentHashMap<>();
    
    // Cooldown time in milliseconds
    private static final long ALERT_COOLDOWN = 5000; // 5 seconds
    
    public DiscordWebhookHandler(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Clears the alert status for a player
     * @param playerUUID The UUID of the player to clear
     */
    public void clearPlayerAlertStatus(UUID playerUUID) {
        // Clear alert tracking
        alertTypesSent.remove(playerUUID);
        lastAlertChannels.remove(playerUUID);
        lastAlertTime.remove(playerUUID);
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Discord] Cleared alert status for player UUID: " + playerUUID);
        }
    }
    
    /**
     * Check if an alert of a specific type has already been sent for this player
     * @param playerUUID Player UUID
     * @param alertType The type of alert
     * @return True if this alert type has already been sent
     */
    public boolean hasAlertBeenSent(UUID playerUUID, String alertType) {
        Set<String> sentTypes = alertTypesSent.getOrDefault(playerUUID, new HashSet<>());
        return sentTypes.contains(alertType);
    }
    
    /**
     * Mark an alert type as sent for this player
     * @param playerUUID Player UUID
     * @param alertType The type of alert
     */
    public void markAlertSent(UUID playerUUID, String alertType) {
        Set<String> sentTypes = alertTypesSent.computeIfAbsent(playerUUID, k -> new HashSet<>());
        sentTypes.add(alertType);
    }
    
    /**
     * Check if any violation alerts have been sent for this player
     * @param playerUUID Player UUID
     * @return True if any violation alerts have been sent
     */
    public boolean hasAnyViolationAlerts(UUID playerUUID) {
        Set<String> sentTypes = alertTypesSent.getOrDefault(playerUUID, new HashSet<>());
        
        // Check for common violation alert types
        for (String alertType : sentTypes) {
            if (alertType.startsWith("BLOCKED_CHANNEL:") ||
                alertType.equals("CHANNEL_WHITELIST") ||
                alertType.equals("VANILLA_WITH_CHANNELS") ||
                alertType.equals("NON_VANILLA_WITH_CHANNELS") ||
                alertType.equals("BLOCKED_BRAND") ||
                alertType.equals("GEYSER_SPOOF")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if we should send an alert for this player based on cooldown
     * @param playerUUID Player UUID
     * @return True if we should send an alert
     */
    private boolean isOnCooldown(UUID playerUUID) {
        long now = System.currentTimeMillis();
        Long lastAlert = lastAlertTime.get(playerUUID);
        
        if (lastAlert == null) {
            return false;
        }
        
        return (now - lastAlert) < ALERT_COOLDOWN;
    }
    
    /**
     * Check if Discord alerts are enabled for the given violation type
     * @param violationType The type of violation
     * @return True if Discord alerts are enabled for this violation type
     */
    private boolean isDiscordAlertEnabledForViolation(String violationType, String reason) {
        if (!config.isDiscordWebhookEnabled()) {
            return false;
        }
        
        if (violationType.equals("VANILLA_WITH_CHANNELS") || reason.contains("Vanilla client with plugin channels")) {
            return config.isVanillaCheckDiscordEnabled();
        }
        else if (violationType.equals("NON_VANILLA_WITH_CHANNELS") || reason.contains("Non-vanilla client with channels")) {
            return config.isNonVanillaCheckDiscordEnabled();
        }
        else if (violationType.startsWith("BLOCKED_CHANNEL:") || reason.contains("Blocked channel:") || 
                 violationType.equals("CHANNEL_WHITELIST") || reason.contains("Client channels don't match whitelist")) {
            return config.isBlockedChannelsDiscordEnabled();
        }
        else if (violationType.equals("BLOCKED_BRAND") || reason.contains("Blocked client brand:")) {
            return config.isBlockedBrandsDiscordEnabled();
        }
        else if (violationType.equals("GEYSER_SPOOF") || reason.contains("Spoofing Geyser client")) {
            return config.isGeyserSpoofDiscordEnabled();
        }
        else if (violationType.startsWith("MODIFIED_CHANNEL:") || reason.contains("Modified channel:")) {
            return config.isModifiedChannelsDiscordEnabled();
        }
        else if (violationType.startsWith("CLIENT_BRAND:") || reason.contains("joined using client brand:")) {
            return config.isJoinBrandAlertsEnabled();
        }
        
        // Default to true if we can't determine the type
        return true;
    }
    
    /**
     * Sends an alert to Discord webhook
     * @param player The player who triggered the alert
     * @param reason The reason for the alert
     * @param brand The client brand
     * @param channel The channel that triggered the alert (can be null)
     * @param violations List of all violations (for multiple flags)
     */
    public void sendAlert(Player player, String reason, String brand, String channel, List<String> violations) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }
        
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        
        // Check if we're on cooldown
        if (isOnCooldown(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipping alert for " + player.getName() + " due to cooldown");
            }
            return;
        }
        
        // Determine alert type
        String alertType;
        if (reason.contains("modified channel")) {
            alertType = "MODIFIED_CHANNEL:" + channel;
        } else if (reason.contains("Blocked channel:")) {
            alertType = "BLOCKED_CHANNEL:" + channel;
        } else if (reason.contains("Client channels don't match whitelist")) {
            alertType = "CHANNEL_WHITELIST";
        } else if (reason.contains("Blocked client brand:")) {
            alertType = "BLOCKED_BRAND:" + brand;
        } else if (reason.contains("joined using client brand:")) {
            alertType = "CLIENT_BRAND:" + brand;
        } else if (reason.contains("Vanilla client with plugin channels")) {
            alertType = "VANILLA_WITH_CHANNELS";
        } else if (reason.contains("Non-vanilla client with channels")) {
            alertType = "NON_VANILLA_WITH_CHANNELS";
        } else if (reason.contains("Spoofing Geyser client")) {
            alertType = "GEYSER_SPOOF";
        } else {
            alertType = "UNKNOWN:" + reason;
        }
        
        // Check if this alert type has already been sent for this player
        if (hasAlertBeenSent(playerUuid, alertType)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipping duplicate alert of type " + alertType + " for player " + player.getName());
            }
            return;
        }
        
        // Check if Discord alerts are enabled for this violation type
        if (!isDiscordAlertEnabledForViolation(alertType, reason)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipping alert for " + player.getName() + " - Discord alerts disabled for " + alertType);
            }
            return;
        }
        
        // Update last alert time
        lastAlertTime.put(playerUuid, System.currentTimeMillis());
        
        PlayerData data = plugin.getPlayerDataMap().get(playerUuid);
        if (data == null) {
            return;
        }
        
        // Mark this alert type as sent
        markAlertSent(playerUuid, alertType);
        
        // Save current channels for comparison
        Set<String> currentChannels = new HashSet<>(data.getChannels());
        
        // Check if this is a modified channel alert
        boolean isModifiedChannelAlert = reason.contains("modified channel");
        
        // If it's a brand alert or violation alert
        if (!isModifiedChannelAlert) {
            // Store the current channels for future comparison
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
            
            // If the player has previously had a violation, don't send a brand alert
            if (alertType.equals("CLIENT_BRAND:" + brand) && hasAnyViolationAlerts(playerUuid)) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Skipping brand alert because player has violation alerts");
                }
                return;
            }
            
            // Send the full webhook for the alert
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending alert for player: " + player.getName() + ", reason: " + reason);
            }
            
            // Send the full webhook
            sendFullWebhook(player, reason, brand, channel, violations);
        }
        // It's a modified channel alert
        else if (config.isModifiedChannelsEnabled()) {
            // If the player already has violation alerts, skip the modified channel alert
            if (hasAnyViolationAlerts(playerUuid)) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Skipping modified channel alert because player has violation alerts");
                }
                return;
            }
            
            // Send a compact update webhook for the modified channel
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending modified channel alert for: " + channel);
            }
            
            sendModifiedChannelWebhook(player, channel);
            
            // Update last alert channels
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
        }
    }
    
    /**
     * Send an initial channel registration webhook with all channels
     * @param player The player
     * @param channels The set of channels
     */
    public void sendInitialChannelsWebhook(Player player, Set<String> channels) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }
        
        // Check if initial channels alerts are enabled
        if (!config.isInitialChannelsAlertsEnabled()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipping initial channels alert - feature disabled in config");
            }
            return;
        }
        
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Check if we're on cooldown
        if (isOnCooldown(playerUuid)) {
            return;
        }
        
        // Check if we've already sent an initial channels alert
        if (hasAlertBeenSent(playerUuid, "INITIAL_CHANNELS")) {
            return;
        }
        
        // Don't send initial channels if we already have any violation alerts
        if (hasAnyViolationAlerts(playerUuid)) {
            return;
        }
        
        // Mark as sent
        markAlertSent(playerUuid, "INITIAL_CHANNELS");
        
        // Update last alert time
        lastAlertTime.put(playerUuid, System.currentTimeMillis());
        
        // Store these channels
        lastAlertChannels.put(playerUuid, new HashSet<>(channels));
        
        // Send webhook if there are channels
        if (!channels.isEmpty()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending initial channels webhook for player: " + player.getName());
            }
            
            sendInitialChannelsWebhook(player, "Initial Channels Registered", channels);
        }
    }
    
    /**
     * Sends a webhook with initial channel registration
     */
    private void sendInitialChannelsWebhook(Player player, String reason, Set<String> channels) {
        String brand = plugin.getClientBrand(player);
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        
        // Title
        String title = config.getDiscordEmbedTitle()
                .replace("%player%", player.getName())
                .replace("%reason%", reason);
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        
        // Color (convert hex to decimal)
        String colorHex = config.getDiscordEmbedColor().replace("#", "");
        try {
            Color color = Color.decode("#" + colorHex);
            int decimal = color.getRGB() & 0xFFFFFF;
            sb.append("\"color\":").append(decimal).append(",");
        } catch (NumberFormatException e) {
            sb.append("\"color\":2831050,"); // Default teal color
        }
        
        // Description - Just player name and channel list
        sb.append("\"description\":\"");
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        sb.append("**Brand**: ").append(escapeJson(brand != null ? brand : "unknown")).append("\\n");
        
        sb.append("**Registered Channels**:\\n");
        for (String channel : channels) {
            sb.append("• ").append(escapeJson(channel)).append("\\n");
        }
        
        // Close the description
        sb.append("\",");
        
        // Timestamp
        sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        
        sb.append("}]}");
        
        // Send the webhook
        sendWebhookPayload(sb.toString());
    }
    
    /**
     * Sends a full webhook with all player information
     */
    private void sendFullWebhook(Player player, String reason, String brand, String channel, List<String> violations) {
        // Get the appropriate console alert message
        String consoleAlert = determineConsoleAlert(player, reason, brand, channel, violations);
        
        // Build the webhook JSON
        String json = createFullWebhookJson(player, reason, brand, channel, violations, consoleAlert);
        
        // Send the webhook
        sendWebhookPayload(json);
    }
    
    /**
     * Sends a compact webhook for a modified channel
     */
    private void sendModifiedChannelWebhook(Player player, String modifiedChannel) {
        // Build the webhook JSON
        String json = createModifiedChannelJson(player, modifiedChannel);
        
        // Send the webhook
        sendWebhookPayload(json);
    }
    
    /**
     * Send a webhook payload
     */
    private void sendWebhookPayload(String json) {
        String webhookUrl = config.getDiscordWebhookUrl();
        
        // Validate webhook URL format
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") && 
            !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
            plugin.getLogger().warning("[Discord] Invalid webhook URL. Must start with https://discord.com/api/webhooks/ or https://discordapp.com/api/webhooks/");
            return;
        }
        
        // Execute webhook request asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Webhook payload: " + json);
                }
                
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = connection.getResponseCode();
                if (responseCode == 204) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Webhook sent successfully!");
                    }
                } else if (responseCode == 429) {
                    // Rate limited
                    plugin.getLogger().warning("[Discord] Rate limited. Try again later.");
                } else {
                    plugin.getLogger().warning("[Discord] Failed to send webhook, response code: " + responseCode);
                    
                    // Read error response
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder responseBody = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            responseBody.append(responseLine);
                        }
                        plugin.getLogger().warning("[Discord] Error response: " + responseBody);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Discord] Could not read error response: " + e.getMessage());
                    }
                }
                
                connection.disconnect();
            } catch (IOException e) {
                plugin.getLogger().warning("[Discord] Error sending webhook: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Creates a compact JSON payload for modified channels
     */
    private String createModifiedChannelJson(Player player, String modifiedChannel) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        
        // Title - Just a simple title for modified channels
        sb.append("\"title\":\"Channel Modified\",");
        
        // Color - Use a different color for modified channels (light blue)
        sb.append("\"color\":5814783,");
        
        // Description - Just player name and modified channel
        sb.append("\"description\":\"");
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        sb.append("**Modified Channel**: ").append(escapeJson(modifiedChannel));
        
        // Close the description
        sb.append("\",");
        
        // Timestamp
        sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        
        sb.append("}]}");
        return sb.toString();
    }
    
    /**
     * Determines the appropriate console alert message based on the violation type
     */
    private String determineConsoleAlert(Player player, String reason, String brand, String channel, List<String> violations) {
        // For multiple violations, use the multiple flags console message
        if (violations != null && violations.size() > 1) {
            String reasonsList = String.join(", ", violations);
            return config.getConsoleMultipleFlagsMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reasons%", reasonsList);
        }
        
        // For specific violation types, determine the appropriate message
        if (reason.contains("Vanilla client with plugin channels")) {
            return config.getVanillaCheckConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
        } 
        else if (reason.contains("Non-vanilla client with channels")) {
            return config.getNonVanillaCheckConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
        }
        else if (reason.contains("Blocked channel:") || reason.contains("Client channels don't match whitelist")) {
            String alert = config.getBlockedChannelsConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
            
            if (channel != null) {
                alert = alert.replace("%channel%", channel);
            }
            return alert;
        }
        else if (reason.contains("Blocked client brand:")) {
            return config.getBlockedBrandsConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
        }
        else if (reason.contains("Spoofing Geyser client")) {
            return config.getGeyserSpoofConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
        }
        else if (reason.contains("joined using client brand:")) {
            return config.getBlockedBrandsConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown");
        }
        else if (reason.contains("Modified channel:")) {
            return config.getModifiedChannelsConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%channel%", channel != null ? channel : "unknown");
        }
        
        // Default to the general console alert if no specific type is found
        String alert = config.getConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reason%", reason);
        
        if (channel != null) {
            alert = alert.replace("%channel%", channel);
        }
        return alert;
    }
    
    /**
     * Creates the JSON payload for the full Discord webhook
     */
    private String createFullWebhookJson(Player player, String reason, String brand, String channel, 
                                        List<String> violations, String consoleAlert) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        
        // Title
        String title = config.getDiscordEmbedTitle()
                .replace("%player%", player.getName())
                .replace("%reason%", reason);
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        
        // Color (convert hex to decimal)
        String colorHex = config.getDiscordEmbedColor().replace("#", "");
        try {
            Color color = Color.decode("#" + colorHex);
            int decimal = color.getRGB() & 0xFFFFFF;
            sb.append("\"color\":").append(decimal).append(",");
        } catch (NumberFormatException e) {
            sb.append("\"color\":2831050,"); // Default teal color
        }
        
        // Description - Use violation content from config
        sb.append("\"description\":\"");
        
        // Add player info
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Add reason/alert
        sb.append("**Reason**: ").append(escapeJson(consoleAlert)).append("\\n");
        
        // Add brand info
        sb.append("**Brand**: ").append(escapeJson(brand != null ? brand : "unknown")).append("\\n");
        
        // Add ViaVersion if available
        if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion") && 
            Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                String version = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    player, "%viaversion_player_protocol_version%");
                sb.append("**Client Version**: ").append(escapeJson(version)).append("\\n");
            } catch (Exception e) {
                if (config.isDebugMode()) {
                    plugin.getLogger().warning("[Discord] Error getting ViaVersion: " + e.getMessage());
                }
            }
        }
        
        // If we have specific violations, add them
        if (violations != null && !violations.isEmpty()) {
            sb.append("**Violations**:\\n");
            for (String violation : violations) {
                sb.append("• ").append(escapeJson(violation)).append("\\n");
            }
        }
        
        // Add the player's channels
        PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
        sb.append("**Channels**:\\n");
        if (data != null && !data.getChannels().isEmpty()) {
            for (String ch : data.getChannels()) {
                sb.append("• ").append(escapeJson(ch)).append("\\n");
            }
        } else {
            sb.append("• None detected\\n");
        }
        
        // Close the description
        sb.append("\",");
        
        // Timestamp
        sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        
        sb.append("}]}");
        return sb.toString();
    }
    
    /**
     * Escapes JSON special characters
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}