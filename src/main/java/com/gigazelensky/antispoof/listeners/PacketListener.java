package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;
    
    // Track which players have been flagged for spoofing to avoid duplicate flags
    private final Set<UUID> flaggedPlayers = new HashSet<>();
    
    // Track players' initial channel registration phase
    private final Map<UUID, Long> initialRegistrationTime = new ConcurrentHashMap<>();
    
    // Map to track which alert types have already been shown to players
    private final Map<UUID, Set<String>> alertTypesSent = new ConcurrentHashMap<>();
    
    // Track last alert time for rate limiting
    private final Map<UUID, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN = 3000; // 3 seconds
    
    // Time window for initial channel registration (in milliseconds)
    private static final long INITIAL_REGISTRATION_WINDOW = 5000; // 5 seconds

    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
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
     * Check if we should send an alert based on rate limiting
     * @param playerUUID The player's UUID
     * @return True if we should send the alert, false if it's on cooldown
     */
    private boolean isOnCooldown(UUID playerUUID) {
        long now = System.currentTimeMillis();
        Long lastAlert = lastAlertTime.get(playerUUID);
        
        if (lastAlert == null) {
            return false;
        }
        
        return (now - lastAlert) < ALERT_COOLDOWN;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Skip if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) return;
        
        // Get or create player data
        PlayerData data = plugin.getPlayerDataMap().computeIfAbsent(
            playerUUID, uuid -> new PlayerData()
        );
        
        boolean wasChannelRegistered = false;
        
        // Handle plugin messages based on the packet type
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            wasChannelRegistered = handlePluginMessage(playerUUID, packet.getChannelName(), packet.getData(), data);
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            wasChannelRegistered = handlePluginMessage(playerUUID, packet.getChannelName(), packet.getData(), data);
        }
        
        // If this packet registered a channel and delay is set to 0, check the player
        if (wasChannelRegistered && plugin.getConfigManager().getCheckDelay() <= 0) {
            // Since we can't call it directly, we'll schedule an immediate task
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Only check if player is still online and not already punished
                if (player.isOnline() && !data.isAlreadyPunished()) {
                    // Check if player is spoofing after channel registration
                    checkAndProcessPlayer(player);
                }
            });
        }
    }
    
    private void checkAndProcessPlayer(Player player) {
        // Only run if player is online and not already punished
        if (!player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        if (data == null || data.isAlreadyPunished()) return;

        // Check if player is a Bedrock player
        boolean isBedrockPlayer = plugin.isBedrockPlayer(player);
        
        // If player is a Bedrock player and we're set to ignore them, return immediately
        if (isBedrockPlayer && plugin.getConfigManager().getBedrockHandlingMode().equals("IGNORE")) {
            return;
        }

        String brand = plugin.getClientBrand(player);
        if (brand == null) return;
        
        // Track all violations and whether to punish
        List<String> allViolations = new ArrayList<>();
        boolean shouldPunish = false;
        boolean anyViolationDetected = false;
        
        // Process each violation separately

        // Handle potential Geyser spoofing
        if (plugin.getConfigManager().isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
            String reason = "Spoofing Geyser client";
            String violationType = "GEYSER_SPOOF";
            allViolations.add(reason);
            anyViolationDetected = true;
            
            if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
            } else {
                if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                    sendAlert(player, reason, brand, null, violationType);
                    markAlertSent(uuid, violationType);
                    lastAlertTime.put(uuid, System.currentTimeMillis());
                }
                if (plugin.getConfigManager().shouldPunishGeyserSpoof()) {
                    shouldPunish = true;
                }
            }
        }

        // Check for brand blocking/whitelist
        if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
            boolean brandBlocked = isBrandBlocked(brand);
            if (brandBlocked) {
                // Only add as a violation if count-as-flag is true
                if (plugin.getConfigManager().shouldCountNonWhitelistedBrandsAsFlag()) {
                    String reason = "Blocked client brand: " + brand;
                    String violationType = "BLOCKED_BRAND";
                    allViolations.add(reason);
                    anyViolationDetected = true;
                    
                    if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                        logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
                    } else {
                        if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                            sendAlert(player, reason, brand, null, violationType);
                            markAlertSent(uuid, violationType);
                            lastAlertTime.put(uuid, System.currentTimeMillis());
                        }
                        if (plugin.getConfigManager().shouldPunishBlockedBrands()) {
                            shouldPunish = true;
                        }
                    }
                }
            }
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Vanilla client check
        if (plugin.getConfigManager().isVanillaCheckEnabled() && 
            claimsVanilla && hasChannels) {
            String reason = "Vanilla client with plugin channels";
            String violationType = "VANILLA_WITH_CHANNELS";
            allViolations.add(reason);
            anyViolationDetected = true;
            
            if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
            } else {
                if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                    sendAlert(player, reason, brand, null, violationType);
                    markAlertSent(uuid, violationType);
                    lastAlertTime.put(uuid, System.currentTimeMillis());
                }
                if (plugin.getConfigManager().shouldPunishVanillaCheck()) {
                    shouldPunish = true;
                }
            }
        }
        // Non-vanilla with channels check
        else if (plugin.getConfigManager().shouldBlockNonVanillaWithChannels() && 
                !claimsVanilla && hasChannels) {
            String reason = "Non-vanilla client with channels";
            String violationType = "NON_VANILLA_WITH_CHANNELS";
            allViolations.add(reason);
            anyViolationDetected = true;
            
            if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
            } else {
                if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                    sendAlert(player, reason, brand, null, violationType);
                    markAlertSent(uuid, violationType);
                    lastAlertTime.put(uuid, System.currentTimeMillis());
                }
                if (plugin.getConfigManager().shouldPunishNonVanillaCheck()) {
                    shouldPunish = true;
                }
            }
        }
        
        // Channel whitelist/blacklist check
        if (plugin.getConfigManager().isBlockedChannelsEnabled()) {
            if (plugin.getConfigManager().isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    String reason = "Client channels don't match whitelist";
                    String violationType = "CHANNEL_WHITELIST";
                    allViolations.add(reason);
                    anyViolationDetected = true;
                    
                    if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                        logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
                    } else {
                        if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                            sendAlert(player, reason, brand, null, violationType);
                            markAlertSent(uuid, violationType);
                            lastAlertTime.put(uuid, System.currentTimeMillis());
                        }
                        if (plugin.getConfigManager().shouldPunishBlockedChannels()) {
                            shouldPunish = true;
                        }
                    }
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    String reason = "Blocked channel: " + blockedChannel;
                    String violationType = "BLOCKED_CHANNEL:" + blockedChannel;
                    allViolations.add(reason);
                    anyViolationDetected = true;
                    
                    if (isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
                        logDebug("Bedrock player " + player.getName() + " would be flagged for: " + reason + ", but is exempt");
                    } else {
                        if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                            sendAlert(player, reason, brand, blockedChannel, violationType);
                            markAlertSent(uuid, violationType);
                            lastAlertTime.put(uuid, System.currentTimeMillis());
                        }
                        if (plugin.getConfigManager().shouldPunishBlockedChannels()) {
                            shouldPunish = true;
                        }
                    }
                }
            }
        }

        // If player is a Bedrock player and we're in EXEMPT mode, don't punish
        if (!allViolations.isEmpty() && isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
            logDebug("Bedrock player " + player.getName() + " would be processed for violations, but is exempt");
            return;
        }

        // Only execute punishment if any violations were found
        if (shouldPunish && !allViolations.isEmpty() && !data.isAlreadyPunished()) {
            // Choose the first violation as the primary reason for punishment
            String primaryReason = allViolations.get(0);
            String violationType = determineViolationType(primaryReason);
            String violatedChannel = extractChannelFromReason(primaryReason);
            
            executePunishment(player, primaryReason, brand, violationType, violatedChannel);
            data.setAlreadyPunished(true);
        }
        
        // Send initial channels webhook only if no violations
        if (!anyViolationDetected && !data.getChannels().isEmpty()) {
            plugin.getDiscordWebhookHandler().sendInitialChannelsWebhook(player, data.getChannels());
        }
    }
    
    // Helper method to determine violation type from reason
    private String determineViolationType(String reason) {
        if (reason.contains("Vanilla client with plugin channels")) {
            return "VANILLA_WITH_CHANNELS";
        } else if (reason.contains("Non-vanilla client with channels")) {
            return "NON_VANILLA_WITH_CHANNELS";
        } else if (reason.contains("Blocked channel:")) {
            return "BLOCKED_CHANNEL";
        } else if (reason.contains("Client channels don't match whitelist")) {
            return "CHANNEL_WHITELIST";
        } else if (reason.contains("Blocked client brand:")) {
            return "BLOCKED_BRAND";
        } else if (reason.contains("Spoofing Geyser client")) {
            return "GEYSER_SPOOF";
        }
        return "";
    }

    // Helper method to extract channel from reason
    private String extractChannelFromReason(String reason) {
        if (reason.contains("Blocked channel: ")) {
            return reason.substring("Blocked channel: ".length());
        }
        return null;
    }
    
    private boolean isBrandBlocked(String brand) {
        if (brand == null) return false;
        
        return plugin.getConfigManager().isBrandBlocked(brand);
    }
    
    private boolean checkChannelWhitelist(java.util.Set<String> playerChannels) {
        boolean strictMode = plugin.getConfigManager().isChannelWhitelistStrict();
        List<String> whitelistedChannels = plugin.getConfigManager().getBlockedChannels();
        
        // If no channels are whitelisted, then fail if player has any channels
        if (whitelistedChannels.isEmpty()) {
            return playerChannels.isEmpty();
        }
        
        // SIMPLE mode: Player must have at least one of the whitelisted channels
        if (!strictMode) {
            for (String playerChannel : playerChannels) {
                if (plugin.getConfigManager().matchesChannelPattern(playerChannel)) {
                    return true; // Pass if player has at least one whitelisted channel
                }
            }
            return false; // Fail if player has no whitelisted channels
        } 
        // STRICT mode: Player must have ALL whitelisted channels AND only whitelisted channels
        else {
            // 1. Check if every player channel is whitelisted
            for (String playerChannel : playerChannels) {
                if (!plugin.getConfigManager().matchesChannelPattern(playerChannel)) {
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
                        // If regex is invalid, just do direct match as fallback
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
    
    private String findBlockedChannel(java.util.Set<String> playerChannels) {
        for (String playerChannel : playerChannels) {
            if (plugin.getConfigManager().matchesChannelPattern(playerChannel)) {
                return playerChannel;
            }
        }
        
        return null; // No blocked channels found
    }
    
    private boolean handlePluginMessage(UUID playerUUID, String channel, byte[] data, PlayerData playerData) {
        boolean channelRegistered = false;
        
        // Handle channel registration/unregistration (for Fabric/Forge mods)
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            channelRegistered = handleChannelRegistration(playerUUID, channel, data, playerData);
        } else {
            // Mark the start of initial registration period if this is the first channel
            if (!initialRegistrationTime.containsKey(playerUUID) && playerData.getChannels().isEmpty()) {
                initialRegistrationTime.put(playerUUID, System.currentTimeMillis());
                logDebug("Starting initial registration period for " + playerUUID);
            }
            
            // Direct channel usage - check if this is a new channel
            if (!playerData.getChannels().contains(channel)) {
                // This is a new channel, add it
                playerData.addChannel(channel);
                long currentTime = System.currentTimeMillis();
                
                // Only consider it as "modified" if we're past the initial registration window
                if (initialRegistrationTime.containsKey(playerUUID) && 
                    currentTime - initialRegistrationTime.get(playerUUID) > INITIAL_REGISTRATION_WINDOW) {
                    
                    // If modified channels alerts are enabled, notify
                    if (plugin.getConfigManager().isModifiedChannelsEnabled()) {
                        Player player = Bukkit.getPlayer(playerUUID);
                        if (player != null) {
                            // Check if we've already sent this specific modified channel alert
                            String alertType = "MODIFIED_CHANNEL:" + channel;
                            if (!hasAlertBeenSent(playerUUID, alertType) && !isOnCooldown(playerUUID)) {
                                notifyModifiedChannel(player, channel);
                                markAlertSent(playerUUID, alertType);
                                lastAlertTime.put(playerUUID, System.currentTimeMillis());
                            }
                        }
                    }
                }
                
                logDebug("Direct channel used: " + channel);
                channelRegistered = true;
            } else {
                // Channel already known, just log it
                logDebug("Direct channel used: " + channel);
            }
        }
        
        return channelRegistered;
    }
    
    private boolean handleChannelRegistration(UUID playerUUID, String channel, byte[] data, PlayerData playerData) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        boolean didRegister = false;
        
        // Mark the start of initial registration period if this is the first channel registration
        if (!initialRegistrationTime.containsKey(playerUUID) && channel.equals("minecraft:register")) {
            initialRegistrationTime.put(playerUUID, System.currentTimeMillis());
            logDebug("Starting initial registration period for " + playerUUID);
        }
        
        long currentTime = System.currentTimeMillis();
        boolean isPastInitialRegistration = initialRegistrationTime.containsKey(playerUUID) && 
                                          currentTime - initialRegistrationTime.get(playerUUID) > INITIAL_REGISTRATION_WINDOW;
        
        for (String registeredChannel : channels) {
            if (channel.equals("minecraft:register")) {
                // Check if this is a new channel
                if (!playerData.getChannels().contains(registeredChannel)) {
                    playerData.addChannel(registeredChannel);
                    logDebug("Channel registered: " + registeredChannel);
                    
                    // Only consider it as "modified" if we're past the initial registration window
                    if (isPastInitialRegistration) {
                        // If modified channels alerts are enabled, notify
                        if (plugin.getConfigManager().isModifiedChannelsEnabled()) {
                            Player player = Bukkit.getPlayer(playerUUID);
                            if (player != null) {
                                // Check if we've already sent this specific modified channel alert
                                String alertType = "MODIFIED_CHANNEL:" + registeredChannel;
                                if (!hasAlertBeenSent(playerUUID, alertType) && !isOnCooldown(playerUUID)) {
                                    notifyModifiedChannel(player, registeredChannel);
                                    markAlertSent(playerUUID, alertType);
                                    lastAlertTime.put(playerUUID, System.currentTimeMillis());
                                }
                            }
                        }
                    }
                    
                    didRegister = true;
                }
            } else {
                playerData.removeChannel(registeredChannel);
                logDebug("Channel unregistered: " + registeredChannel);
            }
        }
        
        return didRegister;
    }
    
    private void notifyModifiedChannel(Player player, String channel) {
        // Only notify if player hasn't been punished
        UUID playerUUID = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(playerUUID);
        
        // Don't send additional blocked channel alerts when notifying about modified channels
        if (data != null && data.isAlreadyPunished()) {
            return;
        }
        
        // Skip if on cooldown
        if (isOnCooldown(playerUUID)) {
            return;
        }
        
        String alertType = "MODIFIED_CHANNEL:" + channel;
        if (hasAlertBeenSent(playerUUID, alertType)) {
            return;
        }
        
        // Format the player alert message
        String alertMessage = plugin.getConfigManager().getModifiedChannelsAlertMessage()
                .replace("%player%", player.getName())
                .replace("%channel%", channel);
        
        // Format the console alert message
        String consoleAlertMessage = plugin.getConfigManager().getModifiedChannelsConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%channel%", channel);
        
        // Convert color codes for player messages
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', alertMessage);
        
        // Log to console
        plugin.getLogger().info(consoleAlertMessage);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord webhook if enabled
        if (plugin.getConfigManager().isModifiedChannelsDiscordEnabled()) {
            String reason = "Modified channel: " + channel;
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                reason,
                plugin.getClientBrand(player), 
                channel, 
                null // No violations list
            );
        }
        
        // Mark this alert as sent
        markAlertSent(playerUUID, alertType);
        lastAlertTime.put(playerUUID, System.currentTimeMillis());
    }
    
    // Send alert message for multiple violations
    private void sendMultipleViolationsAlert(Player player, List<String> violations, String brand) {
        UUID playerUUID = player.getUniqueId();
        
        // Skip if on cooldown
        if (isOnCooldown(playerUUID)) {
            return;
        }
        
        // Skip if we've already sent a multiple violations alert for this player
        if (hasAlertBeenSent(playerUUID, "MULTIPLE_VIOLATIONS")) {
            return;
        }
        
        // Join all reasons with commas
        String reasonsList = String.join(", ", violations);
        
        // Format the player alert message for multiple violations
        String playerAlert = plugin.getConfigManager().getMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reasons%", reasonsList);
        
        // Format the console alert message for multiple violations
        String consoleAlert = plugin.getConfigManager().getConsoleMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reasons%", reasonsList);
        
        // Convert color codes for player messages
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        
        // Log to console directly using the console format (no need to strip colors)
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if enabled
        plugin.getDiscordWebhookHandler().sendAlert(player, "Multiple Violations", brand, null, violations);
        
        // Mark this alert as sent
        markAlertSent(playerUUID, "MULTIPLE_VIOLATIONS");
        lastAlertTime.put(playerUUID, System.currentTimeMillis());
    }
    
    // Send alert message to staff and console with rate limiting
    private void sendAlert(Player player, String reason, String brand, String violatedChannel, String violationType) {
        UUID playerUUID = player.getUniqueId();
        
        // Skip if on cooldown
        if (isOnCooldown(playerUUID)) {
            return;
        }
        
        // Skip if we've already sent this specific alert type
        if (hasAlertBeenSent(playerUUID, violationType)) {
            return;
        }
        
        // Select the appropriate alert message based on violation type
        String alertTemplate;
        String consoleAlertTemplate;
        
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                alertTemplate = plugin.getConfigManager().getVanillaCheckAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getVanillaCheckConsoleAlertMessage();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                alertTemplate = plugin.getConfigManager().getNonVanillaCheckAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getNonVanillaCheckConsoleAlertMessage();
                break;
                
            case "CHANNEL_WHITELIST":
                alertTemplate = plugin.getConfigManager().getBlockedChannelsAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getBlockedChannelsConsoleAlertMessage();
                break;
                
            case "BLOCKED_BRAND":
                alertTemplate = plugin.getConfigManager().getBlockedBrandsAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getBlockedBrandsConsoleAlertMessage();
                break;
                
            case "GEYSER_SPOOF":
                alertTemplate = plugin.getConfigManager().getGeyserSpoofAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getGeyserSpoofConsoleAlertMessage();
                break;
                
            default:
                if (violationType.startsWith("BLOCKED_CHANNEL:")) {
                    alertTemplate = plugin.getConfigManager().getBlockedChannelsAlertMessage();
                    consoleAlertTemplate = plugin.getConfigManager().getBlockedChannelsConsoleAlertMessage();
                } else {
                    // Fallback to global messages
                    alertTemplate = plugin.getConfigManager().getAlertMessage();
                    consoleAlertTemplate = plugin.getConfigManager().getConsoleAlertMessage();
                }
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
        
        if (violatedChannel != null) {
            playerAlert = playerAlert.replace("%channel%", violatedChannel);
            consoleAlert = consoleAlert.replace("%channel%", violatedChannel);
        }
        
        // Convert color codes for player messages
        String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
        
        // Log to console directly using the console format (no need to strip colors)
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredPlayerAlert));
        
        // Send to Discord if enabled
        List<String> singleViolation = new ArrayList<>();
        singleViolation.add(reason);
        plugin.getDiscordWebhookHandler().sendAlert(player, reason, brand, violatedChannel, singleViolation);
        
        // Mark this alert as sent
        markAlertSent(playerUUID, violationType);
        lastAlertTime.put(playerUUID, System.currentTimeMillis());
    }
    
    // Execute punishment commands
    private void executePunishment(Player player, String reason, String brand, String violationType, String violatedChannel) {
        List<String> punishments;
        
        // Select the appropriate punishments based on violation type
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                punishments = plugin.getConfigManager().getVanillaCheckPunishments();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                punishments = plugin.getConfigManager().getNonVanillaCheckPunishments();
                break;
                
            case "BLOCKED_CHANNEL":
            case "CHANNEL_WHITELIST":
                punishments = plugin.getConfigManager().getBlockedChannelsPunishments();
                break;
                
            case "BLOCKED_BRAND":
                punishments = plugin.getConfigManager().getBlockedBrandsPunishments();
                break;
                
            case "GEYSER_SPOOF":
                punishments = plugin.getConfigManager().getGeyserSpoofPunishments();
                break;
                
            default:
                // Fallback to global punishments
                punishments = plugin.getConfigManager().getPunishments();
        }
        
        // If no specific punishments defined, fall back to global
        if (punishments.isEmpty()) {
            punishments = plugin.getConfigManager().getPunishments();
        }
        
        // Execute the punishments
        for (String command : punishments) {
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", reason)
                                     .replace("%brand%", brand != null ? brand : "unknown");
            
            if (violatedChannel != null) {
                formatted = formatted.replace("%channel%", violatedChannel);
            }
            
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
        }
    }
    
    private void logDebug(String message) {
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] " + message);
        }
    }
    
    /**
     * Cleanup method to remove player data on disconnect
     */
    public void playerDisconnected(UUID playerUUID) {
        flaggedPlayers.remove(playerUUID);
        initialRegistrationTime.remove(playerUUID);
        alertTypesSent.remove(playerUUID);
        lastAlertTime.remove(playerUUID);
        
        // Also clear the player's alert status in the Discord webhook handler
        plugin.clearPlayerAlertStatus(playerUUID);
    }
}