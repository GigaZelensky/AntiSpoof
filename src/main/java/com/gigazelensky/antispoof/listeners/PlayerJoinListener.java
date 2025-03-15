package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class PlayerJoinListener implements Listener {
   private final AntiSpoofPlugin plugin;
   private final ConfigManager config;
   private static boolean listenerRegistered = false;

   public PlayerJoinListener(AntiSpoofPlugin plugin) {
       this.plugin = plugin;
       this.config = plugin.getConfigManager();
       
       // Add plugin instance to debug log for verification
       if (config.isDebugMode()) {
           plugin.getLogger().info("[Debug] PlayerJoinListener created for plugin instance: " + plugin.toString());
       }
   }

   public void register() {
       // Ensure listener is only registered once
       if (!listenerRegistered) {
           Bukkit.getPluginManager().registerEvents(this, plugin);
           listenerRegistered = true;
           plugin.getLogger().info("PlayerJoinListener registered");
       } else if (config.isDebugMode()) {
           plugin.getLogger().info("[Debug] Avoided duplicate PlayerJoinListener registration");
       }
   }

   @EventHandler(priority = EventPriority.LOW)
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
   
   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerQuit(PlayerQuitEvent event) {
       UUID uuid = event.getPlayer().getUniqueId();
       plugin.getPlayerDataMap().remove(uuid);
       plugin.getPlayerBrands().remove(event.getPlayer().getName());
       
       // Clean up brand channel handler tracking
       if (plugin.getBrandChannelHandler() != null) {
           plugin.getBrandChannelHandler().playerDisconnected(uuid);
       }
       
       // Clean up packet listener tracking
       if (plugin.getPacketListener() != null) {
           plugin.getPacketListener().playerDisconnected(uuid);
       }
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
       
       // NOTE: Brand alerts are now handled directly by the BrandChannelHandler
       // No brand alerts sent from here anymore
       
       // List to collect all violations
       List<String> violations = new ArrayList<>();
       boolean shouldAlert = false;
       boolean shouldPunish = false;
       String primaryReason = "";
       String violationType = "";
       String violatedChannel = null;

       // Handle potential Geyser spoofing
       if (config.isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
           String reason = "Spoofing Geyser client";
           violations.add(reason);
           if (primaryReason.isEmpty()) {
               primaryReason = reason;
               violationType = "GEYSER_SPOOF";
           }
           shouldAlert = true;
           shouldPunish = config.shouldPunishGeyserSpoof();
       }
       
       // Check for brand blocking
       if (config.isBlockedBrandsEnabled()) {
           boolean brandBlocked = isBrandBlocked(brand);
           if (brandBlocked) {
               // Only add as a violation if count-as-flag is true
               if (config.shouldCountNonWhitelistedBrandsAsFlag()) {
                   String reason = "Blocked client brand: " + brand;
                   violations.add(reason);
                   if (primaryReason.isEmpty()) {
                       primaryReason = reason;
                       violationType = "BLOCKED_BRAND";
                   }
                   shouldAlert = true;
                   shouldPunish = config.shouldPunishBlockedBrands();
               }
           }
       }
       
       boolean hasChannels = !data.getChannels().isEmpty();
       boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
       
       // Vanilla client check
       if (config.isVanillaCheckEnabled() && 
           claimsVanilla && hasChannels) {
           String reason = "Vanilla client with plugin channels";
           violations.add(reason);
           if (primaryReason.isEmpty()) {
               primaryReason = reason;
               violationType = "VANILLA_WITH_CHANNELS";
           }
           shouldAlert = true;
           shouldPunish = config.shouldPunishVanillaCheck();
       }
       // Non-vanilla with channels check
       else if (config.shouldBlockNonVanillaWithChannels() && 
               !claimsVanilla && hasChannels) {
           String reason = "Non-vanilla client with channels";
           violations.add(reason);
           if (primaryReason.isEmpty()) {
               primaryReason = reason;
               violationType = "NON_VANILLA_WITH_CHANNELS";
           }
           shouldAlert = true;
           shouldPunish = config.shouldPunishNonVanillaCheck();
       }
       
       // Channel whitelist/blacklist check
       if (config.isBlockedChannelsEnabled()) {
           if (config.isChannelWhitelistEnabled()) {
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
                   shouldPunish = config.shouldPunishBlockedChannels();
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
                   shouldPunish = config.shouldPunishBlockedChannels();
               }
           }
       }

       // If player is a Bedrock player and we're in EXEMPT mode, don't process further
       if ((shouldAlert || shouldPunish) && isBedrockPlayer && config.isBedrockExemptMode()) {
           if (config.isDebugMode()) {
               plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                      " would be processed for: " + primaryReason + ", but is exempt");
           }
           return;
       }

       if (shouldAlert) {
           // Always send the alert if a violation is detected
           if (violations.size() > 0) {
               sendMultipleViolationsAlert(player, violations, brand, violatedChannel, violationType);
           }
           
           // Only execute punishment if enabled for this violation type
           if (shouldPunish) {
               executePunishment(player, primaryReason, brand, violationType, violatedChannel);
               data.setAlreadyPunished(true);
           }
       }

       if (plugin.getConfigManager().isDebugMode()) {
           plugin.getLogger().info("[Debug] Checked player: " + player.getName());
           plugin.getLogger().info("[Debug] Brand: " + brand);
           if (isBedrockPlayer) {
               plugin.getLogger().info("[Debug] Player is a Bedrock player");
           }
       }
   }

   private boolean isBrandBlocked(String brand) {
       if (brand == null) return false;
       
       return config.isBrandBlocked(brand);
   }
   
   private boolean checkChannelWhitelist(Set<String> playerChannels) {
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

   private String findBlockedChannel(Set<String> playerChannels) {
       for (String playerChannel : playerChannels) {
           if (config.matchesChannelPattern(playerChannel)) {
               return playerChannel;
           }
       }
       
       return null; // No blocked channels found
   }

   // Send alert message for multiple violations
   private void sendMultipleViolationsAlert(Player player, List<String> violations, String brand, String violatedChannel, String violationType) {
       UUID playerUUID = player.getUniqueId();
       
       // Join all reasons with commas
       String reasonsList = String.join(", ", violations);
       
       // Format the player alert message based on the number of violations
       String playerAlert;
       String consoleAlert;
       
       if (violations.size() > 1) {
           // Multiple violations format
           playerAlert = config.getMultipleFlagsMessage()
                   .replace("%player%", player.getName())
                   .replace("%brand%", brand != null ? brand : "unknown")
                   .replace("%reasons%", reasonsList);
           
           consoleAlert = config.getConsoleMultipleFlagsMessage()
                   .replace("%player%", player.getName())
                   .replace("%brand%", brand != null ? brand : "unknown")
                   .replace("%reasons%", reasonsList);
       } else {
           // Single violation format - select the appropriate message based on violation type
           switch(violationType) {
               case "VANILLA_WITH_CHANNELS":
                   playerAlert = config.getVanillaCheckAlertMessage();
                   consoleAlert = config.getVanillaCheckConsoleAlertMessage();
                   break;
                   
               case "NON_VANILLA_WITH_CHANNELS":
                   playerAlert = config.getNonVanillaCheckAlertMessage();
                   consoleAlert = config.getNonVanillaCheckConsoleAlertMessage();
                   break;
                   
               case "BLOCKED_CHANNEL":
               case "CHANNEL_WHITELIST":
                   playerAlert = config.getBlockedChannelsAlertMessage();
                   consoleAlert = config.getBlockedChannelsConsoleAlertMessage();
                   break;
                   
               case "BLOCKED_BRAND":
                   playerAlert = config.getBlockedBrandsAlertMessage();
                   consoleAlert = config.getBlockedBrandsConsoleAlertMessage();
                   break;
                   
               case "GEYSER_SPOOF":
                   playerAlert = config.getGeyserSpoofAlertMessage();
                   consoleAlert = config.getGeyserSpoofConsoleAlertMessage();
                   break;
                   
               default:
                   // Fallback to global messages
                   playerAlert = config.getAlertMessage();
                   consoleAlert = config.getConsoleAlertMessage();
           }
           
           // Replace placeholders
           playerAlert = playerAlert
                   .replace("%player%", player.getName())
                   .replace("%brand%", brand != null ? brand : "unknown")
                   .replace("%reason%", violations.get(0));
           
           consoleAlert = consoleAlert
                   .replace("%player%", player.getName())
                   .replace("%brand%", brand != null ? brand : "unknown")
                   .replace("%reason%", violations.get(0));
       }
       
       // Add channel info if available
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
       plugin.getDiscordWebhookHandler().sendAlert(player, violations.get(0), brand, violatedChannel, violations, violationType);
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