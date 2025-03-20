package com.gigazelensky.antispoof.service;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Handles Bedrock player detection
 */
public class BedrockService {
    private final AntiSpoofPlugin plugin;
    private FloodgateApi floodgateApi;
    
    // Cache Bedrock status for better performance
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();
    
    public BedrockService(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        initFloodgateApi();
    }
    
    /**
     * Initializes Floodgate API if available
     */
    private void initFloodgateApi() {
        try {
            if (plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) {
                floodgateApi = FloodgateApi.getInstance();
                plugin.getLogger().info("Successfully hooked into Floodgate API for Bedrock detection");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to hook into Floodgate API: " + e.getMessage());
            floodgateApi = null;
        }
    }
    
    /**
     * Checks if a player is a Bedrock player
     */
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        
        UUID uuid = player.getUniqueId();
        
        // Check cache first
        if (bedrockCache.containsKey(uuid)) {
            return bedrockCache.get(uuid);
        }
        
        boolean bedrock = checkBedrockPlayer(player);
        bedrockCache.put(uuid, bedrock);
        return bedrock;
    }
    
    /**
     * Performs the actual Bedrock check
     */
    private boolean checkBedrockPlayer(Player player) {
        // Try Floodgate API first if available
        if (floodgateApi != null) {
            try {
                if (floodgateApi.isFloodgatePlayer(player.getUniqueId())) {
                    if (plugin.getConfigManager().isDebugMode()) {
                        plugin.getLogger().info("[Debug] Player " + player.getName() + 
                                             " identified as Bedrock player via Floodgate API");
                    }
                    return true;
                }
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().warning("[Debug] Error checking Floodgate API for " + 
                                           player.getName() + ": " + e.getMessage());
                }
            }
        }
        
        // Fall back to prefix check
        if (plugin.getConfigManager().isBedrockPrefixCheckEnabled()) {
            String prefix = plugin.getConfigManager().getBedrockPrefix();
            if (player.getName().startsWith(prefix)) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Debug] Player " + player.getName() + 
                                         " identified as Bedrock player via prefix check");
                }
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Clears the Bedrock cache for a player
     */
    public void clearCache(UUID uuid) {
        if (uuid != null) {
            bedrockCache.remove(uuid);
        }
    }
    
    /**
     * Clears the entire Bedrock cache
     */
    public void clearCache() {
        bedrockCache.clear();
    }
}