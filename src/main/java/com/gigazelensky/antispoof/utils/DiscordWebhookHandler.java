package com.gigazelensky.antispoof.utils;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class DiscordWebhookHandler {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    public DiscordWebhookHandler(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
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
        if (!config.isDiscordWebhookEnabled() || config.getDiscordWebhookUrl().isEmpty()) {
            return;
        }
        
        // Execute webhook request asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                String webhookUrl = config.getDiscordWebhookUrl();
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                // Create JSON payload
                String json = createWebhookJson(player, reason, brand, channel, violations);
                
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = connection.getResponseCode();
                if (responseCode != 204) {
                    plugin.getLogger().warning("Failed to send Discord webhook, response code: " + responseCode);
                }
                
                connection.disconnect();
            } catch (IOException e) {
                plugin.getLogger().warning("Error sending Discord webhook: " + e.getMessage());
            }
        });
    }
    
    /**
     * Creates the JSON payload for the Discord webhook
     */
    private String createWebhookJson(Player player, String reason, String brand, String channel, List<String> violations) {
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
        
        // Fields
        sb.append("\"fields\":[");
        
        // Add player info
        sb.append("{\"name\":\"Player\",\"value\":\"").append(escapeJson(player.getName())).append("\",\"inline\":true},");
        
        // Add client brand
        sb.append("{\"name\":\"Client Brand\",\"value\":\"").append(escapeJson(brand != null ? brand : "unknown")).append("\",\"inline\":true},");
        
        // Add reason(s)
        if (violations != null && !violations.isEmpty()) {
            sb.append("{\"name\":\"Violations\",\"value\":\"");
            for (String violation : violations) {
                sb.append("• ").append(escapeJson(violation)).append("\\n");
            }
            sb.append("\",\"inline\":false},");
        } else {
            sb.append("{\"name\":\"Reason\",\"value\":\"").append(escapeJson(reason)).append("\",\"inline\":false},");
        }
        
        // Add channel info if available
        if (channel != null) {
            sb.append("{\"name\":\"Channel\",\"value\":\"").append(escapeJson(channel)).append("\",\"inline\":true},");
        }
        
        // Add all channels if available
        PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
        if (data != null && !data.getChannels().isEmpty()) {
            Set<String> channels = data.getChannels();
            StringBuilder channelsStr = new StringBuilder();
            for (String ch : channels) {
                channelsStr.append("• ").append(ch).append("\\n");
            }
            sb.append("{\"name\":\"All Channels\",\"value\":\"").append(escapeJson(channelsStr.toString())).append("\",\"inline\":false},");
        }
        
        // Remove trailing comma if present
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        
        sb.append("],");
        
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
