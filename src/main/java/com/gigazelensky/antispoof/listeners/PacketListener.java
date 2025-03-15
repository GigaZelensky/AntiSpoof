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
    
    // Time window for initial channel registration (in milliseconds)
    private static final long INITIAL_REGISTRATION_WINDOW = 5000; // 5 seconds

    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
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
                if (player.isOnline() && !data.isAlreadyPunished() && !flaggedPlayers.contains(playerUUID)) {
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
        
        // List to collect all violations
        List<String> violations = new ArrayList<>();
        boolean shouldAlert = false;
        boolean shouldPunish = false;
        String primaryReason = "";
        String violationType = "";
        String violatedChannel = null;

        // Handle potential Geyser spoofing
        if (plugin.getConfigManager().isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
            String reason = "Spoofing Geyser client";
            violations.add(reason);
            if (primaryReason.isEmpty()) {
                primaryReason = reason;
                violationType = "GEYSER_SPOOF";
            }
            shouldAlert = true;
            if (plugin.getConfigManager().shouldPunishGeyserSpoof()) {
                shouldPunish = true;
            }
        }

        // Check the brand formatting
        if (plugin.getConfigManager().checkBrandFormatting() && hasInvalidFormatting(brand)) {
            String reason = "Invalid brand formatting";
            violations.add(reason);
            if (primaryReason.isEmpty()) {
                primaryReason = reason;
                violationType = "BRAND_FORMAT";
            }
            shouldAlert = true;
            // Only enable punishment if it's explicitly enabled in the config
            if (plugin.getConfigManager().shouldPunishBrandFormatting()) {
                shouldPunish = true;
            }
        }
        
        // Check for brand blocking/whitelist
        if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
            boolean brandBlocked = isBrandBlocked(brand);
            if (brandBlocked) {
                // Only add as a violation if count-as-flag is true
                if (plugin.getConfigManager().shouldCountNonWhitelistedBrandsAsFlag()) {
                    String reason = "Blocked client brand: " + brand;
                    violations.add(reason);
                    if (primaryReason.isEmpty()) {
                        primaryReason = reason;
                        violationType = "BLOCKED_BRAND";
                    }
                    shouldAlert = true;
                    if (plugin.getConfigManager().shouldPunishBlockedBrands()) {
                        shouldPunish = true;
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
            violations.add(reason);
            if (primaryReason.isEmpty()) {
                primaryReason = reason;
                violationType = "VANILLA_WITH_CHANNELS";
            }
            shouldAlert = true;
            if (plugin.getConfigManager().shouldPunishVanillaCheck()) {
                shouldPunish = true;
            }
        }
        // Non-vanilla with channels check
        else if (plugin.getConfigManager().shouldBlockNonVanillaWithChannels() && 
                !claimsVanilla && hasChannels) {
            String reason = "Non-vanilla client with channels";
            violations.add(reason);
            if (primaryReason.isEmpty()) {
                primaryReason = reason;
                violationType = "NON_VANILLA_WITH_CHANNELS";
            }
            shouldAlert = true;
            if (plugin.getConfigManager().shouldPunishNonVanillaCheck()) {
                shouldPunish = true;
            }
        }
        
        // Channel whitelist/blacklist check
        if (plugin.getConfigManager().isBlockedChannelsEnabled()) {
            if (plugin.getConfigManager().isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    String reason = "Client channels don't match whitelist";
                    violations.add(reason);
                    if (primaryReason.isEmpty()) {
                        primaryReason = reason;
                        violationType = "CHANNEL_WHITELIST";
                    }
                    shouldAlert = true;
                    if (plugin.getConfigManager().shouldPunishBlockedChannels()) {
                        shouldPunish = true;
                    }
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    String reason = "Blocked channel: " + blockedChannel;
                    violations.add(reason);
                    if (primaryReason.isEmpty()) {
                        primaryReason = reason;
                        violationType = "BLOCKED_CHANNEL";
                        violatedChannel = blockedChannel;
                    }
                    shouldAlert = true;
                    if (plugin.getConfigManager().shouldPunishBlockedChannels()) {
                        shouldPunish = true;
                    }
                }
            }
        }

        // If player is a Bedrock player and we're in EXEMPT mode, don't punish
        if ((shouldAlert || shouldPunish) && isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
            logDebug("Bedrock player " + player.getName() + " would be processed for: " + primaryReason + ", but is exempt");
            return;
        }

        if (shouldAlert) {
            // Mark player as flagged to prevent duplicate alerts
            flaggedPlayers.add(uuid);
            
            // Always send the alert if a violation is detected
            if (violations.size() > 1) {
                sendMultipleViolationsAlert(player, violations, brand);
            } else {
                sendAlert(player, primaryReason, brand, violatedChannel, violationType);
            }
            
            // Only execute punishment if enabled for this violation type
            if (shouldPunish) {
                executePunishment(player, primaryReason, brand, violationType, violatedChannel);
                data.setAlreadyPunished(true);
            }
        }
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
                            notifyModifiedChannel(player, channel);
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
                                notifyModifiedChannel(player, registeredChannel);
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
        // Only notify if player hasn't been flagged for spoofing or has been punished
        UUID playerUUID = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(playerUUID);
        
        // Don't send additional blocked channel alerts when notifying about modified channels
        if (data != null && data.isAlreadyPunished()) {
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
            plugin.getDiscordWebhookHandler().sendAlert(
                player, 
                "Modified channel: " + channel,
                plugin.getClientBrand(player), 
                channel, 
                null, // No violations list
                "MODIFIED_CHANNEL"
            );
        }
    }
    
    private boolean hasInvalidFormatting(String brand) {
        return brand.matches(".*[§&].*") || 
               !brand.matches("^[a-zA-Z0-9 _-]+$");
    }
    
    // Send alert message for multiple violations
    private void sendMultipleViolationsAlert(Player player, List<String> violations, String brand) {
        UUID playerUUID = player.getUniqueId();
        
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
        plugin.getDiscordWebhookHandler().sendAlert(player, "Multiple Violations", brand, null, violations, "MULTIPLE");
    }
    
    // Send alert message to staff and console with rate limiting
    private void sendAlert(Player player, String reason, String brand, String violatedChannel, String violationType) {
        UUID playerUUID = player.getUniqueId();
        
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
                
            case "BRAND_FORMAT":
                alertTemplate = plugin.getConfigManager().getBrandFormattingAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getBrandFormattingConsoleAlertMessage();
                break;
                
            case "BLOCKED_CHANNEL":
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
                // Fallback to global messages
                alertTemplate = plugin.getConfigManager().getAlertMessage();
                consoleAlertTemplate = plugin.getConfigManager().getConsoleAlertMessage();
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
        plugin.getDiscordWebhookHandler().sendAlert(player, reason, brand, violatedChannel, singleViolation, violationType);
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
                
            case "BRAND_FORMAT":
                punishments = plugin.getConfigManager().getBrandFormattingPunishments();
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
    }
}