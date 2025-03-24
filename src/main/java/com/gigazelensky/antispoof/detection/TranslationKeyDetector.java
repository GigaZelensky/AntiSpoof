package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
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
            "item.minecraft.diamond",
            "key.forward",
            "key.left",
            "key.back",
            "key.right",
            "key.jump",
            "key.sneak",
            "key.sprint",
            "key.inventory",
            "key.drop",
            "key.chat",
            "key.command"
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
     * Initiates a targeted detection scan for a specific translation key
     * @param player The player to scan
     * @param translationKey The specific translation key to test
     */
    public void scanPlayerForKey(Player player, String translationKey) {
        if (player == null || !player.isOnline() || translationKey == null || translationKey.isEmpty()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Check if player already has an active session
        if (activeSessions.containsKey(playerUuid)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Translation key detection for " + player.getName() + 
                                     " skipped (active session already exists)");
            }
            
            // Clean up the previous session
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
        
        // Create a list with just this key
        List<String> keys = new ArrayList<>();
        keys.add(translationKey);
        
        // Send fake sign data with the translation key
        sendFakeSignWithTranslationKeys(player, keys);
        
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
     * Sends a fake sign editor with translation keys to test using the complete packet sequence
     */
    private void sendFakeSignWithTranslationKeys(Player player, List<String> translationKeys) {
        // Find an appropriate location near the player (but out of sight)
        Location playerLoc = player.getLocation();
        Location fakeLocation = playerLoc.clone().add(0, -20, 0);
        
        // Store the original block at this location in the session
        UUID playerUuid = player.getUniqueId();
        DetectionSession session = activeSessions.get(playerUuid);
        if (session == null) return;
        
        session.originalLocation = fakeLocation.clone();
        
        // Position vector for the sign
        Vector3i position = new Vector3i(
            fakeLocation.getBlockX(),
            fakeLocation.getBlockY(),
            fakeLocation.getBlockZ()
        );
        
        // Prepare lines with translation keys
        List<String> signLines = new ArrayList<>();
        int keyCount = Math.min(translationKeys.size(), 4);
        
        for (int i = 0; i < keyCount; i++) {
            signLines.add("{\"translate\":\"" + translationKeys.get(i) + "\"}");
        }
        
        // Fill remaining lines with empty text if needed
        while (signLines.size() < 4) {
            signLines.add("\"\"");
        }
        
        // Store the original translationKeys in the session
        session.translationKeysToCheck = new ArrayList<>(translationKeys);
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // 1. Send block change packet to create an oak sign
                WrappedBlockState signState = WrappedBlockState.getByType(StateTypes.OAK_SIGN);
                WrapperPlayServerBlockChange blockChangePacket = new WrapperPlayServerBlockChange(position, signState);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChangePacket);
                
                // 2. Send tile entity data packet with the sign text
                // Prepare the NBT data for the sign
                Map<String, Object> tagCompound = new HashMap<>();
                
                // Add sign lines data
                Map<String, Object> frontTextCompound = new HashMap<>();
                List<Object> messages = new ArrayList<>();
                for (String line : signLines) {
                    messages.add(line);
                }
                frontTextCompound.put("messages", messages);
                frontTextCompound.put("has_glowing_text", false);
                frontTextCompound.put("color", "black");
                
                tagCompound.put("front_text", frontTextCompound);
                tagCompound.put("is_waxed", false);
                
                // Send the block entity data packet
                WrapperPlayServerBlockEntityData blockEntityPacket = new WrapperPlayServerBlockEntityData(
                    position,
                    9, // Sign block entity ID (9 for sign)
                    tagCompound
                );
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntityPacket);
                
                // 3. Send sign editor packet
                WrapperPlayServerOpenSignEditor openSignPacket = new WrapperPlayServerOpenSignEditor(
                    position,
                    true // frontText
                );
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSignPacket);
                
                // 4. Immediately close the window to force client to process sign
                WrapperPlayServerCloseWindow closeWindowPacket = new WrapperPlayServerCloseWindow(1); // Window ID 1
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, closeWindowPacket);
                
                // Store the sign data in the session
                session.sentLines = new ArrayList<>(signLines);
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
                        
                        // Clean up
                        activeSessions.remove(playerUuid);
                        activeSignTests.remove(playerUuid);
                    }
                }, 20L); // Wait 1 second for a response (reduced from 5)
                
            } catch (Exception e) {
                plugin.getLogger().warning("[TranslationKeyDetector] Error sending fake sign: " + e.getMessage());
                if (config.isDebugMode()) {
                    e.printStackTrace();
                }
                activeSessions.remove(playerUuid);
                activeSignTests.remove(playerUuid);
            }
        }, 2L); // Short delay to ensure packets are sent in the right order
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
            
            for (int i = 0; i < Math.min(originalKeys.size(), receivedLines.length); i++) {
                String originalKey = originalKeys.get(i);
                String receivedLine = receivedLines[i].trim();
                
                // If the received line is different from the original key and not empty,
                // it was likely translated
                if (!receivedLine.isEmpty() && !receivedLine.equals(originalKey)) {
                    String modName = translationKeyToMod.getOrDefault(originalKey, "Unknown Mod");
                    translatedKeys.add(originalKey + " -> " + modName);
                    
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Detected translation: " + originalKey + 
                                            " was translated to '" + receivedLine + "', mod: " + modName);
                    }
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
            
            // Cancel the packet since it's our fake sign
            event.setCancelled(true);
            
            // Cleanup
            activeSessions.remove(playerUuid);
            activeSignTests.remove(playerUuid);
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