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
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.HashMap;

public class PlayerJoinListener implements Listener {
   private final AntiSpoofPlugin plugin;
   private final ConfigManager config;
   
   // Map to track which alert types have already been shown to players
   private final Map<UUID, Set<String>> alertTypesSent = new HashMap<>();
   
   // Track last alert time for rate limiting
   private final Map<UUID, Long> lastAlertTime = new HashMap<>();
   private static final long ALERT_COOLDOWN = 3000; // 3 seconds

   public PlayerJoinListener(AntiSpoofPlugin plugin) {
       this.plugin = plugin;
       this.config = plugin.getConfigManager();
   }

   public void register() {
       Bukkit.getPluginManager().registerEvents(this, plugin);
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
       UUID uuid = event.getPlayer().getUniqueId();
       plugin.getPlayerDataMap().remove(uuid);
       plugin.getPlayerBrands().remove(event.getPlayer().getName());
       
       // Clear the player's alert status in Discord webhook handler
       plugin.clearPlayerAlertStatus(uuid);
       
       // Clear local alert tracking
       alertTypesSent.remove(uuid);
       lastAlertTime.remove(uuid);
       
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
       
       // Send the initial channels webhook (will only send once)
       if (!data.getChannels().isEmpty()) {
           plugin.getDiscordWebhookHandler().sendInitialChannelsWebhook(player, data.getChannels());
       }
       
       // Always show client brand join message for operators if enabled
       // This happens regardless of any violations
       if (config.isBlockedBrandsEnabled()) {
           String alertType = "CLIENT_BRAND:" + brand;
           
           // Skip if already sent or on cooldown
           if (!hasAlertBeenSent(uuid, alertType) && !isOnCooldown(uuid)) {
               // Format the player alert message with placeholders
               String playerAlert = config.getBlockedBrandsAlertMessage()
                       .replace("%player%", player.getName())
                       .replace("%brand%", brand != null ? brand : "unknown");
               
               // Format the console alert message with placeholders
               String consoleAlert = config.getBlockedBrandsConsoleAlertMessage()
                       .replace("%player%", player.getName())
                       .replace("%brand%", brand != null ? brand : "unknown");
               
               // Convert color codes for player messages
               String coloredPlayerAlert = ChatColor.translateAlternateColorCodes('&', playerAlert);
               
               // Log to console directly using the console format (no need to strip colors)
               plugin.getLogger().info(consoleAlert);
               
               // Notify players with permission
               Bukkit.getOnlinePlayers().stream()
                       .filter(p -> p.hasPermission("antispoof.alerts"))
                       .forEach(p -> p.sendMessage(coloredPlayerAlert));
                       
               // Send the brand alert to Discord
               plugin.getDiscordWebhookHandler().sendAlert(
                   player, 
                   "joined using client brand: " + brand, 
                   brand,
                   null,
                   null
               );
               
               // Mark as sent and update last alert time
               markAlertSent(uuid, alertType);
               lastAlertTime.put(uuid, System.currentTimeMillis());
           }
        }
       
       // Track all violations to decide on punishment
       List<String> allViolations = new ArrayList<>();
       boolean shouldPunish = false;
       
       // Process each violation separately
       
       // Handle potential Geyser spoofing
       if (config.isPunishSpoofingGeyser() && plugin.isSpoofingGeyser(player)) {
           String reason = "Spoofing Geyser client";
           String violationType = "GEYSER_SPOOF";
           allViolations.add(reason);
           
           if (isBedrockPlayer && config.isBedrockExemptMode()) {
               if (config.isDebugMode()) {
                   plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                         " would be flagged for: " + reason + ", but is exempt");
               }
           } else {
               if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                   sendAlert(player, reason, brand, null, violationType);
                   markAlertSent(uuid, violationType);
                   lastAlertTime.put(uuid, System.currentTimeMillis());
               }
               
               if (config.shouldPunishGeyserSpoof()) {
                   shouldPunish = true;
               }
           }
       }
       
       // Check for brand blocking
       if (config.isBlockedBrandsEnabled()) {
           boolean brandBlocked = isBrandBlocked(brand);
           if (brandBlocked) {
               // Only add as a violation if count-as-flag is true
               if (config.shouldCountNonWhitelistedBrandsAsFlag()) {
                   String reason = "Blocked client brand: " + brand;
                   String violationType = "BLOCKED_BRAND";
                   allViolations.add(reason);
                   
                   if (isBedrockPlayer && config.isBedrockExemptMode()) {
                       if (config.isDebugMode()) {
                           plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                                 " would be flagged for: " + reason + ", but is exempt");
                       }
                   } else {
                       if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                           sendAlert(player, reason, brand, null, violationType);
                           markAlertSent(uuid, violationType);
                           lastAlertTime.put(uuid, System.currentTimeMillis());
                       }
                       
                       if (config.shouldPunishBlockedBrands()) {
                           shouldPunish = true;
                       }
                   }
               }
           }
       }
       
       boolean hasChannels = !data.getChannels().isEmpty();
       boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
       
       // Vanilla client check
       if (config.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
           String reason = "Vanilla client with plugin channels";
           String violationType = "VANILLA_WITH_CHANNELS";
           allViolations.add(reason);
           
           if (isBedrockPlayer && config.isBedrockExemptMode()) {
               if (config.isDebugMode()) {
                   plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                         " would be flagged for: " + reason + ", but is exempt");
               }
           } else {
               if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                   sendAlert(player, reason, brand, null, violationType);
                   markAlertSent(uuid, violationType);
                   lastAlertTime.put(uuid, System.currentTimeMillis());
               }
               
               if (config.shouldPunishVanillaCheck()) {
                   shouldPunish = true;
               }
           }
       }
       // Non-vanilla with channels check
       else if (config.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
           String reason = "Non-vanilla client with channels";
           String violationType = "NON_VANILLA_WITH_CHANNELS";
           allViolations.add(reason);
           
           if (isBedrockPlayer && config.isBedrockExemptMode()) {
               if (config.isDebugMode()) {
                   plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                         " would be flagged for: " + reason + ", but is exempt");
               }
           } else {
               if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                   sendAlert(player, reason, brand, null, violationType);
                   markAlertSent(uuid, violationType);
                   lastAlertTime.put(uuid, System.currentTimeMillis());
               }
               
               if (config.shouldPunishNonVanillaCheck()) {
                   shouldPunish = true;
               }
           }
       }
       
       // Channel whitelist/blacklist check
       if (config.isBlockedChannelsEnabled()) {
           if (config.isChannelWhitelistEnabled()) {
               // Whitelist mode
               boolean passesWhitelist = checkChannelWhitelist(data.getChannels());
               if (!passesWhitelist) {
                   String reason = "Client channels don't match whitelist";
                   String violationType = "CHANNEL_WHITELIST";
                   allViolations.add(reason);
                   
                   if (isBedrockPlayer && config.isBedrockExemptMode()) {
                       if (config.isDebugMode()) {
                           plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                                 " would be flagged for: " + reason + ", but is exempt");
                       }
                   } else {
                       if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                           sendAlert(player, reason, brand, null, violationType);
                           markAlertSent(uuid, violationType);
                           lastAlertTime.put(uuid, System.currentTimeMillis());
                       }
                       
                       if (config.shouldPunishBlockedChannels()) {
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
                   
                   if (isBedrockPlayer && config.isBedrockExemptMode()) {
                       if (config.isDebugMode()) {
                           plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                                 " would be flagged for: " + reason + ", but is exempt");
                       }
                   } else {
                       if (!hasAlertBeenSent(uuid, violationType) && !isOnCooldown(uuid)) {
                           sendAlert(player, reason, brand, blockedChannel, violationType);
                           markAlertSent(uuid, violationType);
                           lastAlertTime.put(uuid, System.currentTimeMillis());
                       }
                       
                       if (config.shouldPunishBlockedChannels()) {
                           shouldPunish = true;
                       }
                   }
               }
           }
       }

       // If we got here and player is a Bedrock player in EXEMPT mode, don't process further
       if (!allViolations.isEmpty() && isBedrockPlayer && config.isBedrockExemptMode()) {
           if (config.isDebugMode()) {
               plugin.getLogger().info("[Debug] Bedrock player " + player.getName() + 
                                     " would be processed for violations, but is exempt");
           }
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

       if (plugin.getConfigManager().isDebugMode()) {
           plugin.getLogger().info("[Debug] Checked player: " + player.getName());
           plugin.getLogger().info("[Debug] Brand: " + brand);
           if (isBedrockPlayer) {
               plugin.getLogger().info("[Debug] Player is a Bedrock player");
           }
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
   private void sendMultipleViolationsAlert(Player player, List<String> violations, String brand) {
       UUID playerUUID = player.getUniqueId();
       
       // Skip if on cooldown
       if (isOnCooldown(playerUUID)) {
           return;
       }
       
       // Skip if we've already sent a multiple violations alert
       if (hasAlertBeenSent(playerUUID, "MULTIPLE_VIOLATIONS")) {
           return;
       }
       
       // Join all reasons with commas
       String reasonsList = String.join(", ", violations);
       
       // Format the player alert message for multiple violations
       String playerAlert = config.getMultipleFlagsMessage()
               .replace("%player%", player.getName())
               .replace("%brand%", brand != null ? brand : "unknown")
               .replace("%reasons%", reasonsList);
       
       // Format the console alert message for multiple violations
       String consoleAlert = config.getConsoleMultipleFlagsMessage()
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
       
       // Mark as sent and update last alert time
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
       
       // Skip if already sent this alert type
       if (hasAlertBeenSent(playerUUID, violationType)) {
           return;
       }
       
       // Select the appropriate alert message based on violation type
       String alertTemplate;
       String consoleAlertTemplate;
       
       switch(violationType) {
           case "VANILLA_WITH_CHANNELS":
               alertTemplate = config.getVanillaCheckAlertMessage();
               consoleAlertTemplate = config.getVanillaCheckConsoleAlertMessage();
               break;
               
           case "NON_VANILLA_WITH_CHANNELS":
               alertTemplate = config.getNonVanillaCheckAlertMessage();
               consoleAlertTemplate = config.getNonVanillaCheckConsoleAlertMessage();
               break;
               
           case "CHANNEL_WHITELIST":
               alertTemplate = config.getBlockedChannelsAlertMessage();
               consoleAlertTemplate = config.getBlockedChannelsConsoleAlertMessage();
               break;
               
           case "BLOCKED_BRAND":
               alertTemplate = config.getBlockedBrandsAlertMessage();
               consoleAlertTemplate = config.getBlockedBrandsConsoleAlertMessage();
               break;
               
           case "GEYSER_SPOOF":
               alertTemplate = config.getGeyserSpoofAlertMessage();
               consoleAlertTemplate = config.getGeyserSpoofConsoleAlertMessage();
               break;
               
           default:
               if (violationType.startsWith("BLOCKED_CHANNEL:")) {
                   alertTemplate = config.getBlockedChannelsAlertMessage();
                   consoleAlertTemplate = config.getBlockedChannelsConsoleAlertMessage();
               } else {
                   // Fallback to global messages
                   alertTemplate = config.getAlertMessage();
                   consoleAlertTemplate = config.getConsoleAlertMessage();
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
       
       // Mark as sent and update last alert time
       markAlertSent(playerUUID, violationType);
       lastAlertTime.put(playerUUID, System.currentTimeMillis());
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