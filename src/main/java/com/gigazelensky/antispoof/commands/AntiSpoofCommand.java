package com.gigazelensky.antispoof.commands;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class AntiSpoofCommand implements CommandExecutor, TabCompleter {
    private final AntiSpoofPlugin plugin;
    private final List<String> subcommands = Arrays.asList(
        "channels", "brand", "help", "reload", "check", "blockedchannels", "blockedbrands", 
        "runcheck", "detectmods", "clearcooldowns", "translatekey"
    );

    public AntiSpoofCommand(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        
        // Check permission
        if (!sender.hasPermission("antispoof.command")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (subCommand.equals("help")) {
            showHelp(sender);
            return true;
        }
        
        if (subCommand.equals("reload")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to reload the plugin.");
                return true;
            }
            
            plugin.getConfigManager().reload();
            sender.sendMessage(ChatColor.GREEN + "AntiSpoof configuration reloaded!");
            return true;
        }
        
        // Command to show blocked channels
        if (subCommand.equals("blockedchannels")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            showBlockedChannels(sender);
            return true;
        }
        
        // Command to show blocked brands
        if (subCommand.equals("blockedbrands")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            showBlockedBrands(sender);
            return true;
        }
        
        // Handle runcheck command
        if (subCommand.equals("runcheck")) {
            handleRunCheckCommand(sender, args);
            return true;
        }
        
        // Handle check command
        if (subCommand.equals("check")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            if (args.length < 2) {
                // Check all online players
                checkAllPlayers(sender);
                return true;
            }
            
            // Check specific player
            String playerName = args[1];
            if (playerName.equals("*")) {
                checkAllPlayers(sender);
                return true;
            }
            
            Player target = Bukkit.getPlayer(playerName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return true;
            }
            
            checkPlayer(sender, target);
            return true;
        }

        // Handle detectmods command
        if (subCommand.equals("detectmods")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            handleDetectModsCommand(sender, args);
            return true;
        }
        
        // Handle translatekey command
        if (subCommand.equals("translatekey")) {
            handleTranslateKeyCommand(sender, args);
            return true;
        }
        
        // Handle clearcooldowns command
        if (subCommand.equals("clearcooldowns")) {
            if (!sender.hasPermission("antispoof.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            handleClearCooldownsCommand(sender, args);
            return true;
        }
        
        // Commands that require a player argument
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " " + subCommand + " <player>");
            return true;
        }
        
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return true;
        }
        
        UUID targetUUID = target.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(targetUUID);
        if (data == null) {
            sender.sendMessage(ChatColor.YELLOW + "No data available for this player.");
            return true;
        }
        
        // Handle subcommands
        switch (subCommand) {
            case "channels":
                showChannels(sender, target, data);
                break;
            case "brand":
                showBrand(sender, target);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                showHelp(sender);
        }
        
        return true;
    }
    
    /**
     * Handles the translatekey command to test a specific translation key against a player
     * @param sender The command sender
     * @param args The command arguments
     */
    private void handleTranslateKeyCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antispoof.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return;
        }
        
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /antispoof translatekey <player> <key>");
            return;
        }
        
        String playerName = args[1];
        String translationKey = args[2];
        
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }
        
        // Check if translation detection is enabled
        if (!plugin.getConfigManager().isTranslationDetectionEnabled()) {
            sender.sendMessage(ChatColor.RED + "Translation key detection is disabled in the configuration.");
            return;
        }
        
        sender.sendMessage(ChatColor.AQUA + "Testing translation key '" + translationKey + "' on player " + target.getName() + "...");
        
        // Scan the player for the specific translation key
        plugin.getTranslationKeyDetector().scanPlayerForKey(target, translationKey);
        
        sender.sendMessage(ChatColor.GREEN + "Test initialized. Results will be reported via alerts if mod is detected.");
    }
    
    /**
     * Handles the clearCooldowns command to reset detection cooldowns
     * @param sender The command sender
     * @param args The command arguments
     */
    private void handleClearCooldownsCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /antispoof clearcooldowns <player|*>");
            return;
        }
        
        String playerName = args[1];
        
        if (playerName.equals("*")) {
            // Clear all cooldowns
            plugin.getTranslationKeyDetector().clearAllCooldowns();
            sender.sendMessage(ChatColor.GREEN + "Cleared detection cooldowns for all players.");
            return;
        }
        
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }
        
        plugin.getTranslationKeyDetector().clearCooldown(target.getUniqueId());
        sender.sendMessage(ChatColor.GREEN + "Cleared detection cooldown for " + target.getName() + ".");
    }
    
    /**
     * Handles the detectmods command to trigger translation key detection
     * @param sender The command sender
     * @param args The command arguments
     */
    private void handleDetectModsCommand(CommandSender sender, String[] args) {
        if (!plugin.getConfigManager().isTranslationDetectionEnabled()) {
            sender.sendMessage(ChatColor.RED + "Translation key detection is disabled in the configuration.");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /antispoof detectmods <player>");
            return;
        }
        
        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);
        
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }
        
        // Check if already detected mods for this player
        Set<String> detectedMods = plugin.getTranslationKeyDetector().getDetectedMods(target.getUniqueId());
        
        if (!detectedMods.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "Previously detected mods for " + target.getName() + ":");
            for (String mod : detectedMods) {
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + mod);
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "No mods previously detected for " + target.getName() + ".");
        }
        
        // Run a new scan - passing true to forceScan to bypass cooldown
        sender.sendMessage(ChatColor.AQUA + "Starting new mod detection scan for " + target.getName() + "...");
        plugin.getTranslationKeyDetector().scanPlayer(target, true);
        sender.sendMessage(ChatColor.GREEN + "Detection scan initiated. Results will be reported via alerts if mods are found.");
    }
    
    private void handleRunCheckCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antispoof.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return;
        }

        if (args.length < 2) {
            // Run check for all online players
            sender.sendMessage(ChatColor.AQUA + "Running check for all online players...");
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getDetectionManager().checkPlayerAsync(player, false, true);
                count++;
            }
            sender.sendMessage(ChatColor.GREEN + "Check triggered for " + count + " players.");
            sender.sendMessage(ChatColor.YELLOW + "Note: This only re-analyzes existing data, players might need to rejoin for fresh data.");
            return;
        }

        // Check specific player
        String playerName = args[1];
        if (playerName.equals("*")) {
            // Also run for all players if * is specified
            sender.sendMessage(ChatColor.AQUA + "Running check for all online players...");
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getDetectionManager().checkPlayerAsync(player, false, true);
                count++;
            }
            sender.sendMessage(ChatColor.GREEN + "Check triggered for " + count + " players.");
            sender.sendMessage(ChatColor.YELLOW + "Note: This only re-analyzes existing data, players might need to rejoin for fresh data.");
            return;
        }

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "Running check for player " + target.getName() + "...");
        plugin.getDetectionManager().checkPlayerAsync(target, false, true);
        sender.sendMessage(ChatColor.GREEN + "Check triggered for " + target.getName() + ".");
        sender.sendMessage(ChatColor.YELLOW + "Note: This only re-analyzes existing data, players might need to rejoin for fresh data.");
    }
    
    private void showBlockedBrands(CommandSender sender) {
        boolean enabled = plugin.getConfigManager().isBlockedBrandsEnabled();
        boolean whitelistMode = plugin.getConfigManager().isBrandWhitelistEnabled();
        List<String> blockedBrands = plugin.getConfigManager().getBlockedBrands();
        
        sender.sendMessage(ChatColor.AQUA + "=== Blocked Brands Configuration ===");
        sender.sendMessage(ChatColor.GRAY + "Enabled: " + (enabled ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
        sender.sendMessage(ChatColor.GRAY + "Match Type: " + ChatColor.WHITE + "Regex Pattern");
        sender.sendMessage(ChatColor.GRAY + "Mode: " + (whitelistMode ? ChatColor.WHITE + "Whitelist" : ChatColor.WHITE + "Blacklist"));
        
        if (blockedBrands.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No brands are currently " + (whitelistMode ? "whitelisted" : "blocked") + ".");
        } else {
            sender.sendMessage(ChatColor.AQUA + (whitelistMode ? "Whitelisted" : "Blocked") + " Brands:");
            blockedBrands.forEach(brand -> 
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + brand));
        }
    }
    
    private void showBlockedChannels(CommandSender sender) {
        boolean enabled = plugin.getConfigManager().isBlockedChannelsEnabled();
        String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
        List<String> blockedChannels = plugin.getConfigManager().getBlockedChannels();
        
        sender.sendMessage(ChatColor.AQUA + "=== Blocked Channels Configuration ===");
        sender.sendMessage(ChatColor.GRAY + "Enabled: " + (enabled ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
        sender.sendMessage(ChatColor.GRAY + "Match Type: " + ChatColor.WHITE + "Regex Pattern");
        
        String modeDisplay;
        if (whitelistMode.equals("FALSE")) {
            modeDisplay = "Blacklist";
        } else if (whitelistMode.equals("SIMPLE")) {
            modeDisplay = "Whitelist (Simple)";
        } else {
            modeDisplay = "Whitelist (Strict)";
        }
        sender.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + modeDisplay);
        
        if (blockedChannels.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No channels are currently " + 
                (whitelistMode.equals("FALSE") ? "blocked" : "whitelisted") + ".");
        } else {
            sender.sendMessage(ChatColor.AQUA + (whitelistMode.equals("FALSE") ? "Blocked" : "Whitelisted") + " Channels:");
            blockedChannels.forEach(channel -> 
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + channel));
        }
    }
    
    private void checkPlayer(CommandSender sender, Player target) {
        boolean isSpoofing = plugin.isPlayerSpoofing(target);
        String brand = plugin.getClientBrand(target);
        List<String> flagReasons = new ArrayList<>();
        
        if (brand == null) {
            sender.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.YELLOW + " has no client brand information yet.");
            return;
        }
        
        PlayerData data = plugin.getPlayerDataMap().get(target.getUniqueId());
        boolean hasChannels = data != null && !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Display channels first regardless of spoof status
        if (hasChannels) {
            sender.sendMessage(ChatColor.GRAY + "Channels:");
            data.getChannels().forEach(channel -> sender.sendMessage(ChatColor.WHITE + channel));
        }
        
        // Check for detected mods via translation keys
        Set<String> detectedMods = plugin.getTranslationKeyDetector().getDetectedMods(target.getUniqueId());
        if (!detectedMods.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "Detected Mods (via translation keys):");
            for (String mod : detectedMods) {
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + mod);
            }
        }
        
        // Determine all reasons for flagging
        if (isSpoofing) {
            // Check for vanilla client with channels
            if (claimsVanilla && hasChannels && plugin.getConfigManager().isVanillaCheckEnabled()) {
                flagReasons.add("Vanilla client with plugin channels");
            }
            
            // Check for Geyser spoofing
            if (plugin.isSpoofingGeyser(target)) {
                flagReasons.add("Spoofing Geyser client");
            }
            
            // Check for non-vanilla with channels
            if (!claimsVanilla && hasChannels && plugin.getConfigManager().shouldBlockNonVanillaWithChannels()) {
                flagReasons.add("Non-vanilla client with channels");
            }
            
            // Check for blocked brand
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked && plugin.getConfigManager().shouldCountNonWhitelistedBrandsAsFlag()) {
                    if (plugin.getConfigManager().isBrandWhitelistEnabled()) {
                        flagReasons.add("Client brand is not in whitelist");
                    } else {
                        flagReasons.add("Using blocked client brand");
                    }
                }
            }
            
            // Check channel whitelist/blacklist
            if (hasChannels && plugin.getConfigManager().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
                
                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode
                    boolean passesWhitelist = plugin.getDetectionManager().checkChannelWhitelist(data.getChannels());
                    if (!passesWhitelist) {
                        if (whitelistMode.equals("STRICT")) {
                            // Get missing channels for strict mode
                            List<String> missingChannels = new ArrayList<>();
                            for (String whitelistedChannel : plugin.getConfigManager().getBlockedChannels()) {
                                boolean found = false;
                                for (String playerChannel : data.getChannels()) {
                                    try {
                                        if (playerChannel.matches(whitelistedChannel)) {
                                            found = true;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // If regex fails, try direct match
                                        if (playerChannel.equals(whitelistedChannel)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                                
                                if (!found) {
                                    missingChannels.add(whitelistedChannel);
                                }
                            }
                            
                            if (!missingChannels.isEmpty()) {
                                flagReasons.add("Missing required channels: " + String.join(", ", missingChannels));
                            } else {
                                flagReasons.add("Channels don't match whitelist requirements");
                            }
                        } else {
                            flagReasons.add("No whitelisted channels detected");
                        }
                    }
                } else {
                    // Blacklist mode - check for blocked channels
                    String blockedChannel = plugin.getDetectionManager().findBlockedChannel(data.getChannels());
                    if (blockedChannel != null) {
                        flagReasons.add("Using blocked channel: " + blockedChannel);
                    }
                }
            }
            
            // Check for missing required channels for their brand
            String matchedBrand = plugin.getConfigManager().getMatchingClientBrand(brand);
            if (matchedBrand != null && hasChannels) {
                ConfigManager.ClientBrandConfig brandConfig = plugin.getConfigManager().getClientBrandConfig(matchedBrand);
                
                if (!brandConfig.getRequiredChannels().isEmpty()) {
                    // Look for missing required channels
                    List<String> missingRequiredChannels = new ArrayList<>();
                    for (String requiredChannel : brandConfig.getRequiredChannelStrings()) {
                        boolean found = false;
                        for (String playerChannel : data.getChannels()) {
                            try {
                                if (playerChannel.matches(requiredChannel)) {
                                    found = true;
                                    break;
                                }
                            } catch (Exception e) {
                                // If regex fails, try simple contains check
                                String simplePattern = requiredChannel
                                    .replace("(?i)", "")
                                    .replace(".*", "")
                                    .replace("^", "")
                                    .replace("$", "");
                                if (playerChannel.toLowerCase().contains(simplePattern.toLowerCase())) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) {
                            missingRequiredChannels.add(requiredChannel);
                        }
                    }
                    
                    if (!missingRequiredChannels.isEmpty()) {
                        flagReasons.add("Missing required channels for " + matchedBrand + ": " + String.join(", ", missingRequiredChannels));
                    }
                }
            }
            
            // Add default reason if none found
            if (flagReasons.isEmpty()) {
                flagReasons.add("Unknown reason (check configuration)");
            }
        }
        
        // Display player status and brand information after channels
        if (isSpoofing) {
            sender.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.RED + " has been flagged!");
            
            // Show client brand first
            sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.WHITE + brand);
            
            // Show all violations
            sender.sendMessage(ChatColor.RED + "Violations detected (" + flagReasons.size() + "):");
            for (String reason : flagReasons) {
                sender.sendMessage(ChatColor.RED + "• " + reason);
            }
            
            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked) {
                    sender.sendMessage(ChatColor.GRAY + "Brand status: " + ChatColor.RED + "Blocked");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Brand status: " + ChatColor.GREEN + "Allowed");
                }
            }
            
            sender.sendMessage(ChatColor.GRAY + "Has channels: " + (hasChannels ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            
            // Channel whitelist/blacklist checks
            if (hasChannels && plugin.getConfigManager().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
                
                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode
                    sender.sendMessage(ChatColor.GRAY + "Channel whitelist mode: " + 
                        ChatColor.WHITE + (whitelistMode.equals("STRICT") ? "Strict" : "Simple"));
                    
                    sender.sendMessage(ChatColor.GRAY + "Channel whitelist status:");
                    
                    // First, list all player channels and if they're whitelisted
                    for (String channel : data.getChannels()) {
                        boolean whitelisted = plugin.getConfigManager().matchesChannelPattern(channel);
                        
                        if (whitelisted) {
                            sender.sendMessage(ChatColor.GREEN + channel);
                        } else {
                            sender.sendMessage(ChatColor.RED + channel + ChatColor.GRAY + " (not in whitelist)");
                        }
                    }
                    
                    // If in STRICT mode, also list any missing whitelisted channels
                    if (whitelistMode.equals("STRICT")) {
                        List<String> missingWhitelistedChannels = new ArrayList<>();
                        
                        for (String whitelistedChannel : plugin.getConfigManager().getBlockedChannels()) {
                            boolean found = false;
                            for (String playerChannel : data.getChannels()) {
                                try {
                                    if (playerChannel.matches(whitelistedChannel)) {
                                        found = true;
                                        break;
                                    }
                                } catch (Exception e) {
                                    // If regex fails, try direct match
                                    if (playerChannel.equals(whitelistedChannel)) {
                                        found = true;
                                        break;
                                    }
                                }
                            }
                            
                            if (!found) {
                                missingWhitelistedChannels.add(whitelistedChannel);
                            }
                        }
                        
                        if (!missingWhitelistedChannels.isEmpty()) {
                            sender.sendMessage(ChatColor.GRAY + "Missing required channels:");
                            for (String missingChannel : missingWhitelistedChannels) {
                                sender.sendMessage(ChatColor.RED + missingChannel);
                            }
                        }
                    }
                } else {
                    // Blacklist mode - Collect all blocked channels first, then display them together
                    List<String> blockedChannels = new ArrayList<>();
                    
                    for (String channel : data.getChannels()) {
                        boolean blocked = plugin.getConfigManager().matchesChannelPattern(channel);
                        
                        if (blocked) {
                            blockedChannels.add(channel);
                        }
                    }
                    
                    // Display all blocked channels under one header
                    if (!blockedChannels.isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "Blocked channel detected:");
                        for (String channel : blockedChannels) {
                            sender.sendMessage(ChatColor.RED + channel);
                        }
                    }
                }
            }
        } else {
            sender.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.GREEN + " does not appear to be spoofing.");
            
            // Show client brand
            sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.WHITE + brand);
            
            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked) {
                    sender.sendMessage(ChatColor.GRAY + "Brand status: " + ChatColor.RED + "Blocked" + 
                        ChatColor.GRAY + " (but not counting as flag)");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Brand status: " + ChatColor.GREEN + "Allowed");
                }
            }
            
            sender.sendMessage(ChatColor.GRAY + "Has channels: " + (hasChannels ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
        }
    }
    
    private void checkAllPlayers(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== Players Currently Flagging ===");
        
        boolean foundSpoofing = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isPlayerSpoofing(player)) {
                foundSpoofing = true;
                String brand = plugin.getClientBrand(player);
                sender.sendMessage(ChatColor.RED + player.getName() + ChatColor.GRAY + 
                    " - Brand: " + ChatColor.WHITE + (brand != null ? brand : "unknown"));
            }
        }
        
        if (!foundSpoofing) {
            sender.sendMessage(ChatColor.GREEN + "No players are currently detected as spoofing.");
        }
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== AntiSpoof Commands ===");
        sender.sendMessage(ChatColor.GRAY + "/antispoof channels <player> " + ChatColor.WHITE + "- Show player's plugin channels");
        sender.sendMessage(ChatColor.GRAY + "/antispoof brand <player> " + ChatColor.WHITE + "- Show player's client brand");
        sender.sendMessage(ChatColor.GRAY + "/antispoof check [player|*] " + ChatColor.WHITE + "- Check if player is spoofing");
        sender.sendMessage(ChatColor.GRAY + "/antispoof runcheck [player|*] " + ChatColor.WHITE + "- Re-run checks on player(s)");
        sender.sendMessage(ChatColor.GRAY + "/antispoof blockedchannels " + ChatColor.WHITE + "- Show blocked channel config");
        sender.sendMessage(ChatColor.GRAY + "/antispoof blockedbrands " + ChatColor.WHITE + "- Show blocked brand config");
        sender.sendMessage(ChatColor.GRAY + "/antispoof detectmods <player> " + ChatColor.WHITE + "- Scan player for mods using translation keys");
        sender.sendMessage(ChatColor.GRAY + "/antispoof translatekey <player> <key> " + ChatColor.WHITE + "- Test a specific translation key on a player");
        sender.sendMessage(ChatColor.GRAY + "/antispoof clearcooldowns <player|*> " + ChatColor.WHITE + "- Clear detection cooldowns");
        sender.sendMessage(ChatColor.GRAY + "/antispoof reload " + ChatColor.WHITE + "- Reload the plugin configuration");
        sender.sendMessage(ChatColor.GRAY + "/antispoof help " + ChatColor.WHITE + "- Show this help message");
    }
    
    private void showChannels(CommandSender sender, Player target, PlayerData data) {
        sender.sendMessage(ChatColor.AQUA + "Channels for " + target.getName() + ":");
        
        if (data.getChannels().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No channels registered.");
        } else {
            String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
            boolean isWhitelist = !whitelistMode.equals("FALSE");
            
            data.getChannels().forEach(channel -> {
                boolean isListed = plugin.getConfigManager().matchesChannelPattern(channel);
                
                if (isListed) {
                    if (isWhitelist) {
                        sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.GREEN + channel + ChatColor.GRAY + " (whitelisted)");
                    } else {
                        sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.RED + channel + ChatColor.GRAY + " (blocked)");
                    }
                } else {
                    if (isWhitelist) {
                        sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.RED + channel + ChatColor.GRAY + " (not whitelisted)");
                    } else {
                        sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + channel);
                    }
                }
            });
        }
    }
    
    private void showBrand(CommandSender sender, Player target) {
        String brand = plugin.getClientBrand(target);
        if (brand == null || brand.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " has no client brand information.");
        } else {
            // Check if the brand is in the whitelist/blacklist and display with appropriate color
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                boolean whitelistMode = plugin.getConfigManager().isBrandWhitelistEnabled();
                
                if (brandBlocked) {
                    sender.sendMessage(ChatColor.AQUA + "Client brand for " + target.getName() + ": " + 
                        ChatColor.RED + brand + ChatColor.GRAY + " (Blocked)");
                    
                    if (whitelistMode) {
                        sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.RED + "Not in whitelist");
                    } else {
                        sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.RED + "Blocked");
                    }
                } else {
                    sender.sendMessage(ChatColor.AQUA + "Client brand for " + target.getName() + ": " + 
                        ChatColor.GREEN + brand);
                    
                    if (whitelistMode) {
                        sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.GREEN + "In whitelist");
                    } else {
                        sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.GREEN + "Allowed");
                    }
                }
            } else {
                sender.sendMessage(ChatColor.AQUA + "Client brand for " + target.getName() + ": " + 
                    ChatColor.WHITE + brand);
            }
            
            // Show detected mods via translation keys
            Set<String> detectedMods = plugin.getTranslationKeyDetector().getDetectedMods(target.getUniqueId());
            if (!detectedMods.isEmpty()) {
                sender.sendMessage(ChatColor.AQUA + "Detected Mods (via translation keys):");
                for (String mod : detectedMods) {
                    sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + mod);
                }
            }
        }
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String partialArg = args[0].toLowerCase();
            for (String subcommand : subcommands) {
                if (subcommand.startsWith(partialArg)) {
                    completions.add(subcommand);
                }
            }
        } else if (args.length == 2) {
            String partialArg = args[1].toLowerCase();
            
            if (args[0].equalsIgnoreCase("check") || 
                args[0].equalsIgnoreCase("runcheck") ||
                args[0].equalsIgnoreCase("clearcooldowns")) {
                completions.add("*");
            }
            
            if (args[0].equalsIgnoreCase("channels") || 
                args[0].equalsIgnoreCase("brand") ||
                args[0].equalsIgnoreCase("check") ||
                args[0].equalsIgnoreCase("detectmods") ||
                args[0].equalsIgnoreCase("runcheck") ||
                args[0].equalsIgnoreCase("clearcooldowns") ||
                args[0].equalsIgnoreCase("translatekey")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialArg))
                        .collect(Collectors.toList()));
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("translatekey")) {
                String partialArg = args[2].toLowerCase();
                // Add some common translation keys for convenience
                List<String> commonKeys = Arrays.asList(
                    "sodium.option_impact.low",
                    "key.wurst.zoom",
                    "key.freecam.toggle",
                    "xray.config.toggle",
                    "key.meteor-client.open-gui",
                    "litematica.gui.button.change_menu.to_main_menu",
                    "gui.xaero_open_map",
                    "tweakeroo.gui.button.config_gui.tweaks"
                );
                
                for (String key : commonKeys) {
                    if (key.toLowerCase().contains(partialArg)) {
                        completions.add(key);
                    }
                }
                
                // Also suggest any translation keys from the config if available
                Set<String> configKeys = plugin.getTranslationKeyDetector().getAllTranslationKeys();
                if (configKeys != null) {
                    for (String key : configKeys) {
                        if (key.toLowerCase().contains(partialArg)) {
                            completions.add(key);
                        }
                    }
                }
            }
        }
        
        return completions;
    }
}