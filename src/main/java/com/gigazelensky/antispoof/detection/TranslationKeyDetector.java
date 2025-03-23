package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
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
            
            // Remove sessions older than 30 seconds
            activeSessions.entrySet().removeIf(entry -> 
                now - entry.getValue().creationTime > 30000);
                
            // Clean up cooldowns older than 5 minutes
            lastCheckTimes.entrySet().removeIf(entry -> 
                now - entry.getValue() > 300000);
                
        }, 20 * 60, 20 * 60); // Run every minute
    }
    
    /**
     * Initiates a detection scan for a player
     */
    public void scanPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        UUID playerUuid = player.getUniqueId();
        
        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastCheck = lastCheckTimes.get(playerUuid);
        if (lastCheck != null && now - lastCheck < config.getTranslationDetectionCooldown() * 1000L) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Translation key detection for " + player.getName() + 
                                     " skipped (on cooldown)");
            }
            return;
        }
        
        // Update last check time
        lastCheckTimes.put(playerUuid, now);
        
        // Create a new detection session
        DetectionSession session = new DetectionSession(playerUuid, now);
        activeSessions.put(playerUuid, session);
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Debug] Starting translation key detection for " + player.getName());
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
                // Create a Vector3i for the position
                Vector3i position = new Vector3i(
                    fakeLocation.getBlockX(), 
                    fakeLocation.getBlockY(), 
                    fakeLocation.getBlockZ()
                );
                
                // Send packets to simulate opening a sign editor
                // Using Vector3i constructor based on available API
                WrapperPlayServerOpenSignEditor openSignPacket = new WrapperPlayServerOpenSignEditor(
                    position, 
                    true // frontText
                );
                
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSignPacket);
                
                // Store the sign data in the session
                session.sentLines = new ArrayList<>(lines);
                session.pendingResponse = true;
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Sent fake sign with " + keyCount + " translation keys to " + player.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[TranslationKeyDetector] Error sending fake sign: " + e.getMessage());
                activeSessions.remove(playerUuid);
            }
        }, 5L); // Short delay to ensure packets are sent in the right order
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            if (!(event.getPlayer() instanceof Player)) return;
            
            Player player = (Player) event.getPlayer();
            UUID playerUuid = player.getUniqueId();
            
            // Check if we have an active session for this player
            DetectionSession session = activeSessions.get(playerUuid);
            if (session == null || !session.pendingResponse) return;
            
            // Parse the sign update
            WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
            
            // Extract sign lines via reflection since APIs might differ
            String[] receivedLines = extractSignLinesUsingReflection(packet);
            
            // Mark session as processed
            session.pendingResponse = false;
            
            // Process the response
            processFakeSignResponse(player, session, receivedLines);
            
            // Cancel the packet since it's our fake sign
            event.setCancelled(true);
        }
    }
    
    /**
     * Extracts sign lines from a packet using direct reflection
     */
    private String[] extractSignLinesUsingReflection(WrapperPlayClientUpdateSign packet) {
        // Default to empty array
        String[] result = new String[4];
        Arrays.fill(result, ""); // Default to empty strings
        
        try {
            // Try to access fields directly via reflection
            Field[] fields = packet.getClass().getDeclaredFields();
            
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(packet);
                
                if (value instanceof String[]) {
                    String[] lines = (String[]) value;
                    if (lines.length == 4) {
                        return lines;
                    } else if (lines.length > 0) {
                        // Copy what we can
                        System.arraycopy(lines, 0, result, 0, Math.min(lines.length, 4));
                        return result;
                    }
                }
            }
            
            // If no field found, try to get data from the event
            try {
                Field eventField = packet.getClass().getDeclaredField("event");
                if (eventField != null) {
                    eventField.setAccessible(true);
                    Object eventValue = eventField.get(packet);
                    
                    if (eventValue != null) {
                        // Try to extract data from the event object
                        Field[] eventFields = eventValue.getClass().getDeclaredFields();
                        for (Field f : eventFields) {
                            f.setAccessible(true);
                            Object ev = f.get(eventValue);
                            
                            if (ev instanceof String[]) {
                                String[] lines = (String[]) ev;
                                if (lines.length == 4) {
                                    return lines;
                                } else if (lines.length > 0) {
                                    // Copy what we can
                                    System.arraycopy(lines, 0, result, 0, Math.min(lines.length, 4));
                                    return result;
                                }
                            }
                        }
                    }
                }
            } catch (NoSuchFieldException e) {
                // No event field, continue
            }
            
            // Last resort - look for any String[] in any nested object
            for (Field field : fields) {
                field.setAccessible(true);
                Object nestedObj = field.get(packet);
                
                if (nestedObj != null && !(nestedObj instanceof String[])) {
                    try {
                        Field[] nestedFields = nestedObj.getClass().getDeclaredFields();
                        for (Field nField : nestedFields) {
                            nField.setAccessible(true);
                            Object nValue = nField.get(nestedObj);
                            
                            if (nValue instanceof String[]) {
                                String[] lines = (String[]) nValue;
                                if (lines.length == 4) {
                                    return lines;
                                } else if (lines.length > 0) {
                                    // Copy what we can
                                    System.arraycopy(lines, 0, result, 0, Math.min(lines.length, 4));
                                    return result;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore errors in nested reflection
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[TranslationKeyDetector] Error extracting sign lines: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Processes the response from the fake sign edit
     */
    private void processFakeSignResponse(Player player, DetectionSession session, String[] receivedLines) {
        Set<String> detectedModsForPlayer = detectedMods.computeIfAbsent(
            player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        
        Map<String, String> newDetections = new HashMap<>();
        
        // Check each line for translations
        for (int i = 0; i < Math.min(receivedLines.length, session.sentLines.size()); i++) {
            String sentLine = session.sentLines.get(i);
            String receivedLine = receivedLines[i];
            
            // Extract translation key from sent line
            String translationKey = extractTranslationKey(sentLine);
            if (translationKey == null || translationKey.isEmpty()) continue;
            
            // Skip vanilla translation keys
            if (vanillaTranslationKeys.contains(translationKey)) continue;
            
            // If sent and received are different, and received is not the translation key itself,
            // it means the key was translated, indicating the mod is present
            if (!receivedLine.equals(translationKey) && !receivedLine.isEmpty()) {
                String modName = translationKeyToMod.getOrDefault(translationKey, "Unknown Mod");
                
                // Add to detected mods
                detectedModsForPlayer.add(modName);
                
                if (!session.detectedMods.contains(modName)) {
                    session.detectedMods.add(modName);
                    newDetections.put(translationKey, modName);
                }
                
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Detected mod for " + player.getName() + 
                                         ": " + modName + " (key: " + translationKey + 
                                         ", translated to: " + receivedLine + ")");
                }
            }
        }
        
        // Send alerts for newly detected mods
        if (!newDetections.isEmpty() && config.isTranslationDetectionEnabled()) {
            // Prepare reason text
            String reason = "Using mods: " + 
                            String.join(", ", newDetections.values());
            
            // Send alert using the centralized violation processing
            plugin.getDetectionManager().processViolation(
                player,
                "TRANSLATION_KEY",
                reason
            );
            
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Debug] Processed violation for " + player.getName() + 
                                       " - detected mods via translation keys: " + 
                                       String.join(", ", newDetections.values()));
            }
        }
        
        // Cleanup
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            activeSessions.remove(player.getUniqueId());
        }, 5L);
    }
    
    /**
     * Extracts a translation key from a JSON formatted sign line
     */
    private String extractTranslationKey(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) return null;
        
        // Basic extraction - in a real implementation you'd want to use a proper JSON parser
        int translateIndex = jsonLine.indexOf("\"translate\":\"");
        if (translateIndex == -1) return null;
        
        int startIndex = translateIndex + 13; // Length of "\"translate\":\""
        int endIndex = jsonLine.indexOf("\"", startIndex);
        
        if (endIndex == -1) return null;
        
        return jsonLine.substring(startIndex, endIndex);
    }
    
    /**
     * Gets the set of detected mods for a player
     */
    public Set<String> getDetectedMods(UUID playerUuid) {
        return detectedMods.getOrDefault(playerUuid, Collections.emptySet());
    }
    
    /**
     * Cleans up data for a player when they quit
     */
    public void handlePlayerQuit(UUID playerUuid) {
        activeSessions.remove(playerUuid);
        detectedMods.remove(playerUuid);
        lastCheckTimes.remove(playerUuid);
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