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

public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;

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
                // Only check if player is still online
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
        
        boolean shouldAlert = false;
        boolean shouldPunish = false;
        String reason = "";
        String violationType = "";
        String violatedChannel = null;

        // Handle potential Geyser spoofing
        if (plugin.getConfigManager().isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
            reason = "Spoofing Geyser client";
            violationType = "GEYSER_SPOOF";
            shouldAlert = true;
            shouldPunish = plugin.getConfigManager().shouldPunishGeyserSpoof();
        }

        // Check the brand formatting
        if (!shouldAlert && plugin.getConfigManager().checkBrandFormatting() && hasInvalidFormatting(brand)) {
            reason = "Invalid brand formatting";
            violationType = "BRAND_FORMAT";
            shouldAlert = true;
            shouldPunish = plugin.getConfigManager().shouldPunishBrandFormatting();
        }
        
        // Check for brand blocking/whitelist
        if (!shouldAlert && plugin.getConfigManager().isBlockedBrandsEnabled()) {
            boolean brandBlocked = isBrandBlocked(brand);
            if (brandBlocked) {
                reason = "Blocked client brand: " + brand;
                violationType = "BLOCKED_BRAND";
                shouldAlert = true;
                shouldPunish = plugin.getConfigManager().shouldPunishBlockedBrands();
            }
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Vanilla client check
        if (!shouldAlert && plugin.getConfigManager().isVanillaCheckEnabled() && 
            claimsVanilla && hasChannels) {
            reason = "Vanilla client with plugin channels";
            violationType = "VANILLA_WITH_CHANNELS";
            shouldAlert = true;
            shouldPunish = plugin.getConfigManager().shouldPunishVanillaCheck();
        }
        // Non-vanilla with channels check
        else if (!shouldAlert && plugin.getConfigManager().shouldBlockNonVanillaWithChannels() && 
                !claimsVanilla && hasChannels) {
            reason = "Non-vanilla client with channels";
            violationType = "NON_VANILLA_WITH_CHANNELS";
            shouldAlert = true;
            shouldPunish = plugin.getConfigManager().shouldPunishNonVanillaCheck();
        }
        
        // Channel whitelist/blacklist check
        if (!shouldAlert && plugin.getConfigManager().isBlockedChannelsEnabled()) {
            if (plugin.getConfigManager().isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    reason = "Client channels don't match whitelist";
                    violationType = "CHANNEL_WHITELIST";
                    shouldAlert = true;
                    shouldPunish = plugin.getConfigManager().shouldPunishBlockedChannels();
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    reason = "Blocked channel: " + blockedChannel;
                    violationType = "BLOCKED_CHANNEL";
                    violatedChannel = blockedChannel;
                    shouldAlert = true;
                    shouldPunish = plugin.getConfigManager().shouldPunishBlockedChannels();
                }
            }
        }

        // If player is a Bedrock player and we're in EXEMPT mode, don't punish
        if ((shouldAlert || shouldPunish) && isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
            logDebug("Bedrock player " + player.getName() + " would be processed for: " + reason + ", but is exempt");
            return;
        }

        if (shouldAlert) {
            // Always send the alert if a violation is detected
            sendAlert(player, reason, brand, violatedChannel);
            
            // Only execute punishment if enabled for this violation type
            if (shouldPunish) {
                executePunishment(player, reason, brand, violationType, violatedChannel);
                data.setAlreadyPunished(true);
            }
        }
    }
    
    private boolean isBrandBlocked(String brand) {
        if (brand == null) return false;
        
        java.util.List<String> blockedBrands = plugin.getConfigManager().getBlockedBrands();
        boolean exactMatch = plugin.getConfigManager().isExactBrandMatchRequired();
        boolean whitelistMode = plugin.getConfigManager().isBrandWhitelistEnabled();
        
        // No brands in the list means no blocks in blacklist mode, or no allowed brands in whitelist mode
        if (blockedBrands.isEmpty()) {
            return whitelistMode; // If whitelist is empty, everything is blocked
        }
        
        boolean isListed = false;
        
        for (String blockedBrand : blockedBrands) {
            boolean matches;
            
            if (exactMatch) {
                matches = brand.equalsIgnoreCase(blockedBrand);
            } else {
                matches = brand.toLowerCase().contains(blockedBrand.toLowerCase());
            }
            
            if (matches) {
                isListed = true;
                break;
            }
        }
        
        // In whitelist mode, being listed is good (not blocked)
        // In blacklist mode, being listed is bad (blocked)
        return whitelistMode ? !isListed : isListed;
    }
    
    private boolean checkChannelWhitelist(java.util.Set<String> playerChannels) {
        java.util.List<String> whitelistedChannels = plugin.getConfigManager().getBlockedChannels(); // Reusing the values list
        boolean exactMatch = plugin.getConfigManager().isExactChannelMatchRequired();
        boolean strictMode = plugin.getConfigManager().isChannelWhitelistStrict();
        
        // If no channels are whitelisted, then fail if player has any channels
        if (whitelistedChannels.isEmpty()) {
            return playerChannels.isEmpty();
        }
        
        // SIMPLE mode: Player must have at least one of the whitelisted channels
        if (!strictMode) {
            for (String playerChannel : playerChannels) {
                for (String whitelistedChannel : whitelistedChannels) {
                    boolean matches;
                    
                    if (exactMatch) {
                        matches = playerChannel.equals(whitelistedChannel);
                    } else {
                        matches = playerChannel.contains(whitelistedChannel);
                    }
                    
                    if (matches) {
                        return true; // Pass if player has at least one whitelisted channel
                    }
                }
            }
            return false; // Fail if player has no whitelisted channels
        } 
        // STRICT mode: Player must have ALL whitelisted channels AND only whitelisted channels
        else {
            // 1. Check if every player channel is whitelisted
            for (String playerChannel : playerChannels) {
                boolean channelIsWhitelisted = false;
                
                for (String whitelistedChannel : whitelistedChannels) {
                    boolean matches;
                    
                    if (exactMatch) {
                        matches = playerChannel.equals(whitelistedChannel);
                    } else {
                        matches = playerChannel.contains(whitelistedChannel);
                    }
                    
                    if (matches) {
                        channelIsWhitelisted = true;
                        break;
                    }
                }
                
                if (!channelIsWhitelisted) {
                    return false; // Fail if any player channel is not whitelisted
                }
            }
            
            // 2. Also check if player has ALL whitelisted channels
            for (String whitelistedChannel : whitelistedChannels) {
                boolean playerHasChannel = false;
                
                for (String playerChannel : playerChannels) {
                    boolean matches;
                    
                    if (exactMatch) {
                        matches = playerChannel.equals(whitelistedChannel);
                    } else {
                        matches = playerChannel.contains(whitelistedChannel);
                    }
                    
                    if (matches) {
                        playerHasChannel = true;
                        break;
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
        java.util.List<String> blockedChannels = plugin.getConfigManager().getBlockedChannels();
        boolean exactMatch = plugin.getConfigManager().isExactChannelMatchRequired();
        
        for (String playerChannel : playerChannels) {
            if (exactMatch) {
                // Check for exact match with any blocked channel
                if (blockedChannels.contains(playerChannel)) {
                    return playerChannel;
                }
            } else {
                // Check if player channel contains any blocked channel string
                for (String blockedChannel : blockedChannels) {
                    if (playerChannel.contains(blockedChannel)) {
                        return playerChannel;
                    }
                }
            }
        }
        
        return null; // No blocked channels found
    }
    
    private boolean handlePluginMessage(UUID playerUUID, String channel, byte[] data, PlayerData playerData) {
        boolean channelRegistered = false;
        
        // Handle channel registration/unregistration (for Fabric/Forge mods)
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            channelRegistered = handleChannelRegistration(channel, data, playerData);
        } else {
            // Direct channel usage
            playerData.addChannel(channel);
            logDebug("Direct channel used: " + channel);
            channelRegistered = true;
        }
        
        return channelRegistered;
    }
    
    private boolean handleChannelRegistration(String channel, byte[] data, PlayerData playerData) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        boolean didRegister = false;
        
        for (String registeredChannel : channels) {
            if (channel.equals("minecraft:register")) {
                playerData.addChannel(registeredChannel);
                logDebug("Channel registered: " + registeredChannel);
                didRegister = true;
            } else {
                playerData.removeChannel(registeredChannel);
                logDebug("Channel unregistered: " + registeredChannel);
            }
        }
        
        return didRegister;
    }
    
    private boolean hasInvalidFormatting(String brand) {
        return brand.matches(".*[§&].*") || 
               !brand.matches("^[a-zA-Z0-9 _-]+$");
    }
    
    // Send alert message to staff and console with rate limiting
    private void sendAlert(Player player, String reason, String brand, String violatedChannel) {
        UUID playerUUID = player.getUniqueId();
        
        // Format the player alert message with placeholders
        String playerAlert = plugin.getConfigManager().getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reason%", reason);
        
        // Format the console alert message with placeholders
        String consoleAlert = plugin.getConfigManager().getConsoleAlertMessage()
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
        
        // Only send in-game alerts if cooldown allows it
        if (plugin.canSendAlert(playerUUID)) {
            // Notify players with permission
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("antispoof.alerts"))
                    .forEach(p -> p.sendMessage(coloredPlayerAlert));
        }
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
}
