package com.gigazelensky.antispoof.commands;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class AntiSpoofCommand implements CommandExecutor, TabCompleter {
    private final AntiSpoofPlugin plugin;
    private final List<String> subcommands = Arrays.asList(
        "channels", "brand", "help", "reload", "check", "blockedchannels", "blockedbrands"
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
        String flagReason = null;
        
        if (brand == null) {
            sender.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.YELLOW + " has no client brand information yet.");
            return;
        }
        
        PlayerData data = plugin.getPlayerDataMap().get(target.getUniqueId());
        boolean hasChannels = data != null && !data.getChannels().isEmpty();
        
        // Display channels first regardless of spoof status
        if (hasChannels) {
            sender.sendMessage(ChatColor.GRAY + "Channels:");
            data.getChannels().forEach(channel -> sender.sendMessage(ChatColor.WHITE + channel));
        }
        
        // Determine reason for flagging
        if (isSpoofing) {
            // Check brand first
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked && plugin.getConfigManager().shouldCountNonWhitelistedBrandsAsFlag()) {
                    if (plugin.getConfigManager().isBrandWhitelistEnabled()) {
                        flagReason = "Client brand is not in whitelist";
                    } else {
                        flagReason = "Using blocked client brand";
                    }
                }
            }
            
            // Check channels if no brand reason was found
            if (flagReason == null && hasChannels && plugin.getConfigManager().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
                
                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode - check for missing required channels
                    if (whitelistMode.equals("STRICT")) {
                        List<String> missingChannels = new ArrayList<>();
                        
                        for (String whitelistedChannel : plugin.getConfigManager().getBlockedChannels()) {
                            boolean found = false;
                            for (String playerChannel : data.getChannels()) {
                                boolean matches = plugin.getConfigManager().matchesChannelPattern(playerChannel);
                                
                                if (matches) {
                                    found = true;
                                    break;
                                }
                            }
                            
                            if (!found) {
                                missingChannels.add(whitelistedChannel);
                            }
                        }
                        
                        if (!missingChannels.isEmpty()) {
                            flagReason = "Missing required channels: " + String.join(", ", missingChannels);
                        }
                    } else {
                        // Simple whitelist - check if all channels are outside whitelist
                        boolean hasWhitelistedChannel = false;
                        
                        for (String channel : data.getChannels()) {
                            boolean matches = plugin.getConfigManager().matchesChannelPattern(channel);
                            
                            if (matches) {
                                hasWhitelistedChannel = true;
                                break;
                            }
                        }
                        
                        if (!hasWhitelistedChannel) {
                            flagReason = "No whitelisted channels detected";
                        }
                    }
                } else {
                    // Blacklist mode - check for blocked channels
                    for (String channel : data.getChannels()) {
                        boolean blocked = plugin.getConfigManager().matchesChannelPattern(channel);
                        
                        if (blocked) {
                            flagReason = "Using blocked channel: " + channel;
                            break;
                        }
                    }
                }
            }
            
            // Default reason if none found
            if (flagReason == null) {
                flagReason = "Unknown reason (check configuration)";
            }
        }
        
        // Display player status and brand information after channels
        if (isSpoofing) {
            sender.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.RED + " has been flagged!");
            sender.sendMessage(ChatColor.RED + "Reason: " + flagReason);
            
            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked) {
                    sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.RED + brand + ChatColor.GRAY + " (Blocked)");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.GREEN + brand);
                }
            } else {
                sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.WHITE + brand);
            }
            
            sender.sendMessage(ChatColor.GRAY + "Has channels: " + (hasChannels ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            
            // Channel whitelist/blacklist checks
            if (hasChannels && plugin.getConfigManager().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.getConfigManager().getChannelWhitelistMode();
                
                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode
                    sender.sendMessage(ChatColor.GRAY + "Channel whitelist mode: " + 
                        ChatColor.WHITE + (whitelistMode.equals("STRICT") ? "Strict" : "Simple"));
                    
                    sender.sendMessage(ChatColor.GRAY + "Channel is whitelisted:");
                    
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
                                boolean matches = plugin.getConfigManager().matchesChannelPattern(playerChannel);
                                
                                if (matches) {
                                    found = true;
                                    break;
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
            
            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.getConfigManager().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.getConfigManager().isBrandBlocked(brand);
                
                if (brandBlocked) {
                    sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.RED + brand + ChatColor.GRAY + " (Blocked)");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.GREEN + brand);
                }
            } else {
                sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.WHITE + brand);
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
        sender.sendMessage(ChatColor.GRAY + "/antispoof blockedchannels " + ChatColor.WHITE + "- Show blocked channel config");
        sender.sendMessage(ChatColor.GRAY + "/antispoof blockedbrands " + ChatColor.WHITE + "- Show blocked brand config");
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
            
            if (args[0].equalsIgnoreCase("check")) {
                completions.add("*");
            }
            
            if (args[0].equalsIgnoreCase("channels") || 
                args[0].equalsIgnoreCase("brand") ||
                args[0].equalsIgnoreCase("check")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialArg))
                        .collect(Collectors.toList()));
            }
        }
        
        return completions;
    }
}
