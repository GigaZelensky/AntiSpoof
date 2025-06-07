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
    
    // Map to track players who have already been alerted for spoofing in the current session
    private final Map<UUID, Boolean> alertedPlayers = new ConcurrentHashMap<>();
    
    // Map to track when players registered their initial channels (join time)
    private final Map<UUID, Long> playerRegistrationTimes = new ConcurrentHashMap<>();
    
    // Map to track all pending violations for a player
    private final Map<UUID, List<String>> pendingViolations = new ConcurrentHashMap<>();
    
    // Map to track player brands for alerts
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    
    // Map to track blocked channels for alerts
    private final Map<UUID, String> blockedChannels = new ConcurrentHashMap<>();
    
    // Grace period before channels are considered "modified" after join (in milliseconds)
    private static final long CHANNEL_GRACE_PERIOD = 5000; // 5 seconds
    
    // Cooldown between channel modification alerts to prevent spam (in milliseconds)
    private static final long CHANNEL_MOD_COOLDOWN = 3000; // 3 seconds
    
    // Map to track last time a modification alert was sent for a player
    private final Map<UUID, Long> lastModificationAlertTimes = new ConcurrentHashMap<>();
    
    // Map to track pending modified channels during cooldown
    private final Map<UUID, Set<String>> pendingModifiedChannels = new ConcurrentHashMap<>();
    
    public DiscordWebhookHandler(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Registers a player's initial connection time for channel grace period
     * @param playerUuid The UUID of the player
     */
    public void registerPlayerJoin(UUID playerUuid) {
        playerRegistrationTimes.put(playerUuid, System.currentTimeMillis());
        pendingModifiedChannels.put(playerUuid, new HashSet<>());
        pendingViolations.put(playerUuid, new ArrayList<>());
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Registered join time for player with UUID: " + playerUuid);
        }
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
        
        // Store brand for this player
        if (brand != null) {
            playerBrands.put(playerUuid, brand);
        }
        
        // Get the current set of channels
        Set<String> currentChannels = new HashSet<>(data.getChannels());
        
        // Check if this is a modified channel alert by looking for the text pattern
        boolean isModifiedChannelAlert = reason.contains("modified channel");
        
        // If it's a violation alert (not a modified channel alert)
        if (!isModifiedChannelAlert) {
            // Store any blocked channel for future reference
            if (reason.contains("Using blocked channel:") && channel != null) {
                blockedChannels.put(playerUuid, channel);
            }
            
            // Add this violation to the pending list
            List<String> pendingList = pendingViolations.computeIfAbsent(playerUuid, k -> new ArrayList<>());
            if (!pendingList.contains(reason)) {
                pendingList.add(reason);
            }
            
            // Skip if player has already been alerted for spoofing in this session
            if (alertedPlayers.getOrDefault(playerUuid, false)) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Player " + player.getName() + " already alerted for spoofing in this session, collecting additional violations");
                }
                
                // Even though we're not sending a new alert, we should still check for modified channels
                // if the feature is enabled and we already have channel data
                if (config.isModifiedChannelsEnabled() && lastAlertChannels.containsKey(playerUuid)) {
                    checkForModifiedChannels(player, currentChannels);
                }
                
                return;
            }
            
            // Mark player as alerted for this session
            alertedPlayers.put(playerUuid, true);
            
            // Store the current channels for future comparison
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
            
            // Get the configured delay before sending discord alerts (in seconds)
            int delaySeconds = config.getCheckDelay();
            
            // If there's a delay configured, schedule the webhook after the delay
            if (delaySeconds > 0) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Scheduling alert for player: " + player.getName() + 
                                           " after " + delaySeconds + " seconds");
                }
                
                // Schedule the webhook after the configured delay
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    // Re-fetch the player data to get the most up-to-date channels
                    PlayerData updatedData = plugin.getPlayerDataMap().get(playerUuid);
                    if (updatedData != null && player.isOnline()) {
                        Set<String> updatedChannels = updatedData.getChannels();
                        lastAlertChannels.put(playerUuid, new HashSet<>(updatedChannels));
                        
                        // Get all collected violations
                        List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());
                        String playerBrand = playerBrands.getOrDefault(playerUuid, brand);
                        String blockedChannel = blockedChannels.get(playerUuid);
                        
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Discord] Sending delayed spoofing alert for player: " + 
                                                   player.getName() + " with " + updatedChannels.size() + 
                                                   " channels and " + allViolations.size() + " violations");
                        }
                        
                        // Mark the player's initial registration time
                        playerRegistrationTimes.put(playerUuid, System.currentTimeMillis());
                        
                        // Send the full webhook with updated channel information and all violations
                        sendFullWebhook(player, reason, playerBrand, blockedChannel, allViolations);
                    }
                }, delaySeconds * 20L); // Convert seconds to ticks (20 ticks = 1 second)
            } else {
                // No delay, send immediate webhook
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Sending immediate spoofing alert for player: " + 
                                           player.getName());
                }
                
                // Mark the player's initial registration time
                playerRegistrationTimes.put(playerUuid, System.currentTimeMillis());
                
                // Get all collected violations
                List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());
                
                // Send the full webhook with all violations
                sendFullWebhook(player, reason, brand, channel, allViolations.isEmpty() ? violations : allViolations);
            }
        }
        // It's a modified channel alert
        else {
            // Only process if modified channel discord alerts are enabled
            if (config.isModifiedChannelsEnabled() && config.isModifiedChannelsDiscordEnabled()) {
                // Check if we're still in the grace period after initial channel registration
                boolean isInGracePeriod = isInChannelGracePeriod(playerUuid);
                
                if (isInGracePeriod) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Skipping modified channel alert during grace period for: " + 
                                               player.getName() + ", channel: " + channel);
                    }
                    
                    // Just update the last alert channels silently during grace period
                    lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
                    return;
                }
                
                // Check if we're in cooldown and should batch alerts
                long now = System.currentTimeMillis();
                Long lastModTime = lastModificationAlertTimes.get(playerUuid);
                
                if (lastModTime != null && now - lastModTime < CHANNEL_MOD_COOLDOWN) {
                    // We're in cooldown - add this channel to pending set
                    Set<String> pending = pendingModifiedChannels.computeIfAbsent(playerUuid, k -> new HashSet<>());
                    pending.add(channel);
                    
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Added channel to pending for: " + player.getName() + 
                                              ", channel: " + channel + ", total pending: " + pending.size());
                    }
                } else {
                    // Get any pending channels and add this one
                    Set<String> pending = pendingModifiedChannels.getOrDefault(playerUuid, new HashSet<>());
                    pending.add(channel);
                    
                    // Send alert with all channels in the pending set
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Sending modified channel alert for: " + player.getName() + 
                                              ", channels: " + pending.size());
                    }
                    
                    // Send a compact update webhook with all modified channels
                    sendModifiedChannelWebhook(player, pending);
                    
                    // Clear pending and update last alert time
                    pending.clear();
                    lastModificationAlertTimes.put(playerUuid, now);
                }
                
                // Update last alert channels
                lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
            }
        }
    }
    
    /**
     * Checks if a player is still in the channel registration grace period
     * @param playerUuid The player's UUID
     * @return True if still in the grace period, false otherwise
     */
    private boolean isInChannelGracePeriod(UUID playerUuid) {
        Long registrationTime = playerRegistrationTimes.get(playerUuid);
        if (registrationTime == null) return false;
        
        long now = System.currentTimeMillis();
        long timeSinceRegistration = now - registrationTime;
        
        return timeSinceRegistration < CHANNEL_GRACE_PERIOD;
    }
    
    /**
     * Handle player logout - reset their alert status to enable new alerts when they log back in
     * @param uuid The player's UUID
     */
    public void handlePlayerQuit(UUID uuid) {
        // Remove from all trackers to ensure they get alerts on next login
        alertedPlayers.remove(uuid);
        lastAlertChannels.remove(uuid);
        playerRegistrationTimes.remove(uuid);
        lastModificationAlertTimes.remove(uuid);
        pendingModifiedChannels.remove(uuid);
        pendingViolations.remove(uuid);
        playerBrands.remove(uuid);
        blockedChannels.remove(uuid);
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Reset alert status for player with UUID: " + uuid);
        }
    }
    
    /**
     * Checks for modified channels and sends alerts if needed
     */
    private void checkForModifiedChannels(Player player, Set<String> currentChannels) {
        UUID playerUuid = player.getUniqueId();
        Set<String> previousChannels = lastAlertChannels.get(playerUuid);
        
        // Skip if in grace period
        if (isInChannelGracePeriod(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipping modified channel check during grace period for: " + player.getName());
            }
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
            return;
        }
        
        // Find channels that are in current but not in previous
        Set<String> newChannels = new HashSet<>(currentChannels);
        if (previousChannels != null) {
            newChannels.removeAll(previousChannels);
        }
        
        // If there are no new channels, just update tracking and return
        if (newChannels.isEmpty()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] No new channels to report for: " + player.getName());
            }
            lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
            return;
        }
        
        // If there are new channels, queue them for alerts
        if (config.isModifiedChannelsEnabled()) {
            // Check if we're in cooldown
            long now = System.currentTimeMillis();
            Long lastModTime = lastModificationAlertTimes.get(playerUuid);
            
            // Get or create pending set
            Set<String> pending = pendingModifiedChannels.computeIfAbsent(playerUuid, k -> new HashSet<>());
            pending.addAll(newChannels);
            
            if (lastModTime == null || now - lastModTime >= CHANNEL_MOD_COOLDOWN) {
                // Not in cooldown, send alert now
                if (!pending.isEmpty() && config.isModifiedChannelsDiscordEnabled()) {
                    sendModifiedChannelWebhook(player, pending);
                    lastModificationAlertTimes.put(playerUuid, now);
                    pending.clear();
                }
            } else {
                // In cooldown, channels stay in pending
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Added " + newChannels.size() + " channels to pending for: " + 
                                           player.getName() + ", total pending: " + pending.size());
                }
            }
        }
        
        // Update last alert channels
        lastAlertChannels.put(playerUuid, new HashSet<>(currentChannels));
    }
    
    /**
     * Sends a full webhook with all player information
     */
    private void sendFullWebhook(Player player, String reason, String brand, String channel, List<String> violations) {
        // Get the appropriate console alert message
        String consoleAlert = determineConsoleAlert(player, reason, brand, channel, violations);
        
        // Send the full webhook
        sendWebhookDirectly(player, reason, brand, channel, violations, consoleAlert, false, null);
    }
    
    /**
     * Sends a compact webhook for modified channels
     */
    private void sendModifiedChannelWebhook(Player player, Set<String> modifiedChannels) {
        // Skip if there are no modified channels to report
        if (modifiedChannels == null || modifiedChannels.isEmpty()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Skipped sending empty modified channel webhook for: " + player.getName());
            }
            return;
        }
        
        String reason = "Modified channel" + (modifiedChannels.size() > 1 ? "s" : "");
        
        // Send a compact webhook with non-empty channel data
        sendWebhookDirectly(player, reason, null, null, null, null, true, modifiedChannels);
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
        else if (reason.contains("Blocked client brand:") || reason.contains("Client brand not in whitelist:")) {
            // Use client-brands if enabled
            String message = "%player% using client brand: %brand%";
            if (config.isClientBrandsEnabled()) {
                // Try to get a branded message first
                String brandKey = config.getMatchingClientBrand(brand);
                if (brandKey != null) {
                    ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
                    message = brandConfig.getConsoleAlertMessage();
                } else {
                    // Fall back to default brand config
                    message = config.getBlockedBrandsConsoleAlertMessage();
                }
            } else {
                message = config.getBlockedBrandsConsoleAlertMessage();
            }
            return message.replace("%player%", player.getName())
                          .replace("%brand%", brand != null ? brand : "unknown");
        }
        else if (reason.contains("Spoofing Geyser client")) {
            return config.getGeyserSpoofConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", brand != null ? brand : "unknown")
                    .replace("%reason%", reason);
        }
        else if (reason.contains("joined using client brand:")) {
            // For client brand join alerts, use client-brands if available
            String message = "%player% using client brand: %brand%";
            if (config.isClientBrandsEnabled()) {
                // Try to get a branded message first
                String brandKey = config.getMatchingClientBrand(brand);
                if (brandKey != null) {
                    ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
                    message = brandConfig.getConsoleAlertMessage();
                } else {
                    // Fall back to default brand config
                    message = config.getBlockedBrandsConsoleAlertMessage();
                }
            } else {
                message = config.getBlockedBrandsConsoleAlertMessage();
            }
            return message.replace("%player%", player.getName())
                          .replace("%brand%", brand != null ? brand : "unknown");
        }
        else if (reason.contains("Modified channel:")) {
            return config.getModifiedChannelsConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%channel%", channel != null ? channel : "unknown");
        }
        else if (reason.contains("No client brand detected")) {
            return config.getNoBrandConsoleAlertMessage()
                    .replace("%player%", player.getName())
                    .replace("%reason%", reason);
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
     * Directly sends a webhook
     */
    private void sendWebhookDirectly(Player player, String reason, String brand, String channel, 
                                    List<String> violations, String consoleAlert, 
                                    boolean isCompactUpdate, Set<String> modifiedChannels) {
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
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Sending webhook for player: " + player.getName() + 
                                          (isCompactUpdate ? " (modified channel)" : ""));
                }
                
                URL url = java.net.URI.create(webhookUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "AntiSpoof-Plugin");
                connection.setDoOutput(true);
                
                // Create JSON payload based on the webhook type
                String json;
                if (isCompactUpdate) {
                    json = createModifiedChannelJson(player, reason, modifiedChannels);
                } else {
                    json = createFullWebhookJson(player, reason, brand, channel, violations, consoleAlert);
                }
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Discord] Webhook payload length: " + json.length());
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
            // Wrap channel in backticks for Discord
            sb.append("• `").append(escapeJson(channel)).append("`\\n");
        }
        
        // Close the description
        sb.append("\",");
        
        // Timestamp
        sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        
        sb.append("}]}");
        return sb.toString();
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
        
        // ALWAYS use a consistent format with "Violations" header for all alerts
        // Start with player info
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Create a clean list of violations
        List<String> cleanViolations = new ArrayList<>();
        
        // If we have explicit violations list, use it
        if (violations != null && !violations.isEmpty()) {
            cleanViolations = new ArrayList<>(violations);
            cleanViolations.removeIf(v -> v.contains("Multiple Violations"));
        } 
        // Otherwise use the reason as a single violation
        else if (reason != null && !reason.isEmpty()) {
            cleanViolations.add(reason);
        }
        
        // Always show "Violations:" header with bullet points
        sb.append("**Violations**:\\n");
        
        // Add each violation as a bullet point
        for (String violation : cleanViolations) {
            // Clean up any "(Client: X)" text
            int clientIndex = violation.indexOf(" (Client: ");
            String cleanViolation = clientIndex > 0 ? violation.substring(0, clientIndex) : violation;
            
            // Look for patterns like "Client missing required channels for brand X: pattern"
            if (cleanViolation.contains("Client missing required channels for brand")) {
                int patternStartIndex = cleanViolation.lastIndexOf(": ");
                if (patternStartIndex > 0) {
                    // Split into prefix and pattern parts
                    String prefix = cleanViolation.substring(0, patternStartIndex + 2); // Include the ": " part
                    String pattern = cleanViolation.substring(patternStartIndex + 2);
                    
                    // Output with backticks around the pattern
                    sb.append("• ").append(escapeJson(prefix)).append("`").append(escapeJson(pattern)).append("`\\n");
                } else {
                    sb.append("• ").append(escapeJson(cleanViolation)).append("\\n");
                }
            } 
            // Also look for "Using blocked channel: X" pattern
            else if (cleanViolation.contains("Using blocked channel:")) {
                int patternStartIndex = cleanViolation.lastIndexOf(": ");
                if (patternStartIndex > 0) {
                    // Split into prefix and pattern parts
                    String prefix = cleanViolation.substring(0, patternStartIndex + 2); // Include the ": " part
                    String pattern = cleanViolation.substring(patternStartIndex + 2);
                    
                    // Output with backticks around the pattern
                    sb.append("• ").append(escapeJson(prefix)).append("`").append(escapeJson(pattern)).append("`\\n");
                } else {
                    sb.append("• ").append(escapeJson(cleanViolation)).append("\\n");
                }
            }
            // For any other violations that contain regex patterns
            else if (cleanViolation.contains(": (?i)") || cleanViolation.contains(": ^") || cleanViolation.contains(".*")) {
                int patternStartIndex = cleanViolation.lastIndexOf(": ");
                if (patternStartIndex > 0) {
                    // Split into prefix and pattern parts
                    String prefix = cleanViolation.substring(0, patternStartIndex + 2); // Include the ": " part
                    String pattern = cleanViolation.substring(patternStartIndex + 2);
                    
                    // Output with backticks around the pattern
                    sb.append("• ").append(escapeJson(prefix)).append("`").append(escapeJson(pattern)).append("`\\n");
                } else {
                    sb.append("• ").append(escapeJson(cleanViolation)).append("\\n");
                }
            }
            // Default case for other violations
            else {
                sb.append("• ").append(escapeJson(cleanViolation)).append("\\n");
            }
        }
        
        // Add other standard information
        sb.append("**Client Version**: ").append(getClientVersionFromPlaceholders(player)).append("\\n");
        
        // Wrap brand in backticks for Discord
        sb.append("**Brand**: `").append(escapeJson(brand != null ? brand : "unknown")).append("`\\n");
        
        // Add channels
        sb.append("**Channels**:\\n");
        PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
        if (data != null && !data.getChannels().isEmpty()) {
            Set<String> channels = data.getChannels();
            for (String ch : channels) {
                // Wrap channel in backticks for Discord
                sb.append("• `").append(escapeJson(ch)).append("`\\n");
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
     * Helper method to get client version from PlaceholderAPI
     */
    private String getClientVersionFromPlaceholders(Player player) {
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
        return version;
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