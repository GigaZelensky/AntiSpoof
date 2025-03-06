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

public class DiscordWebhookHandler {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Map to track the channels a player has at the time of their last alert
    private final Map<UUID, Set<String>> lastAlertChannels = new ConcurrentHashMap<>();
    
    // Set to track the types of alerts already sent for each player in the current session
    private final Map<UUID, Set<String>> alertTypesSent = new ConcurrentHashMap<>();
    
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
        PlayerData data = plugin.getPlayerDataMap().get(playerUuid);
        
        // No data, no channels to check
        if (data == null) {
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
        } else if (reason.contains("Invalid brand formatting")) {
            alertType = "BRAND_FORMAT";
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
            
            // Send the full webhook for the alert
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending alert for player: " + player.getName() + ", reason: " + reason);
            }
            
            // Send the full webhook
            sendFullWebhook(player, reason, brand, channel, violations);
        }
        // It's a modified channel alert - only handled by the packet listener in special cases
        else if (config.isModifiedChannelsEnabled() && config.isModifiedChannelsDiscordEnabled()) {
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
        
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Check if we've already sent an initial channels alert
        if (hasAlertBeenSent(playerUuid, "INITIAL_CHANNELS")) {
            return;
        }
        
        // Mark as sent
        markAlertSent(playerUuid, "INITIAL_CHANNELS");
        
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
     * Checks for modified channels and sends alerts if needed
     */
    private void checkForModifiedChannels(Player player, Set<String> currentChannels) {
        UUID playerUuid = player.getUniqueId();
        Set<String> previousChannels = lastAlertChannels.get(playerUuid);
        
        // Find channels that are in current but not in previous
        Set<String> newChannels = new HashSet<>(currentChannels);
        newChannels.removeAll(previousChannels);
        
        // If there are new channels, send alerts
        if (!newChannels.isEmpty() && config.isModifiedChannelsEnabled()) {
            // Only process one channel at a time - usually only one is added at once
            for (String newChannel : newChannels) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Detected modified channel: " + newChannel);
                }
                
                // Send a discord webhook if enabled
                if (config.isModifiedChannelsDiscordEnabled()) {
                    sendModifiedChannelWebhook(player, newChannel);
                }
            }
            
            // Update last alert channels
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
        }
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
        String reason = "Modified channel";
        Set<String> modifiedChannels = new HashSet<>();
        modifiedChannels.add(modifiedChannel);
        
        // Build the webhook JSON
        String json = createModifiedChannelJson(player, reason, modifiedChannels);
        
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
    private String createModifiedChannelJson(Player player, String reason, Set<String> modifiedChannels) {
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
        
        // Description - Just player name and modified channels
        sb.append("\"description\":\"");
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Use specific message for modified channels
        sb.append("**Modified channel(s)**:\\n");
        for (String channel : modifiedChannels) {
            sb.append("• ").append(escapeJson(channel)).append("\\n");
        }
        
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
        else if (reason.contains("Invalid brand formatting")) {
            return config.getBrandFormattingConsoleAlertMessage()
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
        List<String> contentLines = config.getDiscordViolationContent();
        
        if (contentLines != null && !contentLines.isEmpty()) {
            for (String line : contentLines) {
                // Handle special placeholders
                String processedLine = line;
                
                // Handle %player% placeholder
                processedLine = processedLine.replace("%player%", player.getName());
                
                // Handle %console_alert% placeholder
                processedLine = processedLine.replace("%console_alert%", consoleAlert);
                
                // Handle %brand% placeholder
                processedLine = processedLine.replace("%brand%", brand != null ? brand : "unknown");
                
                // Handle %viaversion_version% placeholder if ViaVersion is installed
                if (processedLine.contains("%viaversion_version%")) {
                    String version = "Unknown";
                    // Check if ViaVersion and PlaceholderAPI are installed
                    if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion") && 
                        Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                        try {
                            // If so, try to get the version through PlaceholderAPI
                            version = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                                player, "%viaversion_player_protocol_version%");
                        } catch (Exception e) {
                            if (config.isDebugMode()) {
                                plugin.getLogger().warning("[Discord] Error getting ViaVersion: " + e.getMessage());
                            }
                        }
                    }
                    processedLine = processedLine.replace("%viaversion_version%", version);
                }
                
                // Handle %channel% placeholder for listing all channels
                if (line.contains("%channel%")) {
                    PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
                    if (data != null && !data.getChannels().isEmpty()) {
                        Set<String> channels = data.getChannels();
                        for (String ch : channels) {
                            sb.append("• ").append(escapeJson(ch)).append("\\n");
                        }
                    } else {
                        sb.append("• None detected\\n");
                    }
                } else {
                    sb.append(escapeJson(processedLine)).append("\\n");
                }
            }
        } else {
            // Fallback if no content lines configured
            sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
            sb.append("**Console Alert**: ").append(escapeJson(consoleAlert)).append("\\n");
            sb.append("**Brand**: ").append(escapeJson(brand != null ? brand : "unknown")).append("\\n");
            
            if (violations != null && !violations.isEmpty()) {
                sb.append("**Violations**:\\n");
                for (String violation : violations) {
                    sb.append("• ").append(escapeJson(violation)).append("\\n");
                }
            } else {
                sb.append("**Reason**: ").append(escapeJson(reason)).append("\\n");
            }
            
            if (channel != null) {
                sb.append("**Channel**: ").append(escapeJson(channel)).append("\\n");
            }
            
            PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
            if (data != null && !data.getChannels().isEmpty()) {
                sb.append("**All Channels**:\\n");
                for (String ch : data.getChannels()) {
                    sb.append("• ").append(escapeJson(ch)).append("\\n");
                }
            }
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