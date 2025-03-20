package com.gigazelensky.antispoof.service;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.ClientProfile;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
import com.gigazelensky.antispoof.util.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles punishment execution
 */
public class PunishmentService {
    private final AntiSpoofPlugin plugin;
    
    public PunishmentService(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Processes punishments for violations
     */
    public void processPunishments(Player player, List<Violation> violations) {
        if (violations == null || violations.isEmpty() || player == null || !player.isOnline()) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        
        if (session == null || session.isAlreadyPunished()) {
            return;
        }
        
        // Group violations by profile to handle multiple violations
        Map<ClientProfile, List<Violation>> profileViolations = new HashMap<>();
        
        for (Violation v : violations) {
            ClientProfile profile = v.getAssociatedProfile();
            profileViolations.computeIfAbsent(profile, k -> new ArrayList<>()).add(v);
        }
        
        // Check each profile's violations
        for (Map.Entry<ClientProfile, List<Violation>> entry : profileViolations.entrySet()) {
            ClientProfile profile = entry.getKey();
            List<Violation> profileViols = entry.getValue();
            
            if (profile == null || !profile.shouldPunish() || profile.getPunishments().isEmpty()) {
                continue;
            }
            
            // Format reasons for placeholders
            List<String> reasons = new ArrayList<>();
            for (Violation v : profileViols) {
                reasons.add(v.getReason());
            }
            
            // Get the channel if applicable
            String channel = null;
            for (Violation v : profileViols) {
                if (v.getViolatedChannel() != null) {
                    channel = v.getViolatedChannel();
                    break;
                }
            }
            
            // Get client brand
            String brand = session.getClientBrand();
            
            // Execute punishments
            List<String> executedCommands = new ArrayList<>();
            
            for (String command : profile.getPunishments()) {
                // Format command with placeholders
                String formatted = MessageFormatter.format(
                    command,
                    player, 
                    reasons,
                    brand,
                    channel
                );
                
                // Don't execute the same command twice
                if (executedCommands.contains(formatted)) {
                    continue;
                }
                
                // Log punishment
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[Debug] Executing punishment command for " + 
                                          player.getName() + ": " + formatted);
                }
                
                // Execute on main thread
                final String finalCommand = formatted;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) { // Double-check player is still online
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    }
                });
                
                executedCommands.add(formatted);
            }
            
            // Mark as punished
            session.setPunished(true);
            
            // Once a player is punished by one profile, we stop to avoid multiple punishments
            break;
        }
    }
    
    /**
     * Processes a specific violation
     */
    public void processPunishment(Player player, Violation violation) {
        if (violation == null || player == null || !player.isOnline()) {
            return;
        }
        
        // Create a list with just this violation
        processPunishments(player, Collections.singletonList(violation));
    }
}