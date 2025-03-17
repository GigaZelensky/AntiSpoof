package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class DetectionManager {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Track which players have been checked recently to prevent duplicate checks
    private final Set<UUID> recentlyCheckedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Track player violation states to prevent duplicate alerts
    private final Map<UUID, Map<String, Boolean>> playerViolations = new ConcurrentHashMap<>();
    
    // Player check cooldown in milliseconds (500ms by default)
    private static final long CHECK_COOLDOWN = 500;
    
    public DetectionManager(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }
    
    /**
     * Adds a channel to a player and triggers a check if needed
     * @param player The player to add the channel to
     * @param channel The channel to add
     * @param triggerCheck Whether to trigger a check after adding the channel
     * @return True if a new channel was added, false otherwise
     */
    public boolean addPlayerChannel(Player player, String channel, boolean triggerCheck) {
        UUID playerUUID = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().computeIfAbsent(playerUUID, uuid -> new PlayerData());
        
        boolean channelAdded = false;
        if (!data.getChannels().contains(channel)) {
            data.addChannel(channel);
            channelAdded = true;
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Channel added for " + player.getName() + ": " + channel);
            }
            
            // If this channel was newly added after the initial join
            if (triggerCheck && data.isInitialChannelsRegistered() && config.isModifiedChannelsEnabled()) {
                plugin.getAlertManager().sendModifiedChannelAlert(player, channel);
            }
        }
        
        // Mark initial channels as registered after a short delay from first join
        if (!data.isInitialChannelsRegistered() && 
            System.currentTimeMillis() - data.getJoinTime() > 5000) {
            data.setInitialChannelsRegistered(true);
        }
        
        // Trigger a check if requested and cooldown passed
        if (triggerCheck && canCheckPlayer(playerUUID)) {
            checkPlayerAsync(player, false);
        }
        
        return channelAdded;
    }
    
    /**
     * Removes a channel from a player
     * @param player The player to remove the channel from
     * @param channel The channel to remove
     */
    public void removePlayerChannel(Player player, String channel) {
        UUID playerUUID = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(playerUUID);
        if (data != null) {
            data.removeChannel(channel);
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Channel removed for " + player.getName() + ": " + channel);
            }
        }
    }
    
    /**
     * Checks if a player can be checked (based on cooldown)
     * @param playerUUID The UUID of the player to check
     * @return True if the player can be checked, false otherwise
     */
    private boolean canCheckPlayer(UUID playerUUID) {
        if (recentlyCheckedPlayers.contains(playerUUID)) {
            return false;
        }
        
        // Add to recently checked and schedule removal
        recentlyCheckedPlayers.add(playerUUID);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, 
            () -> recentlyCheckedPlayers.remove(playerUUID), 
            CHECK_COOLDOWN / 50); // Convert to ticks
        
        return true;
    }
    
    /**
     * Checks a player asynchronously
     * @param player The player to check
     * @param isJoinCheck Whether this is an initial join check
     */
    public void checkPlayerAsync(Player player, boolean isJoinCheck) {
        if (!player.isOnline() || player.hasPermission("antispoof.bypass")) {
            return;
        }
        
        // Run check asynchronously to avoid lag
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            checkPlayer(player, isJoinCheck);
        });
    }
    
    /**
     * Performs a comprehensive check on a player
     * @param player The player to check
     * @param isJoinCheck Whether this is an initial join check
     */
    private void checkPlayer(Player player, boolean isJoinCheck) {
        UUID uuid = player.getUniqueId();
        
        // Skip if player is offline or has been punished
        if (!player.isOnline()) return;
        
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        if (data == null) {
            data = new PlayerData();
            plugin.getPlayerDataMap().put(uuid, data);
        }
        
        if (data.isAlreadyPunished()) return;
        
        // Get player's client brand
        String brand = plugin.getClientBrand(player);
        
        // Check for missing brand first
        if (brand == null && config.isNoBrandCheckEnabled()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] No brand detected for " + player.getName());
            }
            
            // Initialize violations map for this player if not exists
            playerViolations.putIfAbsent(uuid, new ConcurrentHashMap<>());
            Map<String, Boolean> violations = playerViolations.get(uuid);
            
            // Skip if already alerted for this player
            if (!violations.getOrDefault("NO_BRAND", false)) {
                violations.put("NO_BRAND", true);
                
                // Process it as a violation
                Map<String, String> detectedViolations = new HashMap<>();
                detectedViolations.put("NO_BRAND", "No client brand detected");
                
                // Process detected violations on the main thread
                final Map<String, String> finalViolations = new HashMap<>(detectedViolations);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    processViolations(player, finalViolations, "unknown");
                });
            }
            
            return; // Skip other checks if no brand
        }
        
        if (brand == null) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] No brand available for " + player.getName());
            }
            return;
        }
        
        // Check if player is a Bedrock player
        boolean isBedrockPlayer = plugin.isBedrockPlayer(player);
        
        // If player is a Bedrock player and we're set to ignore them, return immediately
        if (isBedrockPlayer && config.getBedrockHandlingMode().equals("IGNORE")) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Ignoring Bedrock player: " + player.getName());
            }
            return;
        }
        
        // Initialize violations map for this player if not exists
        playerViolations.putIfAbsent(uuid, new ConcurrentHashMap<>());
        Map<String, Boolean> violations = playerViolations.get(uuid);
        
        // Always show client brand join message if it's enabled and this is a join check
        if (isJoinCheck && config.isJoinBrandAlertsEnabled() && 
            !violations.getOrDefault("JOIN_BRAND", false)) {
            
            // Only send the join-brand alert once per player session
            violations.put("JOIN_BRAND", true);
            
            // Send join brand alert on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getAlertManager().sendBrandJoinAlert(player, brand);
            });
        }
        
        // Collect all detected violations
        Map<String, String> detectedViolations = new HashMap<>();
        
        // Check for Geyser spoofing
        if (config.isPunishSpoofingGeyser() && isSpoofingGeyser(player, brand)) {
            detectedViolations.put("GEYSER_SPOOF", "Spoofing Geyser client");
        }
        
        boolean hasChannels = data.getChannels() != null && !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Check if client brands system is enabled
        if (config.isClientBrandsEnabled()) {
            // Try to match the brand to a configured client brand
            String matchedBrandKey = config.getMatchingClientBrand(brand);
            
            if (matchedBrandKey != null) {
                // We found a matching brand configuration
                ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(matchedBrandKey);
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Matched brand for " + player.getName() + 
                                           ": " + matchedBrandKey);
                }
                
                // Check if this brand should be flagged
                if (brandConfig.shouldFlag()) {
                    detectedViolations.put("CLIENT_BRAND", 
                        "Using flagged client brand: " + brand + " (" + matchedBrandKey + ")");
                }
                
                // Check for strict-check (vanilla spoof detection)
                if (brandConfig.hasStrictCheck() && hasChannels) {
                    detectedViolations.put("VANILLA_WITH_CHANNELS", 
                        "Client claiming '" + matchedBrandKey + "' detected with plugin channels");
                }
                
                // Check required channels for this brand - IMPROVED MATCHING LOGIC
                if (!brandConfig.getRequiredChannels().isEmpty() && hasChannels) {
                    boolean hasRequiredChannel = false;
                    
                    // Log channels in debug mode to help diagnose issues
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Checking required channels for " + player.getName());
                        plugin.getLogger().info("[Debug] Required patterns for " + matchedBrandKey + ": " + 
                                              String.join(", ", brandConfig.getRequiredChannelStrings()));
                        plugin.getLogger().info("[Debug] Player channels: " + String.join(", ", data.getChannels()));
                    }
                    
                    // For each player channel, check against each required channel pattern
                    for (String channel : data.getChannels()) {
                        for (Pattern pattern : brandConfig.getRequiredChannels()) {
                            try {
                                if (pattern.matcher(channel).matches()) {
                                    hasRequiredChannel = true;
                                    
                                    if (config.isDebugMode()) {
                                        plugin.getLogger().info("[Debug] Found matching channel: " + channel);
                                    }
                                    
                                    break;
                                }
                            } catch (Exception e) {
                                // If regex fails, fallback to simple contains check
                                String patternStr = pattern.toString();
                                if (channel.toLowerCase().contains(patternStr.toLowerCase()
                                    .replace("(?i)", "")
                                    .replace(".*", "")
                                    .replace("^", "")
                                    .replace("$", ""))) {
                                    
                                    hasRequiredChannel = true;
                                    
                                    if (config.isDebugMode()) {
                                        plugin.getLogger().info("[Debug] Found matching channel using fallback: " + channel);
                                    }
                                    
                                    break;
                                }
                            }
                        }
                        
                        if (hasRequiredChannel) break;
                    }
                    
                    if (!hasRequiredChannel) {
                        detectedViolations.put("MISSING_REQUIRED_CHANNELS", 
                            "Client missing required channels for brand: " + matchedBrandKey);
                        
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] No matching channels found for " + player.getName());
                        }
                    }
                }
                
                // Always alert if this brand should alert on join and this is a join check
                if (isJoinCheck && brandConfig.shouldAlert() && 
                    !violations.getOrDefault("BRAND_" + matchedBrandKey.toUpperCase(), false)) {
                    
                    violations.put("BRAND_" + matchedBrandKey.toUpperCase(), true);
                    
                    if (config.isDebugMode() && !brandConfig.shouldFlag()) {
                        plugin.getLogger().info("[Debug] Sending brand alert for " + player.getName() + 
                                              " using " + matchedBrandKey);
                    }
                    
                    // Send alert on main thread if this is just an alert, not a violation
                    if (!brandConfig.shouldFlag()) {
                        final String finalBrand = brand;
                        final String finalMatchedBrandKey = matchedBrandKey;
                        final PlayerData finalData = data;  // Create a final reference to data
                        
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // Only send the alert if not already punished
                            if (!finalData.isAlreadyPunished()) {
                                sendBrandAlert(player, finalBrand, finalMatchedBrandKey);
                            }
                        });
                    }
                }
            } else {
                // No matching brand found - use default brand config
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] No matching brand config for " + player.getName() + 
                                          ": " + brand);
                }
                
                // Check if default config should flag unknown brands
                if (config.getClientBrandConfig(null).shouldFlag()) {
                    detectedViolations.put("UNKNOWN_BRAND", "Using unknown client brand: " + brand);
                }
                
                // Vanilla check still takes precedence
                if (config.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
                    detectedViolations.put("VANILLA_WITH_CHANNELS", "Vanilla client with plugin channels");
                }
                
                // Non-vanilla check if enabled
                else if (config.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
                    detectedViolations.put("NON_VANILLA_WITH_CHANNELS", "Non-vanilla client with channels");
                }
            }
        } else {
            // Client brands system disabled - fall back to old checks
            
            // Vanilla client check - this takes precedence
            if (config.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
                detectedViolations.put("VANILLA_WITH_CHANNELS", "Vanilla client with plugin channels");
            }
            
            // Non-vanilla with channels check
            else if (config.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
                detectedViolations.put("NON_VANILLA_WITH_CHANNELS", "Non-vanilla client with channels");
            }
        }
        
        // Channel whitelist/blacklist check
        if (config.isBlockedChannelsEnabled() && hasChannels) {
            if (config.isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    // Use the proper violation type for whitelist
                    if (config.isChannelWhitelistStrict()) {
                        // Get missing channels for detailed message
                        List<String> missingChannels = findMissingRequiredChannels(data.getChannels());
                        if (!missingChannels.isEmpty()) {
                            detectedViolations.put("CHANNEL_WHITELIST", 
                                "Missing required channels: " + String.join(", ", missingChannels));
                        } else {
                            detectedViolations.put("CHANNEL_WHITELIST", 
                                "Client channels don't match whitelist requirements");
                        }
                    } else {
                        detectedViolations.put("CHANNEL_WHITELIST", "No whitelisted channels detected");
                    }
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    detectedViolations.put("BLOCKED_CHANNEL", "Using blocked channel: " + blockedChannel);
                }
            }
        }
        
        // If player is a Bedrock player and we're in EXEMPT mode, don't process violations
        if (!detectedViolations.isEmpty() && isBedrockPlayer && config.isBedrockExemptMode()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                      " would be processed for violations, but is exempt");
            }
            return;
        }
        
        // Process detected violations on the main thread
        if (!detectedViolations.isEmpty()) {
            final Map<String, String> finalViolations = new HashMap<>(detectedViolations);
            final String finalBrand = brand;  // Make brand effectively final
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                processViolations(player, finalViolations, finalBrand);
            });
        }
    }
    
    /**
     * Sends a client brand alert for a non-violating player
     * @param player The player
     * @param brand The client brand
     * @param brandKey The configuration key for the brand
     */
    private void sendBrandAlert(Player player, String brand, String brandKey) {
        ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
        
        // Skip if alerts are disabled for this brand
        if (!brandConfig.shouldAlert()) return;
        
        // Get alert message for this brand
        String alertMessage = brandConfig.getAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);
        
        // Get console alert message for this brand
        String consoleAlertMessage = brandConfig.getConsoleAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);
        
        // Log to console
        plugin.getLogger().info(consoleAlertMessage);
        
        // Send alert to players with permission
        plugin.getAlertManager().sendCustomAlert(player, alertMessage, consoleAlertMessage, "BRAND_ALERT");
        
        // Send to Discord if enabled for this brand
        if (config.isDiscordWebhookEnabled() && brandConfig.shouldDiscordAlert()) {
            List<String> brandInfo = new ArrayList<>();
            brandInfo.add("Client brand: " + brand);
            
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                "Using client: " + brandKey, 
                brand, 
                null, 
                brandInfo
            );
        }
    }
    
    /**
     * Process detected violations for a player
     * @param player The player
     * @param detectedViolations Map of violation types to reasons
     * @param brand The player's client brand
     */
    public void processViolations(Player player, Map<String, String> detectedViolations, String brand) {
        if (!player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        
        if (data == null || data.isAlreadyPunished() || detectedViolations.isEmpty()) return;
        
        // Get player's violation tracking map
        Map<String, Boolean> violations = playerViolations.get(uuid);
        if (violations == null) {
            violations = new ConcurrentHashMap<>();
            playerViolations.put(uuid, violations);
        }
        
        // Find new violations (not already alerted)
        Map<String, String> newViolations = new HashMap<>();
        for (Map.Entry<String, String> entry : detectedViolations.entrySet()) {
            String violationType = entry.getKey();
            if (!violations.getOrDefault(violationType, false)) {
                newViolations.put(violationType, entry.getValue());
                violations.put(violationType, true); // Mark as alerted
            }
        }
        
        // Skip if no new violations
        if (newViolations.isEmpty()) return;
        
        // Get violated channel for blacklist mode
        String violatedChannel = null;
        if (newViolations.containsKey("BLOCKED_CHANNEL")) {
            violatedChannel = findBlockedChannel(data.getChannels());
        }
        
        // Special handling for client brand violations
        if (newViolations.containsKey("CLIENT_BRAND")) {
            String reason = newViolations.get("CLIENT_BRAND");
            // Extract the brand key from the reason format "Using flagged client brand: X (brandKey)"
            String brandKey = null;
            int startIndex = reason.lastIndexOf("(");
            int endIndex = reason.lastIndexOf(")");
            if (startIndex > 0 && endIndex > startIndex) {
                brandKey = reason.substring(startIndex + 1, endIndex);
            }
            
            if (brandKey != null) {
                ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
                
                // Use the brand-specific alert and punishment settings
                plugin.getAlertManager().sendBrandViolationAlert(
                    player, reason, brand, violatedChannel, "CLIENT_BRAND", brandConfig);
                
                // Execute punishment if needed
                if (brandConfig.shouldPunish()) {
                    plugin.getAlertManager().executeBrandPunishment(
                        player, reason, brand, "CLIENT_BRAND", violatedChannel, brandConfig);
                    data.setAlreadyPunished(true);
                }
                
                // Remove the client brand violation since we've handled it specially
                newViolations.remove("CLIENT_BRAND");
            }
        }
        
        // Special handling for unknown brand violations
        if (newViolations.containsKey("UNKNOWN_BRAND")) {
            String reason = newViolations.get("UNKNOWN_BRAND");
            
            // Use the default brand config for alerts and punishments
            ConfigManager.ClientBrandConfig defaultConfig = config.getClientBrandConfig(null);
            
            // Send alert
            plugin.getAlertManager().sendBrandViolationAlert(
                player, reason, brand, null, "UNKNOWN_BRAND", defaultConfig);
            
            // Execute punishment if needed
            if (defaultConfig.shouldPunish()) {
                plugin.getAlertManager().executeBrandPunishment(
                    player, reason, brand, "UNKNOWN_BRAND", null, defaultConfig);
                data.setAlreadyPunished(true);
            }
            
            // Remove the unknown brand violation since we've handled it specially
            newViolations.remove("UNKNOWN_BRAND");
        }
        
        // Send a separate alert for each remaining violation
        for (Map.Entry<String, String> entry : newViolations.entrySet()) {
            // Only pass the channel parameter for BLOCKED_CHANNEL violations
            String channelParam = entry.getKey().equals("BLOCKED_CHANNEL") ? violatedChannel : null;
            
            plugin.getAlertManager().sendViolationAlert(
                player, entry.getValue(), brand, channelParam, entry.getKey());
        }
        
        // If we still have violations to process, handle punishment
        if (!newViolations.isEmpty() && !data.isAlreadyPunished()) {
            // Execute punishment if needed - use the first violation for punishment
            String primaryViolationType = newViolations.keySet().iterator().next();
            String primaryReason = newViolations.get(primaryViolationType);
            
            boolean shouldPunish = shouldPunishViolation(primaryViolationType, brand);
            
            if (shouldPunish) {
                plugin.getAlertManager().executePunishment(
                    player, primaryReason, brand, primaryViolationType, violatedChannel);
                data.setAlreadyPunished(true);
            }
        }
    }
    
    /**
     * Process a single violation for a player
     * @param player The player
     * @param violationType The type of violation
     * @param reason The reason for the violation
     */
    public void processViolation(Player player, String violationType, String reason) {
        if (!player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        
        if (data == null || data.isAlreadyPunished()) return;
        
        // Get player's violation tracking map
        Map<String, Boolean> violations = playerViolations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        
        // Skip if already alerted for this violation
        if (violations.getOrDefault(violationType, false)) {
            return;
        }
        
        // Mark as alerted
        violations.put(violationType, true);
        
        // Send alert
        plugin.getAlertManager().sendViolationAlert(
            player, reason, "unknown", null, violationType);
        
        // Execute punishment if needed - using "unknown" as brand since we don't know it
        boolean shouldPunish = shouldPunishViolation(violationType, "unknown");
        
        if (shouldPunish) {
            plugin.getAlertManager().executePunishment(
                player, reason, "unknown", violationType, null);
            data.setAlreadyPunished(true);
        }
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Processed violation for " + player.getName() + 
                                  ": " + violationType + " - " + reason);
        }
    }
    
    /**
     * Finds missing required channels for strict whitelist mode
     * @param playerChannels The player's channels
     * @return List of missing required channels
     */
    private List<String> findMissingRequiredChannels(Set<String> playerChannels) {
        List<String> missingChannels = new ArrayList<>();
        List<String> requiredChannels = config.getBlockedChannels();
        
        for (String requiredChannel : requiredChannels) {
            boolean found = false;
            for (String playerChannel : playerChannels) {
                try {
                    if (playerChannel.matches(requiredChannel)) {
                        found = true;
                        break;
                    }
                } catch (Exception e) {
                    // If regex fails, try direct comparison
                    if (playerChannel.equals(requiredChannel)) {
                        found = true;
                        break;
                    }
                }
            }
            
            if (!found) {
                missingChannels.add(requiredChannel);
            }
        }
        
        return missingChannels;
    }
    
    /**
     * Determines if a violation should result in punishment
     * @param violationType The type of violation
     * @param brand The player's client brand
     * @return True if this violation should be punished, false otherwise
     */
    private boolean shouldPunishViolation(String violationType, String brand) {
        switch (violationType) {
            case "VANILLA_WITH_CHANNELS":
                return config.shouldPunishVanillaCheck();
            case "NON_VANILLA_WITH_CHANNELS":
                return config.shouldPunishNonVanillaCheck();
            case "BLOCKED_CHANNEL":
            case "CHANNEL_WHITELIST":
                return config.shouldPunishBlockedChannels();
            case "CLIENT_BRAND":
                return false; // Handled separately for each brand
            case "UNKNOWN_BRAND":
                return config.getClientBrandConfig(null).shouldPunish();
            case "MISSING_REQUIRED_CHANNELS":
                // Check the brand's required-channels-punish setting
                String brandKey = config.getMatchingClientBrand(brand);
                if (brandKey != null) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Checking if should punish missing channels for brand: " + 
                                             brandKey + ", punishment setting: " + 
                                             config.getClientBrandConfig(brandKey).shouldPunishRequiredChannels());
                    }
                    return config.getClientBrandConfig(brandKey).shouldPunishRequiredChannels();
                }
                return false; // Default to not punishing if brand not found or setting not specified
            case "GEYSER_SPOOF":
                return config.shouldPunishGeyserSpoof();
            case "NO_BRAND":
                return config.shouldPunishNoBrand();
            default:
                return false;
        }
    }
    
    /**
     * Checks if a player is spoofing Geyser client
     * @param player The player to check
     * @param brand The player's client brand
     * @return True if the player is spoofing Geyser client, false otherwise
     */
    private boolean isSpoofingGeyser(Player player, String brand) {
        if (brand == null) return false;
        
        // Check if brand contains "geyser" (case insensitive)
        boolean claimsGeyser = brand.toLowerCase().contains("geyser");
        
        // If player claims to be using Geyser but isn't detected as a Bedrock player
        return claimsGeyser && !plugin.isBedrockPlayer(player);
    }
    
    /**
     * Checks if player channels pass the whitelist check
     * @param playerChannels The player's channels
     * @return True if the channels pass the whitelist check, false otherwise
     */
    public boolean checkChannelWhitelist(Set<String> playerChannels) {
        if (playerChannels == null || playerChannels.isEmpty()) {
            // Empty channels always pass whitelist check
            return true;
        }
        
        boolean strictMode = config.isChannelWhitelistStrict();
        List<String> whitelistedChannels = config.getBlockedChannels();
        
        // If no channels are whitelisted, then fail if player has any channels
        if (whitelistedChannels.isEmpty()) {
            return playerChannels.isEmpty();
        }
        
        // SIMPLE mode: Player must have at least one of the whitelisted channels
        if (!strictMode) {
            for (String playerChannel : playerChannels) {
                if (config.matchesChannelPattern(playerChannel)) {
                    return true; // Pass if player has at least one whitelisted channel
                }
            }
            return false; // Fail if player has no whitelisted channels
        } 
        // STRICT mode: Player must have ALL whitelisted channels AND only whitelisted channels
        else {
            // 1. Check if every player channel is whitelisted
            for (String playerChannel : playerChannels) {
                if (!config.matchesChannelPattern(playerChannel)) {
                    return false; // Fail if any player channel is not whitelisted
                }
            }
            
            // 2. Also check if player has ALL whitelisted channels
            for (String whitelistedChannel : whitelistedChannels) {
                boolean playerHasChannel = false;
                
                for (String playerChannel : playerChannels) {
                    try {
                        if (playerChannel.matches(whitelistedChannel)) {
                            playerHasChannel = true;
                            break;
                        }
                    } catch (Exception e) {
                        // If regex fails, try direct match as fallback
                        if (playerChannel.equals(whitelistedChannel)) {
                            playerHasChannel = true;
                            break;
                        }
                    }
                }
                
                if (!playerHasChannel) {
                    return false; // Fail if player is missing any whitelisted channel
                }
            }
            
            // Player has passed both checks
            return true;
        }
    }
    
    /**
     * Finds a blocked channel in a player's channels
     * @param playerChannels The player's channels
     * @return The blocked channel, or null if none are blocked
     */
    public String findBlockedChannel(Set<String> playerChannels) {
        if (playerChannels == null || playerChannels.isEmpty()) {
            return null;
        }
        
        // Only for blacklist mode
        if (config.isChannelWhitelistEnabled()) {
            return null;
        }
        
        for (String playerChannel : playerChannels) {
            if (config.matchesChannelPattern(playerChannel)) {
                return playerChannel;
            }
        }
        
        return null; // No blocked channels found
    }
    
    /**
     * Cleans up player data when they disconnect
     * @param playerUUID The UUID of the player who disconnected
     */
    public void handlePlayerQuit(UUID playerUUID) {
        plugin.getPlayerDataMap().remove(playerUUID);
        plugin.getPlayerBrands().remove(playerUUID);
        playerViolations.remove(playerUUID);
        recentlyCheckedPlayers.remove(playerUUID);
    }
}