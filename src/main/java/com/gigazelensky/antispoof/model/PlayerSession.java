package com.gigazelensky.antispoof.model;

import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a player's session with all relevant data
 */
public class PlayerSession {
    private final UUID playerUuid;
    private final String playerName;
    private String clientBrand;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private boolean alreadyPunished = false;
    private final long joinTime = System.currentTimeMillis();
    private boolean initialChannelsRegistered = false;
    private final Map<String, Boolean> violationFlags = new ConcurrentHashMap<>();
    private final List<Violation> pendingViolations = Collections.synchronizedList(new ArrayList<>());
    
    public PlayerSession(Player player) {
        this.playerUuid = player.getUniqueId();
        this.playerName = player.getName();
    }
    
    /**
     * Gets the player's UUID
     */
    public UUID getPlayerUuid() {
        return playerUuid;
    }
    
    /**
     * Gets the player's name
     */
    public String getPlayerName() {
        return playerName;
    }
    
    /**
     * Gets the player's client brand
     */
    public String getClientBrand() {
        return clientBrand;
    }
    
    /**
     * Sets the player's client brand
     */
    public void setClientBrand(String clientBrand) {
        this.clientBrand = clientBrand;
    }
    
    /**
     * Gets the player's registered channels
     */
    public Set<String> getChannels() {
        return Collections.unmodifiableSet(channels);
    }
    
    /**
     * Adds a channel to the player's registered channels
     * 
     * @param channel The channel to add
     * @return True if the channel was added, false if already present
     */
    public boolean addChannel(String channel) {
        return channels.add(channel);
    }
    
    /**
     * Removes a channel from the player's registered channels
     * 
     * @param channel The channel to remove
     * @return True if the channel was removed, false if not present
     */
    public boolean removeChannel(String channel) {
        return channels.remove(channel);
    }
    
    /**
     * Whether the player has already been punished
     */
    public boolean isAlreadyPunished() {
        return alreadyPunished;
    }
    
    /**
     * Sets whether the player has been punished
     */
    public void setPunished(boolean punished) {
        this.alreadyPunished = punished;
    }
    
    /**
     * Gets the player's join time
     */
    public long getJoinTime() {
        return joinTime;
    }
    
    /**
     * Whether the player's initial channels have been registered
     */
    public boolean isInitialChannelsRegistered() {
        return initialChannelsRegistered;
    }
    
    /**
     * Sets whether the player's initial channels have been registered
     */
    public void setInitialChannelsRegistered(boolean registered) {
        this.initialChannelsRegistered = registered;
    }
    
    /**
     * Adds a violation flag
     */
    public void addViolationFlag(String type) {
        violationFlags.put(type, true);
    }
    
    /**
     * Checks if a violation flag is set
     */
    public boolean hasViolationFlag(String type) {
        return violationFlags.getOrDefault(type, false);
    }
    
    /**
     * Adds a pending violation
     */
    public void addPendingViolation(Violation violation) {
        if (violation != null) {
            pendingViolations.add(violation);
        }
    }
    
    /**
     * Gets the pending violations
     */
    public List<Violation> getPendingViolations() {
        return Collections.unmodifiableList(pendingViolations);
    }
    
    /**
     * Clears all pending violations
     */
    public void clearPendingViolations() {
        pendingViolations.clear();
    }
    
    /**
     * Returns the number of channels registered
     */
    public int getChannelCount() {
        return channels.size();
    }
    
    /**
     * Returns true if the player has any channels registered
     */
    public boolean hasChannels() {
        return !channels.isEmpty();
    }
    
    /**
     * Returns true if the player has a client brand
     */
    public boolean hasBrand() {
        return clientBrand != null && !clientBrand.isEmpty();
    }
    
    /**
     * Returns true if the player has any pending violations
     */
    public boolean hasPendingViolations() {
        return !pendingViolations.isEmpty();
    }
    
    /**
     * Returns the violation flags
     */
    public Map<String, Boolean> getViolationFlags() {
        return Collections.unmodifiableMap(violationFlags);
    }
}