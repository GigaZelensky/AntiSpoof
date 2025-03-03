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
        plugin.getPlayerDataMap().putIfAbsent(uuid, new PlayerData());
        
        int delay = config.getCheckDelay();
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPlayer(player), delay * 20L);
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

        String brand = plugin.getClientBrand(player);
        if (brand == null) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] No brand available for " + player.getName());
            }
            return;
        }
        
        boolean shouldPunish = false;
        String reason = "";

        if (config.checkBrandFormatting() && hasInvalidFormatting(brand)) {
            reason = "Invalid brand formatting";
            shouldPunish = true;
        }
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        if (claimsVanilla && hasChannels) {
            reason = "Vanilla client with plugin channels";
            shouldPunish = true;
        }
        else if (config.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            reason = "Non-vanilla client with channels";
            shouldPunish = true;
        }

        if (shouldPunish) {
            executePunishment(player, reason, brand);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Checked player: " + player.getName());
            plugin.getLogger().info("[Debug] Brand: " + brand);
            plugin.getLogger().info("[Debug] Channels: " + String.join(", ", data.getChannels()));
        }
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
        
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antispoof.alerts"))
                .forEach(p -> p.sendMessage(ChatColor.translateAlternateColorCodes('&', alert)));
    }
}