package com.gigazelensky.antispoof.data;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {
    private String clientBrand;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();

    // Add getters/setters
    public String getClientBrand() { return clientBrand; }
    public void setClientBrand(String brand) { this.clientBrand = brand; }
    public Set<String> getChannels() { return Collections.unmodifiableSet(channels); }
    public void addChannel(String channel) { channels.add(channel); }
    public void removeChannel(String channel) { channels.remove(channel); }
}