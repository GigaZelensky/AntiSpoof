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
        
        // Check for invalid brand formatting
        if (configManager.checkBrandFormatting() && hasInvalidFormatting(brand)) {
            return true;
        }
        
        // Check for brand blocking
        if (configManager.isBlockedBrandsEnabled()) {
            if (isBrandBlocked(brand)) {
                return true;
            }
        }
        
        // Vanilla with channels check
        if (claimsVanilla && hasChannels) {
            return true;
        }
        
        // Non-vanilla with channels check
        if (configManager.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            return true;
        }
        
        // Channel whitelist/blacklist check
        if (configManager.isBlockedChannelsEnabled()) {
            if (configManager.isChannelWhitelistEnabled()) {
                // Whitelist mode
                if (!checkChannelWhitelist(data.getChannels())) {
                    return true;
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean isBrandBlocked(String brand) {
        if (brand == null) return false;
        
        List<String> blockedBrands = configManager.getBlockedBrands();
        boolean exactMatch = configManager.isExactBrandMatchRequired();
        boolean whitelistMode = configManager.isBrandWhitelistEnabled();
        
        // No brands in the list means no blocks in blacklist mode, or no allowed brands in whitelist mode
        if (blockedBrands.isEmpty()) {
            return whitelistMode; // If whitelist is empty, everything is blocked
        }
        
        boolean isListed = false;
        
        for (String blockedBrand : blockedBrands) {
            boolean matches;
            
            if (exactMatch) {
                matches = brand.equalsIgnoreCase(blockedBrand);
            } else {
                matches = brand.toLowerCase().contains(blockedBrand.toLowerCase());
            }
            
            if (matches) {
                isListed = true;
                break;
            }
        }
        
        // In whitelist mode, being listed is good (not blocked)
        // In blacklist mode, being listed is bad (blocked)
        return whitelistMode ? !isListed : isListed;
    }
    
    private boolean checkChannelWhitelist(Set<String> playerChannels) {
        List<String> whitelistedChannels = configManager.getBlockedChannels(); // Reusing the values list
        boolean exactMatch = configManager.isExactChannelMatchRequired();
        boolean strictMode = configManager.isChannelWhitelistStrict();
        
        // If no channels are whitelisted, then fail if player has any channels
        if (whitelistedChannels.isEmpty()) {
            return playerChannels.isEmpty();
        }
        
        // SIMPLE mode: Player must have at least one of the whitelisted channels
        if (!strictMode) {
            for (String playerChannel : playerChannels) {
                for (String whitelistedChannel : whitelistedChannels) {
                    boolean matches;
                    
                    if (exactMatch) {
                        matches = playerChannel.equals(whitelistedChannel);
                    } else {
                        matches = playerChannel.contains(whitelistedChannel);
                    }
                    
                    if (matches) {
                        return true; // Pass if player has at least one whitelisted channel
                    }
                }
            }
            return false; // Fail if player has no whitelisted channels
        } 
        // STRICT mode: Player must have ONLY whitelisted channels
        else {
            // Check if every player channel is whitelisted
            for (String playerChannel : playerChannels) {
                boolean channelIsWhitelisted = false;
                
                for (String whitelistedChannel : whitelistedChannels) {
                    boolean matches;
                    
                    if (exactMatch) {
                        matches = playerChannel.equals(whitelistedChannel);
                    } else {
                        matches = playerChannel.contains(whitelistedChannel);
                    }
                    
                    if (matches) {
                        channelIsWhitelisted = true;
                        break;
                    }
                }
                
                if (!channelIsWhitelisted) {
                    return false; // Fail if any player channel is not whitelisted
                }
            }
            
            // All player's channels are whitelisted
            return true;
        }
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