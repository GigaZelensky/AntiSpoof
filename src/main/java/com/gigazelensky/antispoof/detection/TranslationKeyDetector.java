package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Modern implementation of translation key detection system that works by exploiting MC-265322.
 * This system uses a sequence of packets to create a virtual sign with a translation key,
 * open a sign editor for the player, and immediately close it, forcing the client to respond
 * with the translated (or not) text.
 */
public class TranslationKeyDetector extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;
    private final ConfigManager config;
    
    // Cache for constructor and method reflection to improve performance
    private Constructor<?> blockEntityDataConstructor;
    private Method sendPacketMethod;
    private boolean useXYZConstructor = false;
    private boolean initialized = false;
    
    // Maps to track active detection sessions and results
    private final Map<UUID, DetectionSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> detectedMods = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCheckTimes = new ConcurrentHashMap<>();
    private final Set<UUID> activeSignTests = new HashSet<>();
    
    // Map of translation keys to their respective mod names
    private final Map<String, String> translationKeyToMod = new ConcurrentHashMap<>();
    
    // Cleanup task to remove stale sessions
    private BukkitTask cleanupTask;
    
    // Well-known keys that often indicate specific mods
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
    
    /**
     * Constructor initializes the detector and loads initial translation keys
     */
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
        
        // Initialize reflection access
        initializeReflection();
        
        // Start cleanup task
        startCleanupTask();
        
        plugin.getLogger().info("[TranslationKeyDetector] Initialized with " + translationKeyToMod.size() + " translation keys");
    }
    
    /**
     * Initialize reflection access to PacketEvents classes and methods
     */
    private void initializeReflection() {
        try {
            // Get BlockEntityData class
            Class<?> blockEntityDataClass = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData");
            
            // Find available constructors and log them for debugging
            for (Constructor<?> c : blockEntityDataClass.getConstructors()) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Available constructor: " + c);
                }
            }
            
            // Try to find Vector3i constructor
            try {
                blockEntityDataConstructor = blockEntityDataClass.getConstructor(Vector3i.class, int.class, Object.class);
                useXYZConstructor = false;
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Using Vector3i constructor for block entity data");
                }
            } catch (NoSuchMethodException e) {
                // Try x, y, z coordinate constructor
                try {
                    blockEntityDataConstructor = blockEntityDataClass.getConstructor(int.class, int.class, int.class, int.class, Object.class);
                    useXYZConstructor = true;
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Using x,y,z coordinates constructor for block entity data");
                    }
                } catch (NoSuchMethodException e2) {
                    plugin.getLogger().warning("[TranslationKeyDetector] Could not find a suitable constructor for BlockEntityData");
                    return;
                }
            }
            
            // Find method to send a packet to a player
            // First try new API (User)
            try {
                Class<?> userClass = Class.forName("com.github.retrooper.packetevents.protocol.player.User");
                Class<?> packetClass = Class.forName("com.github.retrooper.packetevents.protocol.packettype.PacketType$Play$Server");
                sendPacketMethod = userClass.getDeclaredMethod("sendPacket", Object.class);
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Using User.sendPacket method");
                }
            } catch (Exception e) {
                // Fall back to PlayerManager API
                try {
                    Class<?> playerManagerClass = Class.forName("com.github.retrooper.packetevents.manager.player.PlayerManager");
                    sendPacketMethod = playerManagerClass.getDeclaredMethod("sendPacket", Object.class, Object.class);
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Using PlayerManager.sendPacket method");
                    }
                } catch (Exception e2) {
                    plugin.getLogger().warning("[TranslationKeyDetector] Could not find sendPacket method: " + e2.getMessage());
                    return;
                }
            }
            
            initialized = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[TranslationKeyDetector] Error initializing reflection: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Loads translation keys from the plugin configuration
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
            
            // Remove sessions older than 60 seconds
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
     * Initiates a batch detection scan for a player
     * @param player The player to scan
     * @param forceScan If true, bypasses cooldown check
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
            if (forceScan) {
                activeSessions.remove(playerUuid);
                activeSignTests.remove(playerUuid);
            } else {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Translation key detection for " + player.getName() + 
                                        " skipped (active session already exists)");
                }
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
        
        // Select a batch of keys to test
        List<String> keysToTest = new ArrayList<>(translationKeyToMod.keySet());
        Collections.shuffle(keysToTest); // Randomize order
        
        // Take only first N keys to avoid overwhelming the player
        int batchSize = Math.min(config.getTranslationKeysPerCheck(), keysToTest.size());
        List<String> batchKeys = keysToTest.subList(0, batchSize);
        
        session.translationKeysToCheck.addAll(batchKeys);
        
        // Send fake sign data with translation keys
        testWithVirtualSign(player, batchKeys.get(0));
        
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
        
        // Use virtual sign method
        testWithVirtualSign(player, translationKey);
        
        // Mark this player as having an active sign test
        activeSignTests.add(playerUuid);
    }
    
    /**
     * Tests a translation key using a virtual sign
     * Sequence: BlockChange -> BlockEntityData -> OpenSignEditor -> CloseWindow
     */
    private void testWithVirtualSign(Player player, String translationKey) {
        if (!initialized) {
            plugin.getLogger().warning("[TranslationKeyDetector] Cannot test sign - not fully initialized");
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        DetectionSession session = activeSessions.get(playerUuid);
        if (session == null) return;
        
        // Find a location near the player but out of view
        Location playerLoc = player.getLocation();
        Location signLoc = playerLoc.clone().add(0, -10, 0); // 10 blocks below player
        
        // Store the sign location in the session
        session.originalLocation = signLoc.clone();
        
        // Create position vector for packets
        Vector3i position = new Vector3i(
            signLoc.getBlockX(),
            signLoc.getBlockY(),
            signLoc.getBlockZ()
        );
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Testing key for " + player.getName() + ": " + translationKey + 
                                 " at position " + position.getX() + "," + position.getY() + "," + position.getZ());
        }
        
        // Send the packets in sequence with appropriate timing
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // STEP 1: Create virtual sign block
                // Oak wall sign type ID varies by version, 3 is common
                WrapperPlayServerBlockChange blockChangePacket = new WrapperPlayServerBlockChange(position, 3); 
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChangePacket);
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Sent block change packet to " + player.getName());
                }
                
                // STEP 2: Create sign entity data with translation key
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        // Create sign text with translation key in JSON format
                        Object blockEntityPacket = createBlockEntityDataPacket(position, translationKey);
                        if (blockEntityPacket != null) {
                            PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntityPacket);
                            
                            if (config.isDebugMode()) {
                                plugin.getLogger().info("[Debug] Sent block entity data packet to " + player.getName());
                            }
                            
                            // STEP 3: Open sign editor
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    // Open sign editor
                                    WrapperPlayServerOpenSignEditor openSignPacket = new WrapperPlayServerOpenSignEditor(position, true);
                                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSignPacket);
                                    
                                    session.pendingResponse = true;
                                    
                                    if (config.isDebugMode()) {
                                        plugin.getLogger().info("[Debug] Sent open sign editor packet to " + player.getName());
                                    }
                                    
                                    // STEP 4: Immediately close the sign editor to force response
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (player.isOnline()) {
                                            // Close window with ID 1 (sign editor)
                                            WrapperPlayServerCloseWindow closeWindowPacket = new WrapperPlayServerCloseWindow(1);
                                            PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindowPacket);
                                            
                                            if (config.isDebugMode()) {
                                                plugin.getLogger().info("[Debug] Sent close window packet to " + player.getName());
                                            }
                                        }
                                    }, 1L); // Close after 1 tick
                                    
                                    // Set timeout to clean up if no response received
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (activeSessions.containsKey(playerUuid) && 
                                            activeSessions.get(playerUuid).pendingResponse &&
                                            activeSignTests.contains(playerUuid)) {
                                            
                                            if (config.isDebugMode()) {
                                                plugin.getLogger().info("[Debug] No response received from " + player.getName() +
                                                                     " for key: " + translationKey);
                                            }
                                            
                                            // Clean up
                                            activeSessions.remove(playerUuid);
                                            activeSignTests.remove(playerUuid);
                                        }
                                    }, 20L); // 1 second timeout
                                } catch (Exception e) {
                                    plugin.getLogger().warning("[TranslationKeyDetector] Error sending sign editor packet: " + e.getMessage());
                                    // Clean up
                                    activeSessions.remove(playerUuid);
                                    activeSignTests.remove(playerUuid);
                                }
                            }, 2L); // Wait 2 ticks before opening sign editor
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[TranslationKeyDetector] Error sending block entity data: " + e.getMessage());
                        e.printStackTrace();
                        // Clean up
                        activeSessions.remove(playerUuid);
                        activeSignTests.remove(playerUuid);
                    }
                }, 2L); // Wait 2 ticks for block change
                
            } catch (Exception e) {
                plugin.getLogger().warning("[TranslationKeyDetector] Error in sign test sequence: " + e.getMessage());
                e.printStackTrace();
                // Clean up
                activeSessions.remove(playerUuid);
                activeSignTests.remove(playerUuid);
            }
        }, 1L); // Initial delay
    }
    
    /**
     * Creates a block entity data packet with the translation key
     * Uses reflection to support different versions of PacketEvents
     */
    private Object createBlockEntityDataPacket(Vector3i position, String translationKey) {
        try {
            // Sign NBT data creation
            Map<String, Object> nbtData = new HashMap<>();
            
            // Add translation key in JSON format
            // Line 1 is the one with the translation key, other lines are empty
            nbtData.put("Text1", "{\"translate\":\"" + translationKey + "\"}");
            nbtData.put("Text2", "{\"text\":\"\"}");
            nbtData.put("Text3", "{\"text\":\"\"}");
            nbtData.put("Text4", "{\"text\":\"\"}");
            
            // Add sign identification
            nbtData.put("id", "minecraft:sign");
            nbtData.put("x", position.getX());
            nbtData.put("y", position.getY());
            nbtData.put("z", position.getZ());
            
            // Instantiate the packet using reflection
            Object packet;
            
            // Block entity type varies by version, 9 is common for newer versions, 4 for older
            int signEntityId = 9;
            
            if (useXYZConstructor) {
                // Use x, y, z coordinates constructor
                packet = blockEntityDataConstructor.newInstance(
                    position.getX(), position.getY(), position.getZ(),
                    signEntityId,
                    nbtData
                );
            } else {
                // Use Vector3i constructor
                packet = blockEntityDataConstructor.newInstance(
                    position,
                    signEntityId,
                    nbtData
                );
            }
            
            return packet;
        } catch (Exception e) {
            plugin.getLogger().severe("[TranslationKeyDetector] Failed to create block entity data packet: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Listen for sign update packets from clients
     */
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
            
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Received sign update packet from " + player.getName());
            }
            
            // Check if we have an active session for this player
            DetectionSession session = activeSessions.get(playerUuid);
            if (session == null) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Received sign update but no session found for " + player.getName());
                }
                activeSignTests.remove(playerUuid);
                event.setCancelled(true);
                return;
            }

            if (!session.pendingResponse) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Received sign update but no pending response for " + player.getName());
                }
                return;
            }
            
            // Get the text content from the sign update packet
            WrapperPlayClientUpdateSign signPacket = new WrapperPlayClientUpdateSign(event);
            String[] receivedLines = signPacket.getTextLines();
            
            // Log the received lines
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Received sign lines for " + player.getName() + ":");
                for (int i = 0; i < receivedLines.length; i++) {
                    plugin.getLogger().info("  Line " + i + ": '" + receivedLines[i] + "'");
                }
            }
            
            // Check if any lines were translated (compare with the original keys)
            List<String> translatedKeys = new ArrayList<>();
            List<String> originalKeys = session.translationKeysToCheck;
            
            for (String originalKey : originalKeys) {
                boolean keyTranslated = false;
                
                // Check each line of the sign
                for (String receivedLine : receivedLines) {
                    String trimmedLine = receivedLine.trim();
                    
                    // Skip empty lines
                    if (trimmedLine.isEmpty()) continue;
                    
                    // If the line doesn't contain the original key
                    // and doesn't contain common untranslatable patterns, 
                    // it likely means the key was translated
                    if (!trimmedLine.equals(originalKey) && 
                        !trimmedLine.contains(originalKey) &&
                        !isCommonUntranslatedFormat(trimmedLine, originalKey)) {
                        
                        keyTranslated = true;
                        String modName = translationKeyToMod.getOrDefault(originalKey, "Unknown Mod");
                        translatedKeys.add(originalKey + " -> " + modName);
                        
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] Translation detected for " + player.getName() + 
                                               ": " + originalKey + " -> " + trimmedLine + " (Mod: " + modName + ")");
                        }
                        
                        break;
                    }
                }
                
                if (!keyTranslated && config.isDebugMode()) {
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
                    plugin.getLogger().info("[Debug] No translations detected for " + player.getName());
                }
            }
            
            // Cancel the packet since it's our virtual sign
            event.setCancelled(true);
            
            // Clean up
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
        }
    }
    
    /**
     * Checks if a line contains untranslated format patterns
     */
    private boolean isCommonUntranslatedFormat(String line, String key) {
        // Common patterns when translation keys aren't translated
        return line.contains("translate") || 
               line.contains("\"" + key + "\"") || 
               line.contains("{" + key + "}");
    }
    
    /**
     * Processes a mod detection with specific mods identified
     */
    private void processDetectionWithSpecificMods(Player player, List<String> translatedKeys) {
        UUID playerUuid = player.getUniqueId();
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Detected mods for " + player.getName() + ": " + 
                               String.join(", ", translatedKeys));
        }
        
        // Add to detected mods for this player
        Set<String> detectedModsForPlayer = detectedMods.computeIfAbsent(
            playerUuid, k -> ConcurrentHashMap.newKeySet());
            
        // Extract mod names from translations
        for (String translation : translatedKeys) {
            String[] parts = translation.split(" -> ", 2);
            if (parts.length > 1) {
                detectedModsForPlayer.add(parts[1]);
            } else {
                detectedModsForPlayer.add(translation);
            }
        }
        
        // Process the detection through the plugin's system
        if (config.isTranslationDetectionEnabled()) {
            String detectedModsString = String.join(", ", detectedModsForPlayer);
            plugin.getDetectionManager().processViolation(
                player,
                "TRANSLATION_KEY",
                "Detected mod(s): " + detectedModsString
            );
        }
        
        // Notify admins with permission
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("antispoof.admin")) {
                admin.sendMessage(ChatColor.GOLD + "[AntiSpoof] " + ChatColor.GREEN + 
                               "Mod detection for " + player.getName() + ": " + 
                               ChatColor.WHITE + String.join(", ", translatedKeys));
            }
        }
    }
    
    /**
     * Gets detected mods for a player
     */
    public Set<String> getDetectedMods(UUID playerUuid) {
        return detectedMods.getOrDefault(playerUuid, Collections.emptySet());
    }
    
    /**
     * Gets all configured translation keys
     */
    public Set<String> getAllTranslationKeys() {
        return translationKeyToMod.keySet();
    }
    
    /**
     * Clears detection cooldown for a player
     */
    public void clearCooldown(UUID playerUuid) {
        lastCheckTimes.remove(playerUuid);
    }
    
    /**
     * Clears all detection cooldowns
     */
    public void clearAllCooldowns() {
        lastCheckTimes.clear();
    }
    
    /**
     * Cleans up when a player quits
     */
    public void handlePlayerQuit(UUID playerUuid) {
        activeSessions.remove(playerUuid);
        activeSignTests.remove(playerUuid);
    }
    
    /**
     * Shutdown cleanup
     */
    public void shutdown() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) {
            cleanupTask.cancel();
        }
    }
    
    /**
     * Session class for tracking active detection tests
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