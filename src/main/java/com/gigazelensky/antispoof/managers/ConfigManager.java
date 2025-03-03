package com.gigazelensky.antispoof.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public int getCheckDelay() {
        return config.getInt("delay-in-seconds", 3);
    }

    public boolean shouldBlockNonVanillaWithChannels() {
        return config.getBoolean("block-non-vanilla-with-channels", true);
    }

    public boolean checkBrandFormatting() {
        return config.getBoolean("check-brand-formatting", true);
    }

    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }

    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }

    public String getAlertMessage() {
        return config.getString("messages.alert", "&8[&cAntiSpoof&8] &e%player% &7using &c%reason%");
    }
    
    // New methods for blocked channels
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }
    
    public boolean isExactChannelMatchRequired() {
        return config.getBoolean("blocked-channels.exact-match", true);
    }
    
    public List<String> getBlockedChannels() {
        return config.getStringList("blocked-channels.values");
    }
}