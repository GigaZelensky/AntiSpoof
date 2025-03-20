package com.gigazelensky.antispoof.command;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles all plugin commands
 */
public class AntiSpoofCommand implements CommandExecutor, TabCompleter {
    private final AntiSpoofPlugin plugin;
    private final List<String> subcommands = Arrays.asList(
        "channels", "brand", "help", "reload", "check"
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
        
        // Check base permission
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
            
            sender.sendMessage(ChatColor.YELLOW + "Reloading AntiSpoof configuration...");
            
            // Reload configuration
            plugin.getConfigManager().reload();
            
            // Reload profiles
            plugin.getProfileManager().loadProfiles();
            
            // Re-register alert recipients
            plugin.getAlertService().registerAlertRecipients();
            
            sender.sendMessage(ChatColor.GREEN + "AntiSpoof configuration reloaded!");
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
        
        PlayerSession session = plugin.getPlayerSession(target.getUniqueId());
        if (session == null) {
            sender.sendMessage(ChatColor.YELLOW + "No data available for this player.");
            return true;
        }
        
        // Handle subcommands
        switch (subCommand) {
            case "channels":
                showChannels(sender, target, session);
                break;
            case "brand":
                showBrand(sender, target, session);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                showHelp(sender);
        }
        
        return true;
    }
    
    private void checkPlayer(CommandSender sender, Player target) {
        // Run an immediate check
        boolean isSpoofing = plugin.getDetectionService().checkPlayerImmediately(target);
        
        PlayerSession session = plugin.getPlayerSession(target.getUniqueId());
        if (session == null) {
            sender.sendMessage(ChatColor.RED + "No session data for " + target.getName());
            return;
        }
        
        String brand = session.getClientBrand();
        
        sender.sendMessage(ChatColor.AQUA + "=== AntiSpoof Check: " + target.getName() + " ===");
        sender.sendMessage(ChatColor.GRAY + "Client brand: " + ChatColor.WHITE + (brand != null ? brand : "unknown"));
        
        Set<String> channels = session.getChannels();
        if (channels.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Channels: " + ChatColor.YELLOW + "None");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Channels (" + channels.size() + "):");
            for (String channel : channels) {
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + channel);
            }
        }
        
        if (isSpoofing) {
            sender.sendMessage(ChatColor.RED + "Player is detected as spoofing!");
            
            // Show any pending violations
            List<Violation> violations = session.getPendingViolations();
            if (!violations.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Violations:");
                for (Violation v : violations) {
                    sender.sendMessage(ChatColor.RED + "- " + v.getReason());
                }
            }
        } else {
            sender.sendMessage(ChatColor.GREEN + "Player is not detected as spoofing.");
        }
        
        boolean isBedrockPlayer = plugin.getBedrockService().isBedrockPlayer(target);
        sender.sendMessage(ChatColor.GRAY + "Bedrock player: " + (isBedrockPlayer ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
    }
    
    private void checkAllPlayers(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== Players Currently Flagging ===");
        
        boolean foundSpoofing = false;
        int total = 0;
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            total++;
            if (plugin.getDetectionService().checkPlayerImmediately(player)) {
                foundSpoofing = true;
                String brand = plugin.getPlayerSession(player.getUniqueId()).getClientBrand();
                sender.sendMessage(ChatColor.RED + player.getName() + ChatColor.GRAY + 
                    " - Brand: " + ChatColor.WHITE + (brand != null ? brand : "unknown"));
            }
        }
        
        if (!foundSpoofing) {
            sender.sendMessage(ChatColor.GREEN + "No players are currently detected as spoofing.");
        }
        
        sender.sendMessage(ChatColor.GRAY + "Checked " + total + " online players.");
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== AntiSpoof Commands ===");
        sender.sendMessage(ChatColor.GRAY + "/antispoof channels <player> " + ChatColor.WHITE + "- Show player's plugin channels");
        sender.sendMessage(ChatColor.GRAY + "/antispoof brand <player> " + ChatColor.WHITE + "- Show player's client brand");
        sender.sendMessage(ChatColor.GRAY + "/antispoof check [player|*] " + ChatColor.WHITE + "- Check if player is spoofing");
        sender.sendMessage(ChatColor.GRAY + "/antispoof reload " + ChatColor.WHITE + "- Reload the plugin configuration");
        sender.sendMessage(ChatColor.GRAY + "/antispoof help " + ChatColor.WHITE + "- Show this help message");
    }
    
    private void showChannels(CommandSender sender, Player target, PlayerSession session) {
        sender.sendMessage(ChatColor.AQUA + "Channels for " + target.getName() + ":");
        
        Set<String> channels = session.getChannels();
        if (channels.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No channels registered.");
        } else {
            for (String channel : channels) {
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + channel);
            }
        }
    }
    
    private void showBrand(CommandSender sender, Player target, PlayerSession session) {
        String brand = session.getClientBrand();
        if (brand == null || brand.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " has no client brand information.");
        } else {
            sender.sendMessage(ChatColor.AQUA + "Client brand for " + target.getName() + ": " + 
                ChatColor.WHITE + brand);
            
            // Show which profile the brand matches
            String profileId = plugin.getProfileManager().findMatchingProfile(brand).getId();
            sender.sendMessage(ChatColor.GRAY + "Matched profile: " + ChatColor.WHITE + profileId);
        }
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // First argument - command name
            String partialArg = args[0].toLowerCase();
            for (String subcommand : subcommands) {
                if (subcommand.startsWith(partialArg)) {
                    completions.add(subcommand);
                }
            }
        } else if (args.length == 2) {
            // Second argument - might be player name
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