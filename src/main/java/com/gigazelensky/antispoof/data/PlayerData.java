package com.gigazelensky.antispoof.data;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private boolean alreadyPunished = false;

    // Add getters/setters
    public Set<String> getChannels() { return Collections.unmodifiableSet(channels); }
    public void addChannel(String channel) { channels.add(channel); }
    public void removeChannel(String channel) { channels.remove(channel); }
    
    // Tracking punishment status
    public boolean isAlreadyPunished() { return alreadyPunished; }
    public void setAlreadyPunished(boolean punished) { this.alreadyPunished = punished; }
}