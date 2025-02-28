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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerJoinListener implements Listener {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    public PlayerJoinListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    // Added register method
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("antispoof.bypass")) return;
        
        UUID uuid = player.getUniqueId();
        playerDataMap.put(uuid, new PlayerData());
        
        int delay = config.getCheckDelay();
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPlayer(player), delay * 20L);
    }

    private void checkPlayer(Player player) {
        PlayerData data = playerDataMap.remove(player.getUniqueId());
        if (data == null) return;

        boolean shouldPunish = false;
        String reason = "";

        // Brand checks
        if (data.getClientBrand() == null) return;
        
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
        else if (config.shouldBlockNonVanillaWithChannels() && hasChannels) {
            reason = "Non-vanilla client with channels";
            shouldPunish = true;
        }

        if (shouldPunish) {
            executePunishment(player, reason, data);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Checking player: " + player.getName());
        }

        // In punishment block:
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Punishing " + player.getName() + " - Reason: " + reason);
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