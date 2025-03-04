package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Set;
import java.util.UUID;
import java.util.List;

public class PlayerJoinListener implements Listener {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;

    public PlayerJoinListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("antispoof.bypass")) return;
        
        UUID uuid = player.getUniqueId();
        plugin.getPlayerDataMap().putIfAbsent(uuid, new PlayerData());
        
        int delay = config.getCheckDelay();
        
        // If delay is greater than 0, schedule the check
        if (delay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> checkPlayer(player), delay * 20L);
        } else if (delay == 0) {
            // For zero delay, check immediately but also let the packet listener handle subsequent checks
            // This is to catch players whose brand is available but channels aren't yet
            checkPlayer(player);
        }
        // If delay is negative, rely completely on packet listener checks
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerDataMap().remove(event.getPlayer().getUniqueId());
        plugin.getPlayerBrands().remove(event.getPlayer().getName());
    }

    private void checkPlayer(Player player) {
        if (!player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        if (data == null) return;
        
        // Skip if already punished
        if (data.isAlreadyPunished()) return;

        // Check if player is a Bedrock player
        boolean isBedrockPlayer = plugin.isBedrockPlayer(player);
        
        // If player is a Bedrock player and we're set to ignore them, return immediately
        if (isBedrockPlayer && config.getBedrockHandlingMode().equals("IGNORE")) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Ignoring Bedrock player: " + player.getName());
            }
            return;
        }

        String brand = plugin.getClientBrand(player);
        if (brand == null) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] No brand available for " + player.getName());
            }
            return;
        }
        
        boolean shouldAlert = false;
        boolean shouldPunish = false;
        String reason = "";
        String violationType = "";
        String violatedChannel = null;

        // Handle potential Geyser spoofing
        if (config.isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
            reason = "Spoofing Geyser client";
            violationType = "GEYSER_SPOOF";
            shouldAlert = true;
            shouldPunish = config.shouldPunishGeyserSpoof();
        }

        // Check the brand formatting
        if (!shouldAlert && config.checkBrandFormatting() && hasInvalidFormatting(brand)) {
            reason = "Invalid brand formatting";
            violationType = "BRAND_FORMAT";
            shouldAlert = true;
            shouldPunish = config.shouldPunishBrandFormatting();
        }
        
        // Check for brand blocking/whitelist
        if (!shouldAlert && config.isBlockedBrandsEnabled()) {
            boolean brandBlocked = isBrandBlocked(brand);
            if (brandBlocked) {
                reason = "Blocked client brand: " + brand;
                violationType = "BLOCKED_BRAND";
                shouldAlert = true;
                shouldPunish = config.shouldPunishBlockedBrands();
            }
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Vanilla client check
        if (!shouldAlert && config.isVanillaCheckEnabled() && 
            claimsVanilla && hasChannels) {
            reason = "Vanilla client with plugin channels";
            violationType = "VANILLA_WITH_CHANNELS";
            shouldAlert = true;
            shouldPunish = config.shouldPunishVanillaCheck();
        }
        // Non-vanilla with channels check
        else if (!shouldAlert && config.shouldBlockNonVanillaWithChannels() && 
                !claimsVanilla && hasChannels) {
            reason = "Non-vanilla client with channels";
            violationType = "NON_VANILLA_WITH_CHANNELS";
            shouldAlert = true;
            shouldPunish = config.shouldPunishNonVanillaCheck();
        }
        
        // Channel whitelist/blacklist check
        if (!shouldAlert && config.isBlockedChannelsEnabled()) {
            if (config.isChannelWhitelistEnabled()) {
                // Whitelist mode
                boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
                if (!passesWhitelist) {
                    reason = "Client channels don't match whitelist";
                    violationType = "CHANNEL_WHITELIST";
                    shouldAlert = true;
                    shouldPunish = config.shouldPunishBlockedChannels();
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    reason = "Blocked channel: " + blockedChannel;
                    violationType = "BLOCKED_CHANNEL";
                    violatedChannel = blockedChannel;
                    shouldAlert = true;
                    shouldPunish = config.shouldPunishBlockedChannels();
                }
            }
        }

        // If player is a Bedrock player and we're in EXEMPT mode, don't process further
        if ((shouldAlert || shouldPunish) && isBedrockPlayer && config.isBedrockExemptMode()) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                       " would be processed for: " + reason + ", but is exempt");
            }
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

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Checked player: " + player.getName());
            plugin.getLogger().info("[Debug] Brand: " + brand);
            plugin.getLogger().info("[Debug] Channels: " + String.join(", ", data.getChannels()));
            if (isBedrockPlayer) {
                plugin.getLogger().info("[Debug] Player is a Bedrock player");
            }
        }
    }

    private boolean isBrandBlocked(String brand) {
        if (brand == null) return false;
        
        List<String> blockedBrands = config.getBlockedBrands();
        boolean exactMatch = config.isExactBrandMatchRequired();
        boolean whitelistMode = config.isBrandWhitelistEnabled();
        
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
    
    private boolean checkChannelWhitelist(Set<String> playerChannels) {
        List<String> whitelistedChannels = config.getBlockedChannels(); // Reusing the values list
        boolean exactMatch = config.isExactChannelMatchRequired();
        boolean strictMode = config.isChannelWhitelistStrict();
        
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

    private String findBlockedChannel(Set<String> playerChannels) {
        List<String> blockedChannels = config.getBlockedChannels();
        boolean exactMatch = config.isExactChannelMatchRequired();
        
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

    private boolean hasInvalidFormatting(String brand) {
        return brand.matches(".*[§&].*") || 
               !brand.matches("^[a-zA-Z0-9 _-]+$");
    }

    // Send alert message to staff and console
    private void sendAlert(Player player, String reason, String brand, String violatedChannel) {
        // Format the alert message with placeholders
        String alert = config.getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", brand != null ? brand : "unknown")
                .replace("%reason%", reason);
        
        if (violatedChannel != null) {
            alert = alert.replace("%channel%", violatedChannel);
        }
        
        // Convert color codes for player messages
        String coloredAlert = ChatColor.translateAlternateColorCodes('&', alert);
        
        // Create a clean console message without minecraft color codes
        String consoleMessage = ChatColor.stripColor(coloredAlert);
        
        // Log to console as INFO
        plugin.getLogger().info(consoleMessage);
        
        // Notify players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(coloredAlert));
    }

    // Execute punishment commands
    private void executePunishment(Player player, String reason, String brand, String violationType, String violatedChannel) {
        List<String> punishments;
        
        // Select the appropriate punishments based on violation type
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                punishments = config.getVanillaCheckPunishments();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                punishments = config.getNonVanillaCheckPunishments();
                break;
                
            case "BRAND_FORMAT":
                punishments = config.getBrandFormattingPunishments();
                break;
                
            case "BLOCKED_CHANNEL":
            case "CHANNEL_WHITELIST":
                punishments = config.getBlockedChannelsPunishments();
                break;
                
            case "BLOCKED_BRAND":
                punishments = config.getBlockedBrandsPunishments();
                break;
                
            case "GEYSER_SPOOF":
                punishments = config.getGeyserSpoofPunishments();
                break;
                
            default:
                // Fallback to global punishments
                punishments = config.getPunishments();
        }
        
        // If no specific punishments defined, fall back to global
        if (punishments.isEmpty()) {
            punishments = config.getPunishments();
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
}
