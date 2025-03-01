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
import java.util.UUID;

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
        // Use putIfAbsent so we don't override data (like client brand) already set by PacketListener
        plugin.getPlayerDataMap().putIfAbsent(uuid, new PlayerData());
        
        int delay = config.getCheckDelay();
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPlayer(player), delay * 20L);
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up player data when they leave
        plugin.getPlayerDataMap().remove(event.getPlayer().getUniqueId());
    }

    private void checkPlayer(Player player) {
        if (!player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = plugin.getPlayerDataMap().get(uuid);
        if (data == null) return;

        boolean shouldPunish = false;
        String reason = "";

        // Brand checks
        if (data.getClientBrand() == null) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("No brand received for " + player.getName());
            }
            return;
        }
        
        if (config.checkBrandFormatting() && hasInvalidFormatting(data.getClientBrand())) {
            reason = "Invalid brand formatting";
            shouldPunish = true;
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = data.getClientBrand().equalsIgnoreCase("vanilla");
        
        if (claimsVanilla && hasChannels) {
            reason = "Vanilla client with plugin channels";
            shouldPunish = true;
        }
        else if (config.shouldBlockNonVanillaWithChannels() && claimsVanilla && hasChannels) {
            reason = "Non-vanilla client with channels";
            shouldPunish = true;
        }

        if (shouldPunish) {
            executePunishment(player, reason, data);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Checked player: " + player.getName());
            plugin.getLogger().info("Brand: " + data.getClientBrand());
            plugin.getLogger().info("Channels: " + String.join(", ", data.getChannels()));
        }
    }

    private boolean hasInvalidFormatting(String brand) {
        return brand.matches(".*[§&].*") || 
               !brand.matches("^[a-zA-Z0-9 _-]+$");
    }

    private void executePunishment(Player player, String reason, PlayerData data) {
        // Execute configured punishments
        for (String command : plugin.getConfigManager().getPunishments()) {
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", reason)
                                     .replace("%brand%", data.getClientBrand());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
        }

        // Send alerts
        String alert = plugin.getConfigManager().getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", data.getClientBrand())
                .replace("%reason%", reason);
        
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(ChatColor.translateAlternateColorCodes('&', alert)));
    }
}
