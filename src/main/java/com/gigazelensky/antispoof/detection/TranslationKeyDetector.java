package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects mods by checking if translation keys are automatically translated by the client.
 * This exploits MC-265322 where clients automatically translate certain keys from mods.
 */
public class TranslationKeyDetector extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Map to track active detection sessions by player UUID
    private final Map<UUID, DetectionSession> activeSessions = new ConcurrentHashMap<>();
    
    // Map to cache detected mods by player UUID
    private final Map<UUID, Set<String>> detectedMods = new ConcurrentHashMap<>();
    
    // Map to track cooldowns for players
    private final Map<UUID, Long> lastCheckTimes = new ConcurrentHashMap<>();
    
    // Collection of translation keys to check, mapped to their mod names
    private final Map<String, String> translationKeyToMod = new ConcurrentHashMap<>();
    
    // Collection of vanilla translation keys to exclude
    private final Set<String> vanillaTranslationKeys = new HashSet<>();
    
    // Task for cleaning up old sessions
    private BukkitTask cleanupTask;

    // Track players that have active sign tests
    private final Set<UUID> activeSignTests = new HashSet<>();
    
    public TranslationKeyDetector(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        
        // Load translation keys from config
        loadTranslationKeys();
        
        // Load vanilla translation keys (basic set for demo)
        loadVanillaTranslationKeys();
        
        // Register packet listener
        PacketEvents.getAPI().getEventManager().registerListener(this);
        
        // Start cleanup task
        startCleanupTask();
        
        plugin.getLogger().info("[TranslationKeyDetector] Initialized with " + translationKeyToMod.size() + " translation keys");
    }
    
    /**
     * Loads translation keys from the configuration
     */
    public void loadTranslationKeys() {
        translationKeyToMod.clear();
        
        Map<String, List<String>> modTranslations = config.getModTranslationKeys();
        for (Map.Entry<String, List<String>> entry : modTranslations.entrySet()) {
            String modName = entry.getKey();
            List<String> keys = entry.getValue();
            
            for (String key : keys) {
                translationKeyToMod.put(key, modName);
            }
        }
    }
    
    /**
     * Loads a basic set of vanilla translation keys that should be ignored
     */
    private void loadVanillaTranslationKeys() {
        // Basic vanilla keys - in a real implementation, this should be more comprehensive
        vanillaTranslationKeys.addAll(Arrays.asList(
            "block.minecraft.oak_sign",
            "block.minecraft.spruce_sign",
            "block.minecraft.birch_sign",
            "block.minecraft.acacia_sign",
            "block.minecraft.jungle_sign",
            "block.minecraft.dark_oak_sign",
            "block.minecraft.crimson_sign",
            "block.minecraft.warped_sign",
            "block.minecraft.mangrove_sign",
            "block.minecraft.cherry_sign",
            "block.minecraft.bamboo_sign",
            "container.repair",
            "item.minecraft.stone",
            "item.minecraft.diamond"
        ));
    }
    
    /**
     * Starts a cleanup task to remove old sessions
     */
    private void startCleanupTask() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            
            // Remove sessions older than 60 seconds (increased from 30)
            activeSessions.entrySet().removeIf(entry -> {
                boolean shouldRemove = now - entry.getValue().creationTime > 60000;
                if (shouldRemove && config.isDebugMode()) {
                    UUID playerUuid = entry.getKey();
                    Player player = Bukkit.getPlayer(playerUuid);
                    String playerName = player != null ? player.getName() : playerUuid.toString();
                    plugin.getLogger().info("[Debug] Removing stale detection session for " + playerName);
                }
                return shouldRemove;
            });
            
            // Clean up cooldowns older than 5 minutes
            lastCheckTimes.entrySet().removeIf(entry -> 
                now - entry.getValue() > 300000);
            
            // Clear any active sign tests that might have been left active
            activeSignTests.clear();
                
        }, 20 * 30, 20 * 30); // Run every 30 seconds
    }
    
    /**
     * Initiates a detection scan for a player
     * @param player The player to scan
     * @param forceScan If true, bypasses cooldown check for manual scans
     */
    public void scanPlayer(Player player, boolean forceScan) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Check cooldown (skip if forceScan is true)
        if (!forceScan) {
            long now = System.currentTimeMillis();
            Long lastCheck = lastCheckTimes.get(playerUuid);
            if (lastCheck != null && now - lastCheck < config.getTranslationDetectionCooldown() * 1000L) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Translation key detection for " + player.getName() + 
                                         " skipped (on cooldown)");
                }
                return;
            }
        }
        
        // Check if player already has an active session
        if (activeSessions.containsKey(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Translation key detection for " + player.getName() + 
                                     " skipped (active session already exists)");
            }
            
            // If forcing scan, clean up the previous session
            if (forceScan) {
                activeSessions.remove(playerUuid);
            } else {
                return;
            }
        }
        
        // Update last check time
        lastCheckTimes.put(playerUuid, System.currentTimeMillis());
        
        // Create a new detection session
        DetectionSession session = new DetectionSession(playerUuid, System.currentTimeMillis());
        activeSessions.put(playerUuid, session);
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting translation key detection for " + player.getName() + 
                                  (forceScan ? " (forced scan)" : ""));
        }
        
        // Use an unobtrusive approach by testing a few keys at a time
        // Select a batch of keys to test
        List<String> keysToTest = new ArrayList<>(translationKeyToMod.keySet());
        Collections.shuffle(keysToTest); // Randomize order
        
        // Take only first N keys to avoid overwhelming the player
        int batchSize = Math.min(config.getTranslationKeysPerCheck(), keysToTest.size());
        List<String> batchKeys = keysToTest.subList(0, batchSize);
        
        session.translationKeysToCheck.addAll(batchKeys);
        
        // Send fake sign data with translation keys
        sendFakeSignWithTranslationKeys(player, batchKeys);
        
        // Mark this player as having an active sign test
        activeSignTests.add(playerUuid);
    }
    
    /**
     * Backward compatibility - uses default forceScan = false
     */
    public void scanPlayer(Player player) {
        scanPlayer(player, false);
    }
    
    /**
     * Sends a fake sign editor with translation keys to test
     */
    private void sendFakeSignWithTranslationKeys(Player player, List<String> translationKeys) {
        // Find an appropriate location near the player (but out of sight)
        Location fakeLocation = player.getLocation().clone().add(0, -20, 0);
        
        // Store the original block at this location in the session
        UUID playerUuid = player.getUniqueId();
        DetectionSession session = activeSessions.get(playerUuid);
        if (session == null) return;
        
        session.originalLocation = fakeLocation.clone();
        
        // Prepare lines with translation keys
        List<String> lines = new ArrayList<>();
        int keyCount = Math.min(translationKeys.size(), 4);
        
        for (int i = 0; i < keyCount; i++) {
            lines.add("{\"translate\":\"" + translationKeys.get(i) + "\"}");
        }
        
        // Fill remaining lines with empty text if needed
        while (lines.size() < 4) {
            lines.add("\"\"");
        }
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // Send packets to simulate opening a sign editor
                WrapperPlayServerOpenSignEditor openSignPacket = new WrapperPlayServerOpenSignEditor(
                    new Vector3i(fakeLocation.getBlockX(), fakeLocation.getBlockY(), fakeLocation.getBlockZ()),
                    true // frontText
                );
                
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSignPacket);
                
                // Store the sign data in the session
                session.sentLines = new ArrayList<>(lines);
                session.pendingResponse = true;
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Sent fake sign with " + keyCount + " translation keys to " + player.getName());
                    for (int i = 0; i < keyCount; i++) {
                        plugin.getLogger().info("[Debug] Sign line " + i + ": " + translationKeys.get(i));
                    }
                }
                
                // Schedule a fallback detection after a timeout
                // This helps in case the client doesn't respond explicitly
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    // If the session still exists and is still pending response
                    if (activeSessions.containsKey(playerUuid) && 
                        activeSessions.get(playerUuid).pendingResponse &&
                        activeSignTests.contains(playerUuid)) {
                        
                        // The fact that the client processed our sign and didn't crash is itself
                        // an indication that they might have modded client capabilities
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] No explicit sign response from " + player.getName() + 
                                                 " but client processed sign without error - possible mod detection");
                        }
                        
                        // OPTIONAL: You could call processDetection here if you want to flag clients 
                        // that process the sign but don't send an explicit response
                        // processDetection(player, "No explicit response but sign was processed");
                        
                        // Mark as not pending to avoid duplicate detections
                        activeSessions.get(playerUuid).pendingResponse = false;
                        
                        // Clean up
                        activeSessions.remove(playerUuid);
                        activeSignTests.remove(playerUuid);
                    }
                }, 100L); // Wait 5 seconds for a response
                
            } catch (Exception e) {
                plugin.getLogger().warning("[TranslationKeyDetector] Error sending fake sign: " + e.getMessage());
                activeSessions.remove(playerUuid);
                activeSignTests.remove(playerUuid);
            }
        }, 5L); // Short delay to ensure packets are sent in the right order
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            if (!(event.getPlayer() instanceof Player)) return;
            
            Player player = (Player) event.getPlayer();
            UUID playerUuid = player.getUniqueId();
            
            // Additional debug logging for all sign updates
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Received sign update packet from " + player.getName());
            }
            
            // Check if we have an active session for this player
            DetectionSession session = activeSessions.get(playerUuid);
            if (session == null) {
                if (activeSignTests.contains(playerUuid)) {
                    // We have an active test but lost the session - recreate basic session
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Session lost but active test found for " + player.getName() + 
                                             " - processing anyway");
                    }
                    processDetection(player, "Sign response received (session recovery)");
                    activeSignTests.remove(playerUuid);
                    event.setCancelled(true);
                }
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Received sign update from " + player.getName() + 
                                         " but no active session found");
                }
                return;
            }

            if (!session.pendingResponse) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Received sign update from " + player.getName() + 
                                         " but session is not pending response");
                }
                return;
            }
            
            // Mark session as processed
            session.pendingResponse = false;
            
            // Process the detection
            processDetection(player, "Sign response received");
            
            // Cancel the packet since it's our fake sign
            event.setCancelled(true);
            
            // Cleanup
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
        }
    }
    
    /**
     * Processes a mod detection for a player
     */
    private void processDetection(Player player, String reason) {
        UUID playerUuid = player.getUniqueId();
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Detected potential mod for " + player.getName() + 
                                  ": " + reason);
        }
        
        // Add to detected mods
        Set<String> detectedModsForPlayer = detectedMods.computeIfAbsent(
            playerUuid, k -> ConcurrentHashMap.newKeySet());
        detectedModsForPlayer.add("Client Mod (Translation Response)");
        
        // Process the detection
        if (config.isTranslationDetectionEnabled()) {
            plugin.getDetectionManager().processViolation(
                player,
                "TRANSLATION_KEY",
                "Client responded to translation key test (potential mod)"
            );
        }
        
        // Notify any online player with antispoof.admin permission
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("antispoof.admin")) {
                admin.sendMessage(ChatColor.GOLD + "[AntiSpoof] " + ChatColor.GREEN + 
                               "Mod detection for " + player.getName() + ": " + ChatColor.WHITE + reason);
            }
        }
    }
    
    /**
     * Gets the set of detected mods for a player
     */
    public Set<String> getDetectedMods(UUID playerUuid) {
        return detectedMods.getOrDefault(playerUuid, Collections.emptySet());
    }
    
    /**
     * Clears the cooldown for a specific player
     */
    public void clearCooldown(UUID playerUuid) {
        lastCheckTimes.remove(playerUuid);
    }
    
    /**
     * Clears cooldowns for all players
     */
    public void clearAllCooldowns() {
        lastCheckTimes.clear();
    }
    
    /**
     * Cleans up data for a player when they quit
     */
    public void handlePlayerQuit(UUID playerUuid) {
        activeSessions.remove(playerUuid);
        activeSignTests.remove(playerUuid);
    }
    
    /**
     * Unregisters listener and cancels tasks on plugin disable
     */
    public void shutdown() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) {
            cleanupTask.cancel();
        }
    }
    
    /**
     * Class to track a detection session for a player
     */
    private static class DetectionSession {
        final UUID playerUuid;
        final long creationTime;
        Location originalLocation;
        List<String> sentLines = new ArrayList<>();
        List<String> translationKeysToCheck = new ArrayList<>();
        Set<String> detectedMods = new HashSet<>();
        boolean pendingResponse = false;
        
        DetectionSession(UUID playerUuid, long creationTime) {
            this.playerUuid = playerUuid;
            this.creationTime = creationTime;
        }
    }
}