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
            // Get a reference to the PlayerJoinListener check method
            // Since we can't call it directly, we'll schedule an immediate task
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Only check if player is still online
                if (player.isOnline() && !data.isAlreadyPunished()) {
                    // Check if player is spoofing after channel registration
                    if (plugin.isPlayerSpoofing(player)) {
                        checkAndPunishPlayer(player);
                    }
                }
            });
        }
    }
    
    private void checkAndPunishPlayer(Player player) {
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
        
        boolean shouldPunish = false;
        String reason = "";

        // Handle potential Geyser spoofing
        if (plugin.getConfigManager().isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
            reason = "Spoofing Geyser client";
            shouldPunish = true;
        }

        // Check the brand first
        if (!shouldPunish && plugin.getConfigManager().checkBrandFormatting() && hasInvalidFormatting(brand)) {
            reason = "Invalid brand formatting";
            shouldPunish = true;
        }
        
        // Check for brand blocking/whitelist
        if (!shouldPunish && plugin.getConfigManager().isBlockedBrandsEnabled()) {
            boolean brandBlocked = isBrandBlocked(brand);
            if (brandBlocked) {
                reason = "Blocked client brand: " + brand;
                shouldPunish = true;
            }
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Vanilla client check
        if (!shouldPunish && claimsVanilla && hasChannels) {
            reason = "Vanilla client with plugin channels";
            shouldPunish = true;
        }
        else if (!shouldPunish && plugin.getConfigManager().shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            reason = "Non-vanilla client with channels";
            shouldPunish = true;
        }
        
        // Channel whitelist/blacklist check
        if (!shouldPunish && plugin.getConfigManager().isBlockedChannelsEnabled()) {
            if (plugin.getConfigManager().isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    reason = "Client channels don't match whitelist";
                    shouldPunish = true;
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    reason = "Blocked channel: " + blockedChannel;
                    shouldPunish = true;
                }
            }
        }

        // If player is a Bedrock player and we're in EXEMPT mode, don't punish
        if (shouldPunish && isBedrockPlayer && plugin.getConfigManager().isBedrockExemptMode()) {
            logDebug("Bedrock player " + player.getName() + " would be punished for: " + reason + ", but is exempt");
            return;
        }

        if (shouldPunish) {
            executePunishment(player, reason, brand);
            data.setAlreadyPunished(true);
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
    
    private void executePunishment(Player player, String reason, String brand) {
        for (String command : plugin.getConfigManager().getPunishments()) {
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", reason)
                                     .replace("%brand%", brand);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
        }

        String alert = plugin.getConfigManager().getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand)
                .replace("%reason%", reason);
        
        // Convert color codes for console
        String coloredAlert = ChatColor.translateAlternateColorCodes('&', alert);
        
        // Log to console
        plugin.getLogger().warning(coloredAlert);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredAlert));
    }
    
    private void logDebug(String message) {
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] " + message);
        }
    }
}