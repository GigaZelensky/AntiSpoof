package com.gigazelensky.antispoof.managers;

import org.bukkit.configuration.ConfigurationSection;
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
    
    // Client brand configurations
    private boolean clientBrandsEnabled;
    private final Map<String, ClientBrandConfig> clientBrands = new HashMap<>();
    private ClientBrandConfig defaultBrandConfig;

    // Class to hold client brand configuration
    public static class ClientBrandConfig {
        private boolean enabled;
        private List<Pattern> patterns = new ArrayList<>();
        private List<String> patternStrings = new ArrayList<>();
        private boolean flag;
        private boolean alert;
        private boolean discordAlert;
        private String alertMessage;
        private String consoleAlertMessage;
        private boolean punish;
        private List<String> punishments = new ArrayList<>();
        private List<Pattern> requiredChannels = new ArrayList<>();
        private List<String> requiredChannelStrings = new ArrayList<>();
        private boolean strictCheck;
        // Added fields for required channels punishment
        private boolean requiredChannelsPunish;
        private List<String> requiredChannelsPunishments = new ArrayList<>();
        
        public boolean isEnabled() { return enabled; }
        public List<Pattern> getPatterns() { return patterns; }
        public List<String> getPatternStrings() { return patternStrings; }
        public boolean shouldFlag() { return flag; }
        public boolean shouldAlert() { return alert; }
        public boolean shouldDiscordAlert() { return discordAlert; }
        public String getAlertMessage() { return alertMessage; }
        public String getConsoleAlertMessage() { return consoleAlertMessage; }
        public boolean shouldPunish() { return punish; }
        public List<String> getPunishments() { return punishments; }
        public List<Pattern> getRequiredChannels() { return requiredChannels; }
        public List<String> getRequiredChannelStrings() { return requiredChannelStrings; }
        public boolean hasStrictCheck() { return strictCheck; }
        // Added getters for required channels punishment
        public boolean shouldPunishRequiredChannels() { return requiredChannelsPunish; }
        public List<String> getRequiredChannelsPunishments() { return requiredChannelsPunishments; }
    }

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
        
        // Compile channel patterns
        List<String> channelRegexes = getBlockedChannels();
        for (String regex : channelRegexes) {
            try {
                channelPatterns.put(regex, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid channel regex pattern: " + regex + " - " + e.getMessage());
            }
        }
        
        // Load client brand configurations
        loadClientBrandConfigs();
    }
    
    /**
     * Load all client brand configurations from config
     */
    private void loadClientBrandConfigs() {
        // Clear existing configurations
        clientBrands.clear();
        
        // Check if client brands system is enabled
        clientBrandsEnabled = config.getBoolean("client-brands.enabled", true);
        
        // Load default brand config
        ConfigurationSection defaultSection = config.getConfigurationSection("client-brands.default");
        defaultBrandConfig = new ClientBrandConfig();
        
        if (defaultSection != null) {
            defaultBrandConfig.enabled = true;
            defaultBrandConfig.flag = defaultSection.getBoolean("flag", true);
            defaultBrandConfig.alert = defaultSection.getBoolean("alert", true);
            defaultBrandConfig.discordAlert = defaultSection.getBoolean("discord-alert", false);
            defaultBrandConfig.alertMessage = defaultSection.getString("alert-message", 
                "&8[&cAntiSpoof&8] &7%player% using unknown client: &e%brand%");
            defaultBrandConfig.consoleAlertMessage = defaultSection.getString("console-alert-message", 
                "%player% using unknown client: %brand%");
            defaultBrandConfig.punish = defaultSection.getBoolean("punish", false);
            defaultBrandConfig.punishments = defaultSection.getStringList("punishments");
        } else {
            // Set up default values if section is missing
            defaultBrandConfig.enabled = true;
            defaultBrandConfig.flag = true;
            defaultBrandConfig.alert = true;
            defaultBrandConfig.discordAlert = false;
            defaultBrandConfig.alertMessage = "&8[&cAntiSpoof&8] &7%player% using unknown client: &e%brand%";
            defaultBrandConfig.consoleAlertMessage = "%player% using unknown client: %brand%";
            defaultBrandConfig.punish = false;
            defaultBrandConfig.punishments = new ArrayList<>();
        }
        
        // Load individual brand configurations
        ConfigurationSection brandsSection = config.getConfigurationSection("client-brands.brands");
        if (brandsSection != null) {
            for (String brandKey : brandsSection.getKeys(false)) {
                ConfigurationSection brandSection = brandsSection.getConfigurationSection(brandKey);
                if (brandSection != null) {
                    ClientBrandConfig brandConfig = loadBrandConfig(brandSection);
                    clientBrands.put(brandKey, brandConfig);
                    
                    if (isDebugMode()) {
                        plugin.getLogger().info("[Debug] Loaded client brand config: " + brandKey + 
                                              " with " + brandConfig.patterns.size() + " patterns");
                    }
                }
            }
        }
    }
    
    /**
     * Load a specific brand configuration from a config section
     */
    private ClientBrandConfig loadBrandConfig(ConfigurationSection section) {
        ClientBrandConfig brandConfig = new ClientBrandConfig();
        brandConfig.enabled = section.getBoolean("enabled", true);
        brandConfig.flag = section.getBoolean("flag", false);
        brandConfig.alert = section.getBoolean("alert", true);
        brandConfig.discordAlert = section.getBoolean("discord-alert", false);
        brandConfig.alertMessage = section.getString("alert-message", defaultBrandConfig.alertMessage);
        brandConfig.consoleAlertMessage = section.getString("console-alert-message", defaultBrandConfig.consoleAlertMessage);
        brandConfig.punish = section.getBoolean("punish", false);
        brandConfig.punishments = section.getStringList("punishments");
        brandConfig.strictCheck = section.getBoolean("strict-check", false);
        
        // Added loading for required channels punishment fields
        brandConfig.requiredChannelsPunish = section.getBoolean("required-channels-punish", false);
        brandConfig.requiredChannelsPunishments = section.getStringList("required-channels-punishments");
        
        // Load and compile pattern strings
        List<String> patterns = section.getStringList("values");
        brandConfig.patternStrings.addAll(patterns);
        
        for (String pattern : patterns) {
            try {
                brandConfig.patterns.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid brand pattern: " + pattern + " - " + e.getMessage());
                // Add a simple exact match pattern as fallback
                brandConfig.patterns.add(Pattern.compile("^" + Pattern.quote(pattern) + "$"));
            }
        }
        
        // Load and compile required channel patterns
        List<String> requiredChannels = section.getStringList("required-channels");
        brandConfig.requiredChannelStrings.addAll(requiredChannels);
        
        for (String channel : requiredChannels) {
            try {
                brandConfig.requiredChannels.add(Pattern.compile(channel));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid required channel pattern: " + channel + " - " + e.getMessage());
                // Add a simple exact match pattern as fallback
                brandConfig.requiredChannels.add(Pattern.compile("^" + Pattern.quote(channel) + "$"));
            }
        }
        
        return brandConfig;
    }

    public int getCheckDelay() {
        return config.getInt("delay-in-seconds", 3);
    }

    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }
    
    /**
     * @return Whether the client brands system is enabled
     */
    public boolean isClientBrandsEnabled() {
        return clientBrandsEnabled;
    }
    
    /**
     * Check if a brand matches any configured client brands
     * @param brand The brand to check
     * @return The configured brand key or null if no match
     */
    public String getMatchingClientBrand(String brand) {
        if (brand == null || !clientBrandsEnabled) return null;
        
        for (Map.Entry<String, ClientBrandConfig> entry : clientBrands.entrySet()) {
            ClientBrandConfig brandConfig = entry.getValue();
            
            if (!brandConfig.isEnabled()) continue;
            
            for (Pattern pattern : brandConfig.getPatterns()) {
                try {
                    if (pattern.matcher(brand).matches()) {
                        return entry.getKey();
                    }
                } catch (Exception e) {
                    // If regex fails, try direct comparison
                    if (brand.equals(pattern.pattern())) {
                        return entry.getKey();
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get the configuration for a specific client brand
     * @param brandKey The brand key to get configuration for
     * @return The brand configuration or default if not found
     */
    public ClientBrandConfig getClientBrandConfig(String brandKey) {
        return clientBrands.getOrDefault(brandKey, defaultBrandConfig);
    }
    
    /**
     * Check if a channel matches any of the required channels for a brand
     * @param brandKey The brand key to check
     * @param channel The channel to check
     * @return True if the channel matches a required channel pattern
     */
    public boolean matchesRequiredChannel(String brandKey, String channel) {
        ClientBrandConfig brandConfig = getClientBrandConfig(brandKey);
        
        if (brandConfig.getRequiredChannels().isEmpty()) {
            return true; // No required channels means any channel is fine
        }
        
        for (Pattern pattern : brandConfig.getRequiredChannels()) {
            try {
                if (pattern.matcher(channel).matches()) {
                    return true;
                }
            } catch (Exception e) {
                // If regex fails, try direct comparison
                if (channel.equals(pattern.pattern())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if a brand has strict checking enabled (must have no channels)
     * @param brandKey The brand key to check
     * @return True if strict checking is enabled for this brand
     */
    public boolean hasStrictChecking(String brandKey) {
        return getClientBrandConfig(brandKey).hasStrictCheck();
    }
    
    /**
     * @return Whether brand detection is enabled (maps to client-brands.enabled)
     */
    public boolean isBlockedBrandsEnabled() {
        return isClientBrandsEnabled();
    }
    
    /**
     * Checks if a brand is blocked under the new system
     * @param brand The brand to check
     * @return True if the brand is blocked, false otherwise
     */
    public boolean isBrandBlocked(String brand) {
        if (!isClientBrandsEnabled()) return false;
        
        // Match to a client brand
        String matchedBrandKey = getMatchingClientBrand(brand);
        if (matchedBrandKey != null) {
            // Get the client brand config
            ClientBrandConfig brandConfig = getClientBrandConfig(matchedBrandKey);
            // Return whether this brand should be flagged
            return brandConfig.shouldFlag();
        }
        
        // If no brand pattern matched, use the default config for unknown brands
        return defaultBrandConfig.shouldFlag();
    }
    
    /**
     * @return Whether non-whitelisted brands should be flagged
     */
    public boolean shouldCountNonWhitelistedBrandsAsFlag() {
        // In the new system, this is controlled per brand by the 'flag' parameter
        // Default to true for backward compatibility
        return true;
    }
    
    /**
     * @return Whether brand whitelist mode is enabled (backward compatibility)
     */
    public boolean isBrandWhitelistEnabled() {
        // In the new system, we don't have a global whitelist mode
        // But if any brand with flag: false exists, we can consider it a whitelist
        for (ClientBrandConfig brandConfig : clientBrands.values()) {
            if (brandConfig.isEnabled() && !brandConfig.shouldFlag()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get all brand patterns for backward compatibility
     * @return List of brand patterns
     */
    public List<String> getBlockedBrands() {
        List<String> result = new ArrayList<>();
        
        // Collect all brand patterns
        for (ClientBrandConfig brandConfig : clientBrands.values()) {
            if (brandConfig.isEnabled()) {
                result.addAll(brandConfig.getPatternStrings());
            }
        }
        
        return result;
    }
    
    /**
     * Get the alert message for blocked brands (backward compatibility)
     * @return The alert message
     */
    public String getBlockedBrandsAlertMessage() {
        // Use the default client brand alert message
        return defaultBrandConfig.getAlertMessage();
    }
    
    /**
     * Get the console alert message for blocked brands (backward compatibility)
     * @return The console alert message
     */
    public String getBlockedBrandsConsoleAlertMessage() {
        // Use the default client brand console alert message
        return defaultBrandConfig.getConsoleAlertMessage();
    }
    
    /**
     * Check if Discord alerts are enabled for blocked brands (backward compatibility)
     * @return True if Discord alerts are enabled, false otherwise
     */
    public boolean isBlockedBrandsDiscordAlertEnabled() {
        // Use the default client brand discord alert setting
        return defaultBrandConfig.shouldDiscordAlert();
    }
    
    /**
     * Get punishment commands for blocked brands (backward compatibility)
     * @return List of punishment commands
     */
    public List<String> getBlockedBrandsPunishments() {
        // Use the default client brand punishments
        return defaultBrandConfig.getPunishments();
    }
    
    /**
     * Check if punishment is enabled for blocked brands (backward compatibility)
     * @return True if punishment is enabled, false otherwise
     */
    public boolean shouldPunishBlockedBrands() {
        // Use the default client brand punishment setting
        return defaultBrandConfig.shouldPunish();
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
    
    // Global punishments (legacy)
    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }
    
    // No Brand Check
    public boolean isNoBrandCheckEnabled() {
        return config.getBoolean("no-brand-check.enabled", true);
    }
    
    public boolean isNoBrandDiscordAlertEnabled() {
        return config.getBoolean("no-brand-check.discord-alert", true);
    }
    
    public boolean shouldPunishNoBrand() {
        return config.getBoolean("no-brand-check.punish", false);
    }
    
    public String getNoBrandAlertMessage() {
        return config.getString("no-brand-check.alert-message", 
                               "&8[&cAntiSpoof&8] &e%player% flagged! &cNo client brand detected");
    }
    
    public String getNoBrandConsoleAlertMessage() {
        return config.getString("no-brand-check.console-alert-message", 
                               "%player% flagged! No client brand detected");
    }
    
    public List<String> getNoBrandPunishments() {
        return config.getStringList("no-brand-check.punishments");
    }
    
    // Vanilla Spoof Check (claims vanilla but has channels)
    public boolean isVanillaCheckEnabled() {
        return config.getBoolean("vanillaspoof-check.enabled", true);
    }
    
    public boolean isVanillaCheckDiscordAlertEnabled() {
        return config.getBoolean("vanillaspoof-check.discord-alert", true);
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
    
    public boolean isNonVanillaCheckDiscordAlertEnabled() {
        return config.getBoolean("non-vanilla-check.discord-alert", false);
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
    
    // Blocked Channels Check
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }
    
    public boolean isBlockedChannelsDiscordAlertEnabled() {
        return config.getBoolean("blocked-channels.discord-alert", false);
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
    
    // Added whitelist message methods
    public String getChannelWhitelistAlertMessage() {
        return config.getString("blocked-channels.whitelist-alert-message", 
            "&8[&cAntiSpoof&8] &e%player% flagged! &cChannels don't match whitelist requirements");
    }
    
    public String getChannelWhitelistConsoleAlertMessage() {
        return config.getString("blocked-channels.whitelist-console-alert-message", 
            "%player% flagged! Channels don't match whitelist requirements");
    }
    
    public boolean shouldPunishBlockedChannels() {
        return config.getBoolean("blocked-channels.punish", true);
    }
    
    public List<String> getBlockedChannelsPunishments() {
        return config.getStringList("blocked-channels.punishments");
    }
    
    // Modified Channels alerts
    public boolean isModifiedChannelsEnabled() {
        return config.getBoolean("blocked-channels.modifiedchannels.enabled", false);
    }
    
    public boolean isModifiedChannelsDiscordEnabled() {
        return config.getBoolean("blocked-channels.modifiedchannels.discord-alert", false);
    }
    
    public String getModifiedChannelsAlertMessage() {
        return config.getString("blocked-channels.modifiedchannels.alert-message", 
                               "&8[&cAntiSpoof&8] &e%player% modified channel: &f%channel%");
    }
    
    public String getModifiedChannelsConsoleAlertMessage() {
        return config.getString("blocked-channels.modifiedchannels.console-alert-message", 
                               "%player% modified channel: %channel%");
    }
    
    // Channel regex matching
    public boolean matchesChannelPattern(String channel) {
        if (channel == null) return false;
        
        for (Map.Entry<String, Pattern> entry : channelPatterns.entrySet()) {
            try {
                if (entry.getValue().matcher(channel).matches()) {
                    return true; // Channel matches a pattern
                }
            } catch (Exception e) {
                // If there's any error with the pattern, try direct comparison as fallback
                if (channel.equals(entry.getKey())) {
                    return true;
                }
            }
        }
        
        return false; // No patterns matched
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
    
    public boolean isGeyserSpoofDiscordAlertEnabled() {
        return config.getBoolean("bedrock-handling.geyser-spoof.discord-alert", true);
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
    
    // Global Alert settings
    public boolean isJoinBrandAlertsEnabled() {
        return config.getBoolean("global-alerts.join-brand-alerts", false);
    }
    
    public boolean isInitialChannelsAlertsEnabled() {
        return config.getBoolean("global-alerts.initial-channels-alerts", false);
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
    
    /**
     * Checks if update checking is enabled
     * @return True if update checking is enabled, false otherwise
     */
    public boolean isUpdateCheckerEnabled() {
        return config.getBoolean("update-checker.enabled", true);
    }

    /**
     * Checks if join notifications for updates are enabled
     * @return True if join notifications are enabled, false otherwise
     */
    public boolean isUpdateNotifyOnJoinEnabled() {
        return config.getBoolean("update-checker.notify-on-join", true);
    }
}