package com.gigazelensky.antispoof.service;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Discord webhook integration
 */
public class DiscordService {
    private final AntiSpoofPlugin plugin;
    
    // Track players who already had alerts sent in current session
    private final Set<UUID> alertedPlayers = ConcurrentHashMap.newKeySet();
    
    // Track pending modified channels
    private final Map<UUID, Set<String>> pendingModifiedChannels = new ConcurrentHashMap<>();
    
    // Track last alert time for cooldown
    private final Map<UUID, Long> lastAlertTimes = new ConcurrentHashMap<>();
    
    public DiscordService(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Sends violation alerts to Discord
     */
    public void sendViolationAlerts(Player player, List<Violation> violations) {
        if (!plugin.getConfigManager().isDiscordWebhookEnabled() || 
            violations == null || violations.isEmpty()) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        
        // Check batching
        if (plugin.getConfigManager().shouldBatchDiscordAlerts() && alertedPlayers.contains(uuid)) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Discord] Player " + player.getName() + 
                                    " already alerted, skipping due to batching");
            }
            return;
        }
        
        // Get player session
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) return;
        
        // Mark player as alerted
        alertedPlayers.add(uuid);
        
        // Extract violation reasons
        List<String> reasons = new ArrayList<>();
        String violatedChannel = null;
        
        for (Violation v : violations) {
            reasons.add(v.getReason());
            if (v.getViolatedChannel() != null) {
                violatedChannel = v.getViolatedChannel();
            }
        }
        
        // Get client brand
        String brand = session.getClientBrand();
        
        // Send full webhook with all info
        sendFullWebhook(player, reasons, brand, violatedChannel);
    }
    
    /**
     * Sends a modified channel alert to Discord
     */
    public void sendModifiedChannelAlert(Player player, String channel) {
        if (!plugin.getConfigManager().isDiscordWebhookEnabled() || 
            !plugin.getConfigManager().isModifiedChannelsDiscordEnabled()) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        // Check cooldown
        Long lastTime = lastAlertTimes.get(uuid);
        long cooldown = plugin.getConfigManager().getDiscordBatchingCooldown();
        
        if (lastTime != null && now - lastTime < cooldown) {
            // In cooldown, add to pending
            Set<String> pending = pendingModifiedChannels.computeIfAbsent(uuid, k -> new HashSet<>());
            pending.add(channel);
            return;
        }
        
        // Get pending channels
        Set<String> pending = pendingModifiedChannels.getOrDefault(uuid, new HashSet<>());
        pending.add(channel);
        
        // Send webhook with all channels
        sendModifiedChannelWebhook(player, pending);
        
        // Clear pending and update last time
        pending.clear();
        lastAlertTimes.put(uuid, now);
    }
    
    /**
     * Sends a full webhook with all player information
     */
    private void sendFullWebhook(Player player, List<String> reasons, String brand, String channel) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URI(webhookUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                String json = createWebhookJson(player, reasons, brand, channel);
                
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = connection.getResponseCode();
                if (responseCode != 204) {
                    plugin.getLogger().warning("[Discord] Failed to send webhook, response code: " + responseCode);
                } else if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Discord] Webhook sent successfully for " + player.getName());
                }
                
                connection.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("[Discord] Error sending webhook: " + e.getMessage());
            }
        });
    }
    
    /**
     * Sends a modified channel webhook
     */
    private void sendModifiedChannelWebhook(Player player, Set<String> channels) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty() || channels.isEmpty()) return;
        
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URI(webhookUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                String json = createModifiedChannelJson(player, channels);
                
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = connection.getResponseCode();
                if (responseCode != 204) {
                    plugin.getLogger().warning("[Discord] Failed to send channel webhook, response code: " + responseCode);
                } else if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Discord] Modified channel webhook sent for " + player.getName());
                }
                
                connection.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("[Discord] Error sending channel webhook: " + e.getMessage());
            }
        });
    }
    
    /**
     * Creates JSON for the full webhook
     */
    private String createWebhookJson(Player player, List<String> violations, String brand, String channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        // Add username if configured
        String username = plugin.getConfigManager().getDiscordUsername();
        if (username != null && !username.isEmpty()) {
            sb.append("\"username\":\"").append(escapeJson(username)).append("\",");
        }
        
        // Add avatar if configured
        String avatarUrl = plugin.getConfigManager().getDiscordAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            sb.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
        }
        
        // Add content with mentions if configured
        if (plugin.getConfigManager().shouldMentionEveryone() || !plugin.getConfigManager().getMentionRoleIds().isEmpty()) {
            sb.append("\"content\":\"");
            
            if (plugin.getConfigManager().shouldMentionEveryone()) {
                sb.append("@everyone ");
            }
            
            for (String roleId : plugin.getConfigManager().getMentionRoleIds()) {
                sb.append("<@&").append(roleId).append("> ");
            }
            
            sb.append("\",");
        }
        
        // Add embeds
        sb.append("\"embeds\":[{");
        
        // Title
        String title = plugin.getConfigManager().getDiscordEmbedTitle()
                .replace("%player%", player.getName());
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        
        // Color
        String colorHex = plugin.getConfigManager().getDiscordEmbedColor().replace("#", "");
        try {
            Color color = Color.decode("#" + colorHex);
            int decimal = color.getRGB() & 0xFFFFFF;
            sb.append("\"color\":").append(decimal).append(",");
        } catch (NumberFormatException e) {
            sb.append("\"color\":2831050,"); // Default teal color
        }
        
        // Description
        sb.append("\"description\":\"");
        
        // Player info
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Include UUID if configured
        if (plugin.getConfigManager().shouldIncludePlayerUuid()) {
            sb.append("**UUID**: ").append(escapeJson(player.getUniqueId().toString())).append("\\n");
        }
        
        // Only show violations if configured
        if (plugin.getConfigManager().shouldShowViolations()) {
            sb.append("**Violations**:\\n");
            for (String violation : violations) {
                sb.append("• ").append(escapeJson(violation)).append("\\n");
            }
        }
        
        // Only show version if configured
        if (plugin.getConfigManager().shouldShowVersion()) {
            String version = getClientVersion(player);
            sb.append("**Client Version**: ").append(escapeJson(version)).append("\\n");
        }
        
        // Only show brand if configured
        if (plugin.getConfigManager().shouldShowBrand()) {
            sb.append("**Brand**: `").append(escapeJson(brand != null ? brand : "unknown")).append("`\\n");
        }
        
        // Only show channels if configured
        if (plugin.getConfigManager().shouldShowChannels()) {
            sb.append("**Channels**:\\n");
            PlayerSession session = plugin.getPlayerSession(player.getUniqueId());
            if (session != null && !session.getChannels().isEmpty()) {
                for (String ch : session.getChannels()) {
                    sb.append("• `").append(escapeJson(ch)).append("`\\n");
                }
            } else {
                sb.append("• None detected\\n");
            }
        }
        
        sb.append("\",");
        
        // Include server name if configured
        if (plugin.getConfigManager().shouldIncludeServerName()) {
            sb.append("\"footer\":{\"text\":\"").append(escapeJson(Bukkit.getServer().getName())).append("\"},");
        }
        
        // Include timestamp if configured
        if (plugin.getConfigManager().shouldIncludeTimestamp()) {
            sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        } else {
            // Remove trailing comma if no timestamp
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
        }
        
        sb.append("}]}");
        return sb.toString();
    }
    
    /**
     * Creates JSON for the modified channel webhook
     */
    private String createModifiedChannelJson(Player player, Set<String> channels) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        // Add username if configured
        String username = plugin.getConfigManager().getDiscordUsername();
        if (username != null && !username.isEmpty()) {
            sb.append("\"username\":\"").append(escapeJson(username)).append("\",");
        }
        
        // Add avatar if configured
        String avatarUrl = plugin.getConfigManager().getDiscordAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            sb.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
        }
        
        // Add embeds
        sb.append("\"embeds\":[{");
        
        // Title
        String title = plugin.getConfigManager().getDiscordEmbedTitle()
                .replace("%player%", player.getName());
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        
        // Color
        String colorHex = plugin.getConfigManager().getDiscordEmbedColor().replace("#", "");
        try {
            Color color = Color.decode("#" + colorHex);
            int decimal = color.getRGB() & 0xFFFFFF;
            sb.append("\"color\":").append(decimal).append(",");
        } catch (NumberFormatException e) {
            sb.append("\"color\":2831050,"); // Default teal color
        }
        
        // Description
        sb.append("\"description\":\"");
        
        // Player info
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Include UUID if configured
        if (plugin.getConfigManager().shouldIncludePlayerUuid()) {
            sb.append("**UUID**: ").append(escapeJson(player.getUniqueId().toString())).append("\\n");
        }
        
        // Modified channels
        sb.append("**Modified Channels**:\\n");
        for (String channel : channels) {
            sb.append("• `").append(escapeJson(channel)).append("`\\n");
        }
        
        sb.append("\",");
        
        // Include server name if configured
        if (plugin.getConfigManager().shouldIncludeServerName()) {
            sb.append("\"footer\":{\"text\":\"").append(escapeJson(Bukkit.getServer().getName())).append("\"},");
        }
        
        // Include timestamp if configured
        if (plugin.getConfigManager().shouldIncludeTimestamp()) {
            sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        } else {
            // Remove trailing comma if no timestamp
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
        }
        
        sb.append("}]}");
        return sb.toString();
    }
    
    /**
     * Gets the client version from PlaceholderAPI
     */
    private String getClientVersion(Player player) {
        // If ViaVersion and PlaceholderAPI are available, get the protocol version
        if (plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion") && 
            plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    player, "%viaversion_player_protocol_version%");
            } catch (Exception e) {
                return "Unknown";
            }
        }
        return "Unknown";
    }
    
    /**
     * Escapes special characters in JSON
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    
    /**
     * Handles player quit cleanup
     */
    public void handlePlayerQuit(UUID uuid) {
        if (uuid == null) return;
        
        alertedPlayers.remove(uuid);
        pendingModifiedChannels.remove(uuid);
        lastAlertTimes.remove(uuid);
    }
    
    /**
     * Resets discord alert state for a specific player
     * Useful for testing
     */
    public void resetPlayerAlertState(UUID uuid) {
        if (uuid == null) return;
        
        alertedPlayers.remove(uuid);
    }
}