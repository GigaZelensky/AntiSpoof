package com.gigazelensky.antispoof.commands;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.UUID;

public class AntiSpoofCommand implements CommandExecutor {
    private final AntiSpoofPlugin plugin;

    public AntiSpoofCommand(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("channels")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return true;
            }
            
            PlayerData data = plugin.getPlayerDataMap().get(target.getUniqueId());
            if (data == null) {
                sender.sendMessage(ChatColor.YELLOW + "No data available for this player.");
                return true;
            }
            
            sender.sendMessage(ChatColor.GOLD + "Channels for " + target.getName() + ":");
            data.getChannels().forEach(channel -> 
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + channel));
            return true;
        }
        return false;
    }
}