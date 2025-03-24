package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects mods by checking if translation keys are automatically translated by the client.
 * Uses a simplified approach that works across different versions of PacketEvents.
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
    
    // Task for cleaning up old sessions
    private BukkitTask cleanupTask;

    // Track players that have active sign tests
    private final Set<UUID> activeSignTests = new HashSet<>();
    
    // Use a hybrid approach flag (controls whether to use packets or Bukkit API)
    private boolean useHybridApproach = true;
    
    // Well-known translation keys that are likely to be found in mods
    private final Map<String, String> WELL_KNOWN_KEYS = new HashMap<String, String>() {{
        put("sodium.option_impact.low", "Sodium");
        put("of.key.zoom", "OptiFine");
        put("key.wurst.zoom", "Wurst Client");
        put("gui.xaero_minimap_settings", "Xaero's Minimap");
        put("key.xaero_open_map", "Xaero's World Map");
        put("tweakeroo.gui.button.config_gui.tweaks", "Tweakeroo");
        put("litematica.gui.button.change_menu.to_main_menu", "Litematica");
        put("key.freecam.toggle", "Freecam");
        put("xray.config.toggle", "XRay");
        put("key.meteor-client.open-gui", "Meteor Client");
    }};
    
    public TranslationKeyDetector(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        
        // Load translation keys from config
        loadTranslationKeys();
        
        // Add well-known keys if not already present
        for (Map.Entry<String, String> entry : WELL_KNOWN_KEYS.entrySet()) {
            if (!translationKeyToMod.containsKey(entry.getKey())) {
                translationKeyToMod.put(entry.getKey(), entry.getValue());
            }
        }
        
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
        
        // Always clean up any existing session
        if (activeSessions.containsKey(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Cleaning up existing session for " + player.getName());
            }
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
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
        
        // Test the first key using our preferred method
        testWithRealSign(player, batchKeys.get(0));
        
        // Mark this player as having an active sign test
        activeSignTests.add(playerUuid);
    }

    /**
     * Initiates a targeted detection scan for a specific translation key
     * @param player The player to scan
     * @param translationKey The specific translation key to test
     */
    public void scanPlayerForKey(Player player, String translationKey) {
        if (player == null || !player.isOnline() || translationKey == null || translationKey.isEmpty()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Always clean up any existing session
        if (activeSessions.containsKey(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Cleaning up existing session for " + player.getName());
            }
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
        }
        
        // Create a new detection session
        DetectionSession session = new DetectionSession(playerUuid, System.currentTimeMillis());
        activeSessions.put(playerUuid, session);
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting targeted translation key detection for " + player.getName() + 
                                 " with key: " + translationKey);
        }
        
        // Add the specific key to test
        session.translationKeysToCheck.add(translationKey);
        
        // Use our preferred method
        testWithRealSign(player, translationKey);
        
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
     * Tests a translation key using a real sign
     * This approach is more reliable than virtual signs as it doesn't depend on complex packets
     */
    private void testWithRealSign(Player player, String translationKey) {
        UUID playerUuid = player.getUniqueId();
        DetectionSession session = activeSessions.get(playerUuid);
        if (session == null) return;

        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Testing translation key for " + player.getName() + ": " + translationKey);
        }
        
        // Place a real sign at a location near the player
        Location playerLoc = player.getLocation();
        Location signLoc = playerLoc.clone().add(0, -10, 0);
        
        // Store the location for cleanup
        session.originalLocation = signLoc.clone();
        
        // Save original block for restoration
        Material originalMaterial = signLoc.getBlock().getType();
        byte originalData = signLoc.getBlock().getData();
        
        // Schedule this after a short delay to ensure everything is ready
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // Place a sign using Bukkit API
                signLoc.getBlock().setType(Material.OAK_SIGN);
                
                // Add a short delay to ensure the sign is placed
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        // Get the sign and set its text
                        if (signLoc.getBlock().getState() instanceof Sign) {
                            Sign sign = (Sign) signLoc.getBlock().getState();
                            sign.setLine(0, translationKey);
                            sign.update();
                            
                            if (config.isDebugMode()) {
                                plugin.getLogger().info("[Debug] Created sign with translation key: " + translationKey);
                            }
                            
                            // After sign is updated, open the sign editor
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    // Create position for packets
                                    Vector3i position = new Vector3i(
                                        signLoc.getBlockX(),
                                        signLoc.getBlockY(),
                                        signLoc.getBlockZ()
                                    );
                                    
                                    // Open sign editor
                                    WrapperPlayServerOpenSignEditor openSignPacket = new WrapperPlayServerOpenSignEditor(position, true);
                                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSignPacket);
                                    
                                    session.pendingResponse = true;
                                    
                                    if (config.isDebugMode()) {
                                        plugin.getLogger().info("[Debug] Opened sign editor for " + player.getName());
                                    }
                                    
                                    // Immediately close the sign editor
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (player.isOnline()) {
                                            WrapperPlayServerCloseWindow closeWindowPacket = new WrapperPlayServerCloseWindow(1);
                                            PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindowPacket);
                                            
                                            if (config.isDebugMode()) {
                                                plugin.getLogger().info("[Debug] Closed sign editor for " + player.getName());
                                            }
                                        }
                                    }, 2L); // 2 ticks before close
                                    
                                    // Set timeout and restore block
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        // Restore the original block
                                        signLoc.getBlock().setType(originalMaterial);
                                        
                                        // Check if we received a response
                                        if (activeSessions.containsKey(playerUuid) && 
                                            activeSessions.get(playerUuid).pendingResponse) {
                                            
                                            if (config.isDebugMode()) {
                                                plugin.getLogger().info("[Debug] No response received from " + player.getName());
                                            }
                                            
                                            // Clean up session
                                            activeSessions.remove(playerUuid);
                                            activeSignTests.remove(playerUuid);
                                        }
                                    }, 20L); // 1 second timeout (20 ticks)
                                    
                                } catch (Exception e) {
                                    plugin.getLogger().warning("[TranslationKeyDetector] Error opening sign editor: " + e.getMessage());
                                    // Restore the original block
                                    signLoc.getBlock().setType(originalMaterial);
                                    // Clean up
                                    activeSessions.remove(playerUuid);
                                    activeSignTests.remove(playerUuid);
                                }
                            }, 2L); // 2 ticks after sign update
                        } else {
                            if (config.isDebugMode()) {
                                plugin.getLogger().info("[Debug] Block at location is not a sign: " + signLoc);
                            }
                            // Clean up
                            signLoc.getBlock().setType(originalMaterial);
                            activeSessions.remove(playerUuid);
                            activeSignTests.remove(playerUuid);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[TranslationKeyDetector] Error setting sign text: " + e.getMessage());
                        // Restore the original block
                        signLoc.getBlock().setType(originalMaterial);
                        // Clean up
                        activeSessions.remove(playerUuid);
                        activeSignTests.remove(playerUuid);
                    }
                }, 2L); // 2 ticks after placing sign
                
            } catch (Exception e) {
                plugin.getLogger().warning("[TranslationKeyDetector] Error creating sign: " + e.getMessage());
                // Clean up
                activeSessions.remove(playerUuid);
                activeSignTests.remove(playerUuid);
            }
        }, 1L); // 1 tick initial delay
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            if (!(event.getPlayer() instanceof Player)) return;
            
            Player player = (Player) event.getPlayer();
            UUID playerUuid = player.getUniqueId();
            
            // Check if this is a response to our test
            if (!activeSignTests.contains(playerUuid)) {
                return;
            }
            
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
                
                return;
            }

            if (!session.pendingResponse) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Received sign update from " + player.getName() + 
                                         " but session is not pending response");
                }
                return;
            }
            
            // Get the actual content from the packet to check for translations
            WrapperPlayClientUpdateSign signPacket = new WrapperPlayClientUpdateSign(event);
            String[] receivedLines = signPacket.getTextLines();
            
            // Log the received lines
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Received sign update with content:");
                for (int i = 0; i < receivedLines.length; i++) {
                    plugin.getLogger().info("[Debug] Line " + i + ": '" + receivedLines[i] + "'");
                }
            }
            
            // Check if any lines were translated (compare with the original keys)
            List<String> translatedKeys = new ArrayList<>();
            List<String> originalKeys = session.translationKeysToCheck;
            
            for (String originalKey : originalKeys) {
                boolean wasTranslated = false;
                
                // Check each line of the sign
                for (String receivedLine : receivedLines) {
                    String trimmedLine = receivedLine.trim();
                    
                    // Skip empty lines
                    if (trimmedLine.isEmpty()) continue;
                    
                    // If the line doesn't contain the original key at all
                    // and the line is not empty, it likely means the key was translated
                    if (!trimmedLine.equals(originalKey) && !trimmedLine.contains(originalKey)) {
                        wasTranslated = true;
                        
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] Potential translation detected! Key: " + 
                                               originalKey + " → " + trimmedLine);
                        }
                        
                        String modName = translationKeyToMod.getOrDefault(originalKey, "Unknown Mod");
                        translatedKeys.add(originalKey + " -> " + modName);
                        break;
                    }
                }
                
                // If the key wasn't found as translated in any line
                if (!wasTranslated && config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] No translation detected for key: " + originalKey);
                }
            }
            
            // Mark session as processed
            session.pendingResponse = false;
            
            // Process the detection results
            if (!translatedKeys.isEmpty()) {
                processDetectionWithSpecificMods(player, translatedKeys);
            } else {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] No translation detected in sign response from " + player.getName());
                }
            }
            
            // Cancel the packet since it's our test sign
            event.setCancelled(true);
            
            // Cleanup
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
            
            // Restore original block if using real sign approach
            if (session.originalLocation != null) {
                Location signLoc = session.originalLocation;
                if (signLoc.getBlock().getType() == Material.OAK_SIGN ||
                    signLoc.getBlock().getType().name().contains("SIGN")) {
                    signLoc.getBlock().setType(Material.AIR);
                }
            }
        }
    }
    
    /**
     * Processes a mod detection for a player with specific mods detected
     */
    private void processDetectionWithSpecificMods(Player player, List<String> translatedKeys) {
        UUID playerUuid = player.getUniqueId();
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Detected mods for " + player.getName() + ": " + 
                               String.join(", ", translatedKeys));
        }
        
        // Add to detected mods
        Set<String> detectedModsForPlayer = detectedMods.computeIfAbsent(
            playerUuid, k -> ConcurrentHashMap.newKeySet());
            
        // Extract mod names from the translations
        for (String translation : translatedKeys) {
            // Format is "key -> ModName"
            String[] parts = translation.split(" -> ", 2);
            if (parts.length > 1) {
                detectedModsForPlayer.add(parts[1]);
            } else {
                detectedModsForPlayer.add(translation);
            }
        }
        
        // Process the detection
        if (config.isTranslationDetectionEnabled()) {
            String detectedModsString = String.join(", ", detectedModsForPlayer);
            plugin.getDetectionManager().processViolation(
                player,
                "TRANSLATION_KEY",
                "Detected mod(s): " + detectedModsString
            );
        }
        
        // Notify any online player with antispoof.admin permission
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("antispoof.admin")) {
                admin.sendMessage(ChatColor.GOLD + "[AntiSpoof] " + ChatColor.GREEN + 
                               "Mod detection for " + player.getName() + ": " + 
                               ChatColor.WHITE + String.join(", ", translatedKeys));
            }
        }
    }
    
    /**
     * Processes a generic mod detection for a player
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
     * Gets a set of all translation keys configured for detection
     */
    public Set<String> getAllTranslationKeys() {
        return translationKeyToMod.keySet();
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
        List<String> translationKeysToCheck = new ArrayList<>();
        boolean pendingResponse = false;
        
        DetectionSession(UUID playerUuid, long creationTime) {
            this.playerUuid = playerUuid;
            this.creationTime = creationTime;
        }
    }
}