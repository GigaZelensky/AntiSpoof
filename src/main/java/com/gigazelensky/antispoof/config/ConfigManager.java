package com.gigazelensky.antispoof.config;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Manages plugin configuration
 */
public class ConfigManager {
    private final AntiSpoofPlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        reload();
    }
    
    /**
     * Reloads the plugin configuration
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        if (isDebugMode()) {
            plugin.getLogger().info("[Debug] Configuration reloaded");
        }
    }
    
    /**
     * Whether debug mode is enabled
     */
    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }
    
    /**
     * Get the delay before checking for client spoofing (in seconds)
     */
    public int getCheckDelay() {
        int delay = config.getInt("delay-in-seconds", 1);
        return Math.max(0, delay); // Ensure non-negative
    }
    
    /**
     * Get the bedrock handling mode (EXEMPT or IGNORE)
     */
    public String getBedrockHandlingMode() {
        String mode = config.getString("bedrock-handling.mode", "EXEMPT").toUpperCase();
        // Validate mode
        if (!mode.equals("EXEMPT") && !mode.equals("IGNORE")) {
            plugin.getLogger().warning("Invalid bedrock-handling.mode: " + mode + ", defaulting to EXEMPT");
            return "EXEMPT";
        }
        return mode;
    }
    
    /**
     * Whether to exempt Bedrock players from detection
     */
    public boolean isBedrockExemptMode() {
        return getBedrockHandlingMode().equals("EXEMPT");
    }
    
    /**
     * Whether to check for Bedrock players using prefix
     */
    public boolean isBedrockPrefixCheckEnabled() {
        return config.getBoolean("bedrock-handling.prefix-check.enabled", true);
    }
    
    /**
     * Get the Bedrock player username prefix
     */
    public String getBedrockPrefix() {
        return config.getString("bedrock-handling.prefix-check.prefix", ".");
    }
    
    /**
     * Whether to track modified channels
     */
    public boolean isModifiedChannelsEnabled() {
        return config.getBoolean("profiles.global.channels.modified-channels.enabled", true);
    }
    
    /**
     * Whether to send Discord alerts for modified channels
     */
    public boolean isModifiedChannelsDiscordEnabled() {
        return config.getBoolean("profiles.global.channels.modified-channels.discord-alert", false);
    }
    
    /**
     * Get the alert message for modified channels
     */
    public String getModifiedChannelsAlertMessage() {
        return config.getString("profiles.global.channels.modified-channels.alert-message", 
                              "&8[&cAntiSpoof&8] &e%player% modified channel: &f%channel%");
    }
    
    /**
     * Get the console alert message for modified channels
     */
    public String getModifiedChannelsConsoleAlertMessage() {
        return config.getString("profiles.global.channels.modified-channels.console-alert-message", 
                              "%player% modified channel: %channel%");
    }
    
    /**
     * Whether Discord webhook is enabled
     */
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord.enabled", false);
    }
    
    /**
     * Get the Discord webhook URL
     */
    public String getDiscordWebhookUrl() {
        return config.getString("discord.webhook", "");
    }
    
    /**
     * Get the Discord embed title
     */
    public String getDiscordEmbedTitle() {
        return config.getString("discord.embed-title", "**AntiSpoof Alert**");
    }
    
    /**
     * Get the Discord embed color
     */
    public String getDiscordEmbedColor() {
        return config.getString("discord.embed-color", "#2AB7CA");
    }
    
    /**
     * Whether update checker is enabled
     */
    public boolean isUpdateCheckerEnabled() {
        return config.getBoolean("update-checker.enabled", true);
    }
    
    /**
     * Whether to notify admins about updates when they join
     */
    public boolean isUpdateNotifyOnJoinEnabled() {
        return config.getBoolean("update-checker.notify-on-join", true);
    }
    
    /**
     * Get the raw configuration
     */
    public FileConfiguration getConfig() {
        return config;
    }
}