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

    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }
    
    public String getAlertMessage() {
        return config.getString("messages.alert", "&8[&cAntiSpoof&8] &e%player% flagged! &c%reason%");
    }
    
    public String getConsoleAlertMessage() {
        return config.getString("messages.console-alert", "%player% flagged! %reason%");
    }
    
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
    
    public List<String> getBrandFormattingPunishments() {
        return config.getStringList("brand-formatting.punishments");
    }
    
    // Blocked Channels Check
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }
    
    public boolean isExactChannelMatchRequired() {
        return config.getBoolean("blocked-channels.exact-match", true);
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
    
    public boolean shouldPunishBlockedChannels() {
        return config.getBoolean("blocked-channels.punish", true);
    }
    
    public List<String> getBlockedChannelsPunishments() {
        return config.getStringList("blocked-channels.punishments");
    }
    
    // Blocked Brands Check
    public boolean isBlockedBrandsEnabled() {
        return config.getBoolean("blocked-brands.enabled", false);
    }
    
    public boolean isExactBrandMatchRequired() {
        return config.getBoolean("blocked-brands.exact-match", true);
    }
    
    public boolean isBrandWhitelistEnabled() {
        return config.getBoolean("blocked-brands.whitelist-mode", false);
    }
    
    public List<String> getBlockedBrands() {
        return config.getStringList("blocked-brands.values");
    }
    
    public boolean shouldPunishBlockedBrands() {
        return config.getBoolean("blocked-brands.punish", true);
    }
    
    public List<String> getBlockedBrandsPunishments() {
        return config.getStringList("blocked-brands.punishments");
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
}
