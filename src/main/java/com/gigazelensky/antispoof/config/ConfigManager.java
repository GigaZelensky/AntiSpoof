package com.gigazelensky.antispoof.config;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

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
     * Resolves a configuration path with proper settings prefix handling
     * 
     * @param path The path to resolve
     * @return The full path with settings prefix if needed
     */
    private String resolvePath(String path) {
        // Try with settings prefix first
        String settingsPath = "settings." + path;
        if (config.contains(settingsPath)) {
            return settingsPath;
        }
        // Then try direct path
        return path;
    }
    
    /**
     * Whether debug mode is enabled
     */
    public boolean isDebugMode() {
        return config.getBoolean(resolvePath("debug"), false);
    }
    
    /**
     * Get the delay before checking for client spoofing (in seconds)
     */
    public int getCheckDelay() {
        int delay = config.getInt(resolvePath("delay-in-seconds"), 1);
        return Math.max(0, delay); // Ensure non-negative
    }
    
    /**
     * Whether to check for Bedrock players using prefix
     */
    public boolean isBedrockPrefixCheckEnabled() {
        return config.getBoolean(resolvePath("bedrock.prefix-check.enabled"), true);
    }
    
    /**
     * Get the Bedrock player username prefix
     */
    public String getBedrockPrefix() {
        return config.getString(resolvePath("bedrock.prefix-check.prefix"), ".");
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
        return config.getBoolean(resolvePath("discord.enabled"), false);
    }
    
    /**
     * Get the Discord webhook URL
     */
    public String getDiscordWebhookUrl() {
        return config.getString(resolvePath("discord.webhook"), "");
    }
    
    /**
     * Get the Discord embed title
     */
    public String getDiscordEmbedTitle() {
        return config.getString(resolvePath("discord.embed-title"), "**AntiSpoof Alert**");
    }
    
    /**
     * Get the Discord embed color
     */
    public String getDiscordEmbedColor() {
        return config.getString(resolvePath("discord.embed-color"), "#2AB7CA");
    }
    
    /**
     * Get the Discord webhook username
     */
    public String getDiscordUsername() {
        return config.getString(resolvePath("discord.username"), "");
    }
    
    /**
     * Get the Discord webhook avatar URL
     */
    public String getDiscordAvatarUrl() {
        return config.getString(resolvePath("discord.avatar-url"), "");
    }
    
    /**
     * Whether to mention @everyone in Discord alerts
     */
    public boolean shouldMentionEveryone() {
        return config.getBoolean(resolvePath("discord.mentions.everyone"), false);
    }
    
    /**
     * Get role IDs to mention in Discord alerts
     */
    public List<String> getMentionRoleIds() {
        return config.getStringList(resolvePath("discord.mentions.roles"));
    }
    
    /**
     * Whether to include player UUID in Discord alerts
     */
    public boolean shouldIncludePlayerUuid() {
        return config.getBoolean(resolvePath("discord.content.include-player-uuid"), false);
    }
    
    /**
     * Whether to include server name in Discord alerts
     */
    public boolean shouldIncludeServerName() {
        return config.getBoolean(resolvePath("discord.content.include-server-name"), true);
    }
    
    /**
     * Whether to include timestamp in Discord alerts
     */
    public boolean shouldIncludeTimestamp() {
        return config.getBoolean(resolvePath("discord.content.include-timestamp"), true);
    }
    
    /**
     * Whether to show client brand in Discord alerts
     */
    public boolean shouldShowBrand() {
        return config.getBoolean(resolvePath("discord.fields.show-brand"), true);
    }
    
    /**
     * Whether to show channels in Discord alerts
     */
    public boolean shouldShowChannels() {
        return config.getBoolean(resolvePath("discord.fields.show-channels"), true);
    }
    
    /**
     * Whether to show violations in Discord alerts
     */
    public boolean shouldShowViolations() {
        return config.getBoolean(resolvePath("discord.fields.show-violations"), true);
    }
    
    /**
     * Whether to show client version in Discord alerts
     */
    public boolean shouldShowVersion() {
        return config.getBoolean(resolvePath("discord.fields.show-version"), true);
    }
    
    /**
     * Whether to batch Discord alerts
     */
    public boolean shouldBatchDiscordAlerts() {
        return config.getBoolean(resolvePath("discord.batching.enabled"), true);
    }
    
    /**
     * Get Discord alert batching cooldown
     */
    public long getDiscordBatchingCooldown() {
        return config.getLong(resolvePath("discord.batching.cooldown"), 5000);
    }
    
    /**
     * Whether update checker is enabled
     */
    public boolean isUpdateCheckerEnabled() {
        return config.getBoolean(resolvePath("update-checker.enabled"), true);
    }
    
    /**
     * Whether to notify admins about updates when they join
     */
    public boolean isUpdateNotifyOnJoinEnabled() {
        return config.getBoolean(resolvePath("update-checker.notify-on-join"), true);
    }
    
    /**
     * Get the raw configuration
     */
    public FileConfiguration getConfig() {
        return config;
    }
}