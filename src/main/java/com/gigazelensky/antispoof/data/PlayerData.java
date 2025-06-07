package com.gigazelensky.antispoof.data;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
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
}