package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.ChannelRequirement;
import com.gigazelensky.antispoof.model.ClientProfile;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core detection logic for the plugin
 */
public class DetectionService {
    private final AntiSpoofPlugin plugin;
    
    // Track cooldowns for player checks
    private final Set<UUID> inProgressChecks = ConcurrentHashMap.newKeySet();
    
    public DetectionService(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Check a player for client spoofing
     */
    public void checkPlayer(Player player) {
        if (player == null || !player.isOnline() || player.hasPermission("antispoof.bypass")) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        
        // Skip if check is already in progress
        if (inProgressChecks.contains(uuid)) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Skipping check for " + player.getName() + " - already in progress");
            }
            return;
        }
        
        // Mark check as in progress
        inProgressChecks.add(uuid);
        
        // Get player session
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) {
            // Create a new session if one doesn't exist
            plugin.registerPlayerSession(player);
            session = plugin.getPlayerSession(uuid);
        }
        
        // Skip if already punished
        if (session.isAlreadyPunished()) {
            inProgressChecks.remove(uuid);
            return;
        }
        
        // Get client brand
        String brand = session.getClientBrand();
        
        try {
            // Check for Bedrock player
            boolean isBedrockPlayer = plugin.getBedrockService().isBedrockPlayer(player);
            
            // Exempt Bedrock players
            if (isBedrockPlayer) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Debug] Skipping check for Bedrock player " + player.getName());
                }
                inProgressChecks.remove(uuid);
                return;
            }
            
            // Prepare to collect violations
            List<Violation> violations = new ArrayList<>();
            
            // Special case: No brand
            if (brand == null) {
                ClientProfile noBrandProfile = plugin.getProfileManager().getProfile("no-brand");
                if (noBrandProfile != null && noBrandProfile.isEnabled() && noBrandProfile.shouldFlag()) {
                    violations.add(new Violation(
                        Violation.ViolationType.NO_BRAND,
                        "No client brand detected",
                        "unknown",
                        null,
                        noBrandProfile
                    ));
                }
            } else {
                // Regular brand detection
                
                // Match to a profile
                ClientProfile matchedProfile = plugin.getProfileManager().findMatchingProfile(brand);
                ClientProfile globalProfile = plugin.getProfileManager().getGlobalProfile();
                
                // Apply global profile first (for channel rules)
                if (globalProfile != null && globalProfile.isEnabled()) {
                    applyProfileRules(player, session, globalProfile, violations);
                }
                
                // Apply matched profile
                if (matchedProfile != null && matchedProfile.isEnabled()) {
                    // Special case: Geyser spoofing
                    if (matchedProfile.getId().equals("geyser") && 
                        matchedProfile.shouldFlagNonFloodgate() && 
                        !isBedrockPlayer) {
                        
                        violations.add(new Violation(
                            Violation.ViolationType.GEYSER_SPOOFING,
                            "Geyser client spoofing",
                            brand,
                            null,
                            matchedProfile
                        ));
                    } 
                    // Regular profile checks
                    else {
                        applyProfileRules(player, session, matchedProfile, violations);
                    }
                }
            }
            
            // Process violations if any were found
            if (!violations.isEmpty()) {
                // Add pending violations to session
                for (Violation violation : violations) {
                    session.addPendingViolation(violation);
                }
                
                // Process on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    processViolations(player);
                });
            }
        } finally {
            // Always remove from in progress set
            inProgressChecks.remove(uuid);
        }
    }
    
    /**
     * Check a player immediately and return the result
     * @param player The player to check
     * @return True if the player is detected as spoofing
     */
    public boolean checkPlayerImmediately(Player player) {
        if (player == null || !player.isOnline() || player.hasPermission("antispoof.bypass")) {
            return false;
        }
        
        UUID uuid = player.getUniqueId();
        
        // Get player session
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) {
            // Create a new session if one doesn't exist
            plugin.registerPlayerSession(player);
            session = plugin.getPlayerSession(uuid);
        }
        
        // Skip if already punished
        if (session.isAlreadyPunished()) {
            return true; // Consider already punished players as spoofing
        }
        
        // Clear any existing violations to ensure a fresh check
        session.clearPendingViolations();
        
        // Get client brand
        String brand = session.getClientBrand();
        
        // Check for Bedrock player
        boolean isBedrockPlayer = plugin.getBedrockService().isBedrockPlayer(player);
        
        // Exempt Bedrock players
        if (isBedrockPlayer) {
            return false;
        }
        
        // Prepare to collect violations
        List<Violation> violations = new ArrayList<>();
        
        // Special case: No brand
        if (brand == null) {
            ClientProfile noBrandProfile = plugin.getProfileManager().getProfile("no-brand");
            if (noBrandProfile != null && noBrandProfile.isEnabled() && noBrandProfile.shouldFlag()) {
                violations.add(new Violation(
                    Violation.ViolationType.NO_BRAND,
                    "No client brand detected",
                    "unknown",
                    null,
                    noBrandProfile
                ));
            }
        } else {
            // Regular brand detection
            
            // Match to a profile
            ClientProfile matchedProfile = plugin.getProfileManager().findMatchingProfile(brand);
            ClientProfile globalProfile = plugin.getProfileManager().getGlobalProfile();
            
            // Apply global profile first (for channel rules)
            if (globalProfile != null && globalProfile.isEnabled()) {
                applyProfileRules(player, session, globalProfile, violations);
            }
            
            // Apply matched profile
            if (matchedProfile != null && matchedProfile.isEnabled()) {
                // Special case: Geyser spoofing
                if (matchedProfile.getId().equals("geyser") && 
                    matchedProfile.shouldFlagNonFloodgate() && 
                    !isBedrockPlayer) {
                    
                    violations.add(new Violation(
                        Violation.ViolationType.GEYSER_SPOOFING,
                        "Geyser client spoofing",
                        brand,
                        null,
                        matchedProfile
                    ));
                } 
                // Regular profile checks
                else {
                    applyProfileRules(player, session, matchedProfile, violations);
                }
            }
        }
        
        // Store violations but don't process them
        for (Violation violation : violations) {
            session.addPendingViolation(violation);
        }
        
        return !violations.isEmpty();
    }
    
    private void applyProfileRules(Player player, PlayerSession session, 
                                ClientProfile profile, List<Violation> violations) {
        // Skip if profile isn't enabled
        if (!profile.isEnabled()) return;
        
        String brand = session.getClientBrand();
        
        // Check if this brand should be flagged by this profile
        if (profile.shouldFlag()) {
            violations.add(new Violation(
                Violation.ViolationType.BRAND_MISMATCH,
                "Using flagged client brand: " + brand,
                brand,
                null,
                profile
            ));
        }
        
        // Check channel requirements
        ChannelRequirement channelReq = profile.getChannelRequirement();
        if (channelReq != null) {
            List<String> channelViolations = new ArrayList<>();
            boolean valid = channelReq.validateChannels(session.getChannels(), channelViolations);
            
            if (!valid) {
                for (String reason : channelViolations) {
                    Violation.ViolationType type = reason.startsWith("Missing") ? 
                        Violation.ViolationType.REQUIRED_CHANNEL_MISSING : 
                        Violation.ViolationType.BLOCKED_CHANNEL_FOUND;
                    
                    String channel = null;
                    if (reason.startsWith("Using blocked channel:")) {
                        channel = reason.substring(reason.indexOf(":") + 1).trim();
                    }
                    
                    violations.add(new Violation(
                        type,
                        reason,
                        brand,
                        channel,
                        profile
                    ));
                }
            }
        }
    }
    
    /**
     * Process violations for a player
     */
    public void processViolations(Player player) {
        if (player == null || !player.isOnline()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        
        if (session == null || session.isAlreadyPunished() || session.getPendingViolations().isEmpty()) {
            return;
        }
        
        List<Violation> violations = new ArrayList<>(session.getPendingViolations());
        session.clearPendingViolations();
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Processing " + violations.size() + " violations for " + player.getName());
        }
        
        // Send alerts
        plugin.getAlertService().sendViolationAlerts(player, violations);
        
        // Send to Discord
        plugin.getDiscordService().sendViolationAlerts(player, violations);
        
        // Execute punishment if needed
        plugin.getPunishmentService().processPunishments(player, violations);
    }
    
    /**
     * Called when a client brand is received
     */
    public void handleClientBrand(Player player, String brand) {
        if (player == null) return;
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) {
            plugin.registerPlayerSession(player);
            session = plugin.getPlayerSession(uuid);
        }
        
        // Normalize the brand - handle special cases
        if (brand != null) {
            // Trim the brand to remove any extra spaces
            brand = brand.trim();
            
            // If brand is empty after trimming, treat as null
            if (brand.isEmpty()) {
                brand = null;
            }
        }
        
        String previousBrand = session.getClientBrand();
        boolean isNewBrand = previousBrand == null || !previousBrand.equals(brand);
        
        if (isNewBrand) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Client brand for " + player.getName() + ": " + 
                                    (brand != null ? brand : "null"));
            }
            
            session.setClientBrand(brand);
            
            // If the client has any channels and this is a Fabric client but no brand was detected previously,
            // check to see if the client has Fabric channels
            if ((previousBrand == null || previousBrand.isEmpty()) && 
                brand != null && brand.equalsIgnoreCase("fabric") &&
                !session.getChannels().isEmpty()) {
                
                boolean hasFabricChannel = false;
                for (String channel : session.getChannels()) {
                    if (channel.toLowerCase().contains("fabric")) {
                        hasFabricChannel = true;
                        break;
                    }
                }
                
                if (hasFabricChannel && plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Debug] Validated Fabric client for " + player.getName() + 
                                        " - has Fabric channels");
                }
            }
            
            // Run detection with a short delay to allow time for channel registration
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    checkPlayer(player);
                }
            }, 5L);
        }
    }
    
    /**
     * Called when a channel is registered
     */
    public void handleChannelRegistration(Player player, String channel) {
        if (player == null) return;
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) {
            plugin.registerPlayerSession(player);
            session = plugin.getPlayerSession(uuid);
        }
        
        boolean channelAdded = session.addChannel(channel);
        
        if (channelAdded) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Channel registered for " + player.getName() + ": " + channel);
            }
            
            // If after initial registration, notify for modified channels
            if (session.isInitialChannelsRegistered() && 
                plugin.getConfigManager().isModifiedChannelsEnabled()) {
                
                plugin.getAlertService().sendModifiedChannelAlert(player, channel);
            }
            
            // Mark as having completed initial registration after a delay
            if (!session.isInitialChannelsRegistered() && 
                System.currentTimeMillis() - session.getJoinTime() > 5000) {
                session.setInitialChannelsRegistered(true);
            }
            
            // Run detection
            checkPlayer(player);
        }
    }
    
    /**
     * Called when a channel is unregistered
     */
    public void handleChannelUnregistration(Player player, String channel) {
        if (player == null) return;
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) return;
        
        boolean removed = session.removeChannel(channel);
        
        if (removed && plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Channel unregistered for " + player.getName() + ": " + channel);
        }
    }
}