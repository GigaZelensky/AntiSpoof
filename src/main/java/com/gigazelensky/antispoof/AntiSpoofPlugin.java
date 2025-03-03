package com.gigazelensky.antispoof;

import com.gigazelensky.antispoof.commands.AntiSpoofCommand;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.listeners.PacketListener;
import com.gigazelensky.antispoof.listeners.PlayerJoinListener;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiSpoofPlugin extends JavaPlugin {
    
    private ConfigManager configManager;
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<String, String> playerBrands = new HashMap<>();
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        
        // Initialize PacketEvents
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
            .reEncodeByDefault(false)
            .checkForUpdates(false);
        PacketEvents.getAPI().load();
        
        // Register plugin channels for client brand
        if (getServer().getBukkitVersion().contains("1.13") || 
            Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13) {
            getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", 
                (channel, player, message) -> {
                    // Brand message format: [length][brand]
                    String brand = new String(message).substring(1);
                    playerBrands.put(player.getName(), brand);
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }
                });
        } else {
            getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", 
                (channel, player, message) -> {
                    // Brand message format: [length][brand]
                    String brand = new String(message).substring(1);
                    playerBrands.put(player.getName(), brand);
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }
                });
        }
        
        // Register event listeners
        new PlayerJoinListener(this).register();
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener(this));
        
        // Register command with tab completion
        AntiSpoofCommand commandExecutor = new AntiSpoofCommand(this);
        getCommand("antispoof").setExecutor(commandExecutor);
        getCommand("antispoof").setTabCompleter(commandExecutor);
        
        PacketEvents.getAPI().init();
        
        getLogger().info("AntiSpoof v" + getDescription().getVersion() + " enabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public ConcurrentHashMap<UUID, PlayerData> getPlayerDataMap() {
        return playerDataMap;
    }

    public String getClientBrand(Player player) {
        if (player != null) {
            return playerBrands.get(player.getName());
        }
        return null;
    }
    
    public Map<String, String> getPlayerBrands() {
        return playerBrands;
    }
    
    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;
        
        String brand = getClientBrand(player);
        if (brand == null) return false;
        
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);
        if (data == null) return false;
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Checks for spoofing
        if (configManager.checkBrandFormatting() && hasInvalidFormatting(brand)) {
            return true;
        }
        
        if (claimsVanilla && hasChannels) {
            return true;
        }
        
        if (configManager.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            return true;
        }
        
        // Check for blocked channels
        if (configManager.isBlockedChannelsEnabled()) {
            String blockedChannel = findBlockedChannel(data.getChannels());
            if (blockedChannel != null) {
                return true;
            }
        }
        
        return false;
    }
    
    private String findBlockedChannel(Set<String> playerChannels) {
        List<String> blockedChannels = configManager.getBlockedChannels();
        boolean exactMatch = configManager.isExactChannelMatchRequired();
        
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

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        playerBrands.clear();
        getLogger().info("AntiSpoof disabled!");
    }
}