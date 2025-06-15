package com.gigazelensky.antispoof.data;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final Set<String> detectedMods = ConcurrentHashMap.newKeySet();
    private final Set<String> flaggedMods = ConcurrentHashMap.newKeySet();
    private final Set<String> flaggedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> translatedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> discordAlertMods = ConcurrentHashMap.newKeySet();
    private boolean alreadyPunished = false;
    private long joinTime = System.currentTimeMillis();
    private boolean initialChannelsRegistered = false;

    /**
     * @return An unmodifiable view of the channels associated with this player
     */
    public Set<String> getChannels() { 
        return Collections.unmodifiableSet(channels); 
    }
    
    /**
     * Adds a channel to the player's channel set
     * @param channel The channel to add
     */
    public void addChannel(String channel) { 
        channels.add(channel); 
    }
    
    /**
     * Removes a channel from the player's channel set
     * @param channel The channel to remove
     */
    public void removeChannel(String channel) {
        channels.remove(channel);
    }

    /**
     * Adds a detected mod label to this player's session
     * @param label The mod label
     */
    public void addDetectedMod(String label) {
        detectedMods.add(label);
    }

    public void addFlaggedMod(String label) {
        flaggedMods.add(label);
    }

    public void addFlaggedKey(String key) {
        flaggedKeys.add(key);
    }

    public void addTranslatedKey(String key) {
        translatedKeys.add(key);
    }

    public void addDiscordAlertMod(String label) {
        discordAlertMods.add(label);
    }

    /**
     * @return An unmodifiable view of detected mod labels
     */
    public Set<String> getDetectedMods() {
        return Collections.unmodifiableSet(detectedMods);
    }

    public Set<String> getFlaggedMods() {
        return Collections.unmodifiableSet(flaggedMods);
    }

    public Set<String> getFlaggedKeys() {
        return Collections.unmodifiableSet(flaggedKeys);
    }

    public Set<String> getTranslatedKeys() {
        return Collections.unmodifiableSet(translatedKeys);
    }

    public Set<String> getDiscordAlertMods() {
        return Collections.unmodifiableSet(discordAlertMods);
    }
    
    /**
     * @return Whether this player has already been punished
     */
    public boolean isAlreadyPunished() { 
        return alreadyPunished; 
    }
    
    /**
     * Sets whether this player has been punished
     * @param punished The punishment state
     */
    public void setAlreadyPunished(boolean punished) { 
        this.alreadyPunished = punished; 
    }
    
    /**
     * @return The time this player joined (in milliseconds)
     */
    public long getJoinTime() {
        return joinTime;
    }
    
    /**
     * @return Whether this player's initial channel registration phase has completed
     */
    public boolean isInitialChannelsRegistered() {
        return initialChannelsRegistered;
    }
    
    /**
     * Sets whether this player's initial channel registration phase has completed
     * @param registered The registration state
     */
    public void setInitialChannelsRegistered(boolean registered) {
        this.initialChannelsRegistered = registered;
    }

    public void clearTransientData() {
        flaggedMods.clear();
        flaggedKeys.clear();
        translatedKeys.clear();
        discordAlertMods.clear();
    }
}