package com.gigazelensky.antispoof.managers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    
    // Cache for compiled regex patterns
    private final Map<String, Pattern> channelPatterns = new HashMap<>();
    private final Map<String, Pattern> brandPatterns = new HashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        // Clear and recompile regex patterns
        channelPatterns.clear();
        brandPatterns.clear();
        
        // Compile channel patterns
        List<String> channelRegexes = getBlockedChannels();
        for (String regex : channelRegexes) {
            try {
                channelPatterns.put(regex, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid channel regex pattern: " + regex + " - " + e.getMessage());
            }
        }
        
        // Compile brand patterns
        List<String> brandRegexes = getBlockedBrands();
        for (String regex : brandRegexes) {
            try {
                brandPatterns.put(regex, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid brand regex pattern: " + regex + " - " + e.getMessage());
            }
        }
    }

    public int getCheckDelay() {
        return config.getInt("delay-in-seconds", 3);
    }

    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }
    
    // Global alert messages (legacy)
    public String getAlertMessage() {
        return config.getString("messages.alert", "&8[&cAntiSpoof&8] &e%player% flagged! &c%reason%");
    }
    
    public String getConsoleAlertMessage() {
        return config.getString("messages.console-alert", "%player% flagged! %reason%");
    }
    
    // Multiple flags messages
    public String getMultipleFlagsMessage() {
        return config.getString("messages.multiple-flags", "&8[&cAntiSpoof&8] &e%player% has multiple violations: &c%reasons%");
    }
    
    public String getConsoleMultipleFlagsMessage() {
        return config.getString("messages.console-multiple-flags", "%player% has multiple violations: %reasons%");
    }
    
    // Color theme settings
    public ChatColor getPrimaryColor() {
        String colorName = config.getString("primary-color", "gold").toUpperCase();
        try {
            return ChatColor.valueOf(colorName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid primary color: " + colorName + ", defaulting to GOLD");
            return ChatColor.GOLD;
        }
    }
    
    public ChatColor getSecondaryColor() {
        String colorName = config.getString("secondary-color", "gray").toUpperCase();
        try {
            return ChatColor.valueOf(colorName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid secondary color: " + colorName + ", defaulting to GRAY");
            return ChatColor.GRAY;
        }
    }
    
    // Global punishments (legacy)
    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }
    
    // Vanilla Spoof Check (claims vanilla but has channels)
    public boolean isVanillaCheckEnabled() {
        return config.getBoolean("vanillaspoof-check.enabled", true);
    }
    
    public boolean shouldPunishVanillaCheck() {
        return config.getBoolean("vanillaspoof-check.punish", true);
    }
    
    public String getVanillaCheckAlertMessage() {
        return config.getString("vanillaspoof-check.alert-message", getAlertMessage());
    }
    
    public String getVanillaCheckConsoleAlertMessage() {
        return config.getString("vanillaspoof-check.console-alert-message", getConsoleAlertMessage());
    }
    
    public List<String> getVanillaCheckPunishments() {
        return config.getStringList("vanillaspoof-check.punishments");
    }
    
    // Non-Vanilla Check (anything not vanilla with channels)
    public boolean shouldBlockNonVanillaWithChannels() {
        return config.getBoolean("non-vanilla-check.enabled", false);
    }
    
    public boolean shouldPunishNonVanillaCheck() {
        return config.getBoolean("non-vanilla-check.punish", true);
    }
    
    public String getNonVanillaCheckAlertMessage() {
        return config.getString("non-vanilla-check.alert-message", getAlertMessage());
    }
    
    public String getNonVanillaCheckConsoleAlertMessage() {
        return config.getString("non-vanilla-check.console-alert-message", getConsoleAlertMessage());
    }
    
    public List<String> getNonVanillaCheckPunishments() {
        return config.getStringList("non-vanilla-check.punishments");
    }
    
    // Brand Formatting Check
    public boolean checkBrandFormatting() {
        return config.getBoolean("brand-formatting.enabled", true);
    }
    
    public boolean shouldPunishBrandFormatting() {
        return config.getBoolean("brand-formatting.punish", true);
    }
    
    public String getBrandFormattingAlertMessage() {
        return config.getString("brand-formatting.alert-message", getAlertMessage());
    }
    
    public String getBrandFormattingConsoleAlertMessage() {
        return config.getString("brand-formatting.console-alert-message", getConsoleAlertMessage());
    }
    
    public List<String> getBrandFormattingPunishments() {
        return config.getStringList("brand-formatting.punishments");
    }
    
    // Blocked Channels Check
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }
    
    public String getChannelWhitelistMode() {
        return config.getString("blocked-channels.whitelist-mode", "FALSE").toUpperCase();
    }
    
    public boolean isChannelWhitelistEnabled() {
        String mode = getChannelWhitelistMode();
        return mode.equals("SIMPLE") || mode.equals("STRICT");
    }
    
    public boolean isChannelWhitelistStrict() {
        return getChannelWhitelistMode().equals("STRICT");
    }
    
    public List<String> getBlockedChannels() {
        return config.getStringList("blocked-channels.values");
    }
    
    public String getBlockedChannelsAlertMessage() {
        return config.getString("blocked-channels.alert-message", getAlertMessage());
    }
    
    public String getBlockedChannelsConsoleAlertMessage() {
        return config.getString("blocked-channels.console-alert-message", getConsoleAlertMessage());
    }
    
    public boolean shouldPunishBlockedChannels() {
        return config.getBoolean("blocked-channels.punish", true);
    }
    
    public List<String> getBlockedChannelsPunishments() {
        return config.getStringList("blocked-channels.punishments");
    }
    
    // Channel regex matching
    public boolean matchesChannelPattern(String channel) {
        boolean isWhitelist = isChannelWhitelistEnabled();
        
        for (Map.Entry<String, Pattern> entry : channelPatterns.entrySet()) {
            if (entry.getValue().matcher(channel).matches()) {
                return true; // Channel matches a pattern
            }
        }
        
        return false; // No patterns matched
    }
    
    // Blocked Brands Check
    public boolean isBlockedBrandsEnabled() {
        return config.getBoolean("blocked-brands.enabled", false);
    }
    
    public boolean isBrandWhitelistEnabled() {
        return config.getBoolean("blocked-brands.whitelist-mode", false);
    }
    
    public boolean shouldCountNonWhitelistedBrandsAsFlag() {
        return config.getBoolean("blocked-brands.count-as-flag", true);
    }
    
    public List<String> getBlockedBrands() {
        return config.getStringList("blocked-brands.values");
    }
    
    public String getBlockedBrandsAlertMessage() {
        return config.getString("blocked-brands.alert-message", getAlertMessage());
    }
    
    public String getBlockedBrandsConsoleAlertMessage() {
        return config.getString("blocked-brands.console-alert-message", getConsoleAlertMessage());
    }
    
    public boolean shouldPunishBlockedBrands() {
        return config.getBoolean("blocked-brands.punish", true);
    }
    
    public List<String> getBlockedBrandsPunishments() {
        return config.getStringList("blocked-brands.punishments");
    }
    
    // Brand regex matching
    public boolean matchesBrandPattern(String brand) {
        // Check if brand matches any pattern in the list
        for (Map.Entry<String, Pattern> entry : brandPatterns.entrySet()) {
            if (entry.getValue().matcher(brand).matches()) {
                return true; // Brand matches a pattern
            }
        }
        return false; // No patterns matched
    }
    
    // Fixed method: Checks if a brand should be blocked based on whitelist/blacklist mode
    public boolean isBrandBlocked(String brand) {
        if (brand == null) return false;
        
        boolean whitelistMode = isBrandWhitelistEnabled();
        boolean matchesPattern = matchesBrandPattern(brand);
        
        if (whitelistMode) {
            // In whitelist mode: if matches ANY pattern, it's allowed
            return !matchesPattern;
        } else {
            // In blacklist mode: if matches ANY pattern, it's blocked
            return matchesPattern;
        }
    }
    
    // Bedrock Handling
    public String getBedrockHandlingMode() {
        return config.getString("bedrock-handling.mode", "EXEMPT").toUpperCase();
    }
    
    public boolean isBedrockExemptMode() {
        return getBedrockHandlingMode().equals("EXEMPT");
    }
    
    // Geyser Spoof Detection
    public boolean isPunishSpoofingGeyser() {
        return config.getBoolean("bedrock-handling.geyser-spoof.enabled", true);
    }
    
    public boolean shouldPunishGeyserSpoof() {
        return config.getBoolean("bedrock-handling.geyser-spoof.punish", true);
    }
    
    public String getGeyserSpoofAlertMessage() {
        return config.getString("bedrock-handling.geyser-spoof.alert-message", getAlertMessage());
    }
    
    public String getGeyserSpoofConsoleAlertMessage() {
        return config.getString("bedrock-handling.geyser-spoof.console-alert-message", getConsoleAlertMessage());
    }
    
    public List<String> getGeyserSpoofPunishments() {
        return config.getStringList("bedrock-handling.geyser-spoof.punishments");
    }
    
    // Bedrock Prefix Check
    public boolean isBedrockPrefixCheckEnabled() {
        return config.getBoolean("bedrock-handling.prefix-check.enabled", true);
    }
    
    public String getBedrockPrefix() {
        return config.getString("bedrock-handling.prefix-check.prefix", ".");
    }
    
    // Discord webhook settings
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord.enabled", false);
    }
    
    public String getDiscordWebhookUrl() {
        return config.getString("discord.webhook", "");
    }
    
    public String getDiscordEmbedTitle() {
        return config.getString("discord.embed-title", "**AntiSpoof Alert**");
    }
    
    public String getDiscordEmbedColor() {
        return config.getString("discord.embed-color", "#2AB7CA");
    }
    
    public List<String> getDiscordViolationContent() {
        return config.getStringList("discord.violation-content");
    }
}
