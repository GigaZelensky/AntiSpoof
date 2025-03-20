package com.gigazelensky.antispoof.api;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.ClientProfile;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * API for other plugins to interact with AntiSpoof
 */
public class AntiSpoofApi {
    
    private final AntiSpoofPlugin plugin;
    
    public AntiSpoofApi(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Gets the client brand for a player
     * 
     * @param player The player to check
     * @return The client brand, or null if not available
     */
    public String getClientBrand(Player player) {
        if (player == null) return null;
        
        PlayerSession session = plugin.getPlayerSession(player);
        if (session == null) return null;
        
        return session.getClientBrand();
    }
    
    /**
     * Gets the plugin channels registered by a player
     * 
     * @param player The player to check
     * @return Set of channel names, or empty set if none
     */
    public Set<String> getPlayerChannels(Player player) {
        if (player == null) return Set.of();
        
        PlayerSession session = plugin.getPlayerSession(player);
        if (session == null) return Set.of();
        
        return session.getChannels();
    }
    
    /**
     * Checks if a player is detected as spoofing
     * 
     * @param player The player to check
     * @return True if player is spoofing, false otherwise
     */
    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;
        
        return plugin.getDetectionService().checkPlayerImmediately(player);
    }
    
    /**
     * Gets the matched client profile for a player
     * 
     * @param player The player to check
     * @return The matched profile, or null if not available
     */
    public ClientProfile getPlayerProfile(Player player) {
        if (player == null) return null;
        
        PlayerSession session = plugin.getPlayerSession(player);
        if (session == null || session.getClientBrand() == null) return null;
        
        return plugin.getProfileManager().findMatchingProfile(session.getClientBrand());
    }
    
    /**
     * Gets violations for a player
     * 
     * @param player The player to check
     * @return List of violations, or empty list if none
     */
    public List<Violation> getPlayerViolations(Player player) {
        if (player == null) return List.of();
        
        PlayerSession session = plugin.getPlayerSession(player);
        if (session == null) return List.of();
        
        return session.getPendingViolations();
    }
    
    /**
     * Checks if a player is a Bedrock player
     * 
     * @param player The player to check
     * @return True if player is a Bedrock player, false otherwise
     */
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        
        return plugin.getBedrockService().isBedrockPlayer(player);
    }
    
    /**
     * Forces a detection check for a player
     * 
     * @param player The player to check
     */
    public void forceCheck(Player player) {
        if (player == null) return;
        
        plugin.getDetectionService().checkPlayer(player);
    }
}