package com.gigazelensky.antispoof.service;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.ClientProfile;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.model.Violation;
import com.gigazelensky.antispoof.util.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles in-game alerts
 */
public class AlertService {
    private final AntiSpoofPlugin plugin;
    
    // Track alert recipients
    private final Set<UUID> alertRecipients = ConcurrentHashMap.newKeySet();
    
    // Track cooldowns
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    
    // Cooldown time in milliseconds
    private static final long COOLDOWN_TIME = 3000; // 3 seconds
    
    public AlertService(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Registers all online players with alert permission
     */
    public void registerAlertRecipients() {
        alertRecipients.clear();
        
        // Add all online players with permission
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("antispoof.alerts")) {
                alertRecipients.add(player.getUniqueId());
            }
        }
    }
    
    /**
     * Registers a player for alerts if they have permission
     */
    public void registerPlayer(Player player) {
        if (player != null && player.hasPermission("antispoof.alerts")) {
            alertRecipients.add(player.getUniqueId());
            
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[Debug] Added " + player.getName() + " to alert recipients");
            }
        }
    }
    
    /**
     * Unregisters a player from alerts
     */
    public void unregisterPlayer(UUID uuid) {
        if (uuid != null) {
            alertRecipients.remove(uuid);
            cooldowns.remove(uuid);
        }
    }
    
    /**
     * Sends violation alerts for a player
     */
    public void sendViolationAlerts(Player player, List<Violation> violations) {
        if (player == null || violations.isEmpty()) return;
        
        UUID uuid = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSession(uuid);
        if (session == null) return;
        
        String brand = session.getClientBrand();
        
        // Group violations by profile
        Map<ClientProfile, List<Violation>> profileViolations = new HashMap<>();
        
        for (Violation violation : violations) {
            ClientProfile profile = violation.getAssociatedProfile();
            if (profile == null) continue;
            
            profileViolations.computeIfAbsent(profile, k -> new ArrayList<>()).add(violation);
        }
        
        // Process each profile's violations
        for (Map.Entry<ClientProfile, List<Violation>> entry : profileViolations.entrySet()) {
            ClientProfile profile = entry.getKey();
            List<Violation> profileViols = entry.getValue();
            
            if (profile == null || !profile.shouldAlert() || profileViols.isEmpty()) continue;
            
            // Format reasons
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
            
            // Format alert message
            String alertMsg = MessageFormatter.format(
                profile.getAlertMessage(),
                player,
                reasons,
                brand,
                channel
            );
            
            // Format console message
            String consoleMsg = MessageFormatter.format(
                profile.getConsoleAlertMessage(),
                player,
                reasons,
                brand,
                channel
            );
            
            // Send to console
            plugin.getLogger().info(consoleMsg);
            
            // Send to alert recipients if not on cooldown
            if (checkCooldown(uuid, "VIOLATION")) {
                sendToRecipients(alertMsg);
            }
        }
    }
    
    /**
     * Sends a modified channel alert
     */
    public void sendModifiedChannelAlert(Player player, String channel) {
        if (player == null || channel == null || !plugin.getConfigManager().isModifiedChannelsEnabled()) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        
        // Skip if on cooldown
        if (!checkCooldown(uuid, "MODIFIED_CHANNEL")) {
            return;
        }
        
        // Format alert message
        String alertMsg = MessageFormatter.format(
            plugin.getConfigManager().getModifiedChannelsAlertMessage(),
            player,
            null,
            plugin.getPlayerSession(uuid).getClientBrand(),
            channel
        );
        
        // Format console message
        String consoleMsg = MessageFormatter.format(
            plugin.getConfigManager().getModifiedChannelsConsoleAlertMessage(),
            player,
            null,
            plugin.getPlayerSession(uuid).getClientBrand(),
            channel
        );
        
        // Send to console
        plugin.getLogger().info(consoleMsg);
        
        // Send to alert recipients
        sendToRecipients(alertMsg);
        
        // Send to Discord if enabled
        if (plugin.getConfigManager().isModifiedChannelsDiscordEnabled()) {
            plugin.getDiscordService().sendModifiedChannelAlert(player, channel);
        }
    }
    
    /**
     * Sends a message to all alert recipients
     */
    private void sendToRecipients(String message) {
        String colored = ChatColor.translateAlternateColorCodes('&', message);
        
        for (UUID uuid : alertRecipients) {
            Player recipient = Bukkit.getPlayer(uuid);
            if (recipient != null && recipient.isOnline()) {
                recipient.sendMessage(colored);
            }
        }
    }
    
    /**
     * Checks if an alert can be sent (not on cooldown)
     */
    private boolean checkCooldown(UUID uuid, String alertType) {
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        
        Long lastAlert = playerCooldowns.get(alertType);
        if (lastAlert != null && now - lastAlert < COOLDOWN_TIME) {
            return false;
        }
        
        playerCooldowns.put(alertType, now);
        return true;
    }
    
    /**
     * Gets the number of alert recipients
     */
    public int getAlertRecipientCount() {
        return alertRecipients.size();
    }
}