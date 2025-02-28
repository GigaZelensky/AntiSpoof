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

    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }

    public String getAlertMessage() {
        return config.getString("messages.alert", "&c[AntiSpoof] &e%player% &7is using suspicious client &f%brand%");
    }
}