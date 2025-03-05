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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordWebhookHandler {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Cooldown map to track the last time a webhook was sent for each player
    private final Map<UUID, Long> lastWebhookTime = new ConcurrentHashMap<>();
    // Map to store the most severe alert for each player during cooldown
    private final Map<UUID, WebhookAlert> pendingAlerts = new ConcurrentHashMap<>();
    
    // Default cooldown period in milliseconds (10 seconds)
    private static final long DEFAULT_COOLDOWN = 10000;
    
    public DiscordWebhookHandler(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Represents an alert to be sent to Discord
     */
    private static class WebhookAlert {
        final Player player;
        final String reason;
        final String brand;
        final String channel;
        final List<String> violations;
        final int severityScore;
        
        public WebhookAlert(Player player, String reason, String brand, String channel, List<String> violations) {
            this.player = player;
            this.reason = reason;
            this.brand = brand;
            this.channel = channel;
            this.violations = violations;
            
            // Calculate a severity score based on the alert details
            // More violations = higher severity
            this.severityScore = calculateSeverity();
        }
        
        private int calculateSeverity() {
            int score = 0;
            
            // Multiple violations have higher severity
            if (violations != null) {
                score += violations.size() * 10;
            }
            
            // Specific violation types have different weights
            if (reason != null) {
                if (reason.contains("Geyser")) {
                    score += 50;  // Geyser spoofing is high severity
                }
                if (reason.contains("Vanilla")) {
                    score += 30;  // Vanilla spoofing is medium-high severity
                }
                if (reason.contains("Blocked channel")) {
                    score += 20;  // Blocked channels are medium severity
                }
                if (reason.contains("Blocked client brand")) {
                    score += 15;  // Blocked brands are medium-low severity
                }
                if (reason.contains("joined using client brand")) {
                    score += 5;   // Just joining with a monitored brand is low severity
                }
            }
            
            return score;
        }
        
        /**
         * Compares this alert with another to determine which is more severe
         * @param other The other alert to compare with
         * @return true if this alert is more severe
         */
        public boolean isMoreSevereThan(WebhookAlert other) {
            if (other == null) return true;
            return this.severityScore > other.severityScore;
        }
    }
    
    /**
     * Sends an alert to Discord webhook with cooldown handling
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
        long now = System.currentTimeMillis();
        long cooldownPeriod = getCooldownPeriod();
        
        // Create current alert object
        WebhookAlert currentAlert = new WebhookAlert(player, reason, brand, channel, violations);
        
        // Check if player is in cooldown
        Long lastTime = lastWebhookTime.get(playerUuid);
        if (lastTime != null && now - lastTime < cooldownPeriod) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Player " + player.getName() + " is in webhook cooldown. Checking alert priority.");
            }
            
            // Get existing pending alert if any
            WebhookAlert pendingAlert = pendingAlerts.get(playerUuid);
            
            // Replace pending alert if current one is more severe
            if (currentAlert.isMoreSevereThan(pendingAlert)) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Storing higher priority alert for " + player.getName() + " with reason: " + reason);
                }
                pendingAlerts.put(playerUuid, currentAlert);
            }
            
            // Wait for cooldown to expire before sending
            return;
        }
        
        // Set the last webhook time and remove any pending alerts
        lastWebhookTime.put(playerUuid, now);
        pendingAlerts.remove(playerUuid);
        
        // Send the alert
        sendWebhookDirectly(currentAlert);
        
        // Schedule a task to send any pending alerts after cooldown
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            WebhookAlert pendingAlert = pendingAlerts.remove(playerUuid);
            if (pendingAlert != null) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Sending pending alert for " + pendingAlert.player.getName() + " after cooldown");
                }
                sendWebhookDirectly(pendingAlert);
                lastWebhookTime.put(playerUuid, System.currentTimeMillis());
            }
        }, cooldownPeriod / 50 + 1); // Convert ms to ticks (20 ticks = 1 second) and add 1 for safety
    }
    
    /**
     * Get the cooldown period in milliseconds
     */
    private long getCooldownPeriod() {
        // Get from config or use default
        int seconds = config.getWebhookCooldown();
        if (seconds <= 0) {
            return DEFAULT_COOLDOWN;
        }
        return seconds * 1000L;
    }
    
    /**
     * Directly sends a webhook without cooldown checks
     */
    private void sendWebhookDirectly(WebhookAlert alert) {
        String webhookUrl = config.getDiscordWebhookUrl();
        
        // Validate webhook URL format
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") && 
            !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
            plugin.getLogger().warning("[Discord] Invalid webhook URL. Must start with https://discord.com/api/webhooks/ or https://discordapp.com/api/webhooks/");
            return;
        }
        
        // Get the appropriate console alert message based on the violation type
        String consoleAlert = determineConsoleAlert(alert.player, alert.reason, alert.brand, alert.channel, alert.violations);
        
        // Execute webhook request asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Sending webhook for player: " + alert.player.getName());
                }
                
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                // Create JSON payload
                String json = createWebhookJson(alert.player, alert.reason, alert.brand, alert.channel, alert.violations, consoleAlert);
                
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
     * Creates the JSON payload for the Discord webhook
     */
    private String createWebhookJson(Player player, String reason, String brand, String channel, List<String> violations, String consoleAlert) {
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
        
        // Remove any trailing newline and close the description
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
