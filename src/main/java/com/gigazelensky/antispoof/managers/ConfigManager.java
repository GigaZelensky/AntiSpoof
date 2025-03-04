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
    
    // ===== Vanilla Check Settings =====
    public boolean isVanillaCheckEnabled() {
        return config.getBoolean("vanilla-check.enabled", true);
    }
    
    public boolean shouldPunishVanillaCheck() {
        return config.getBoolean("vanilla-check.punish", true);
    }
    
    public List<String> getVanillaCheckPunishments() {
        return config.getStringList("vanilla-check.punishments");
    }
    
    // ===== Non-Vanilla Check Settings =====
    public boolean shouldBlockNonVanillaWithChannels() {
        return config.getBoolean("non-vanilla-check.enabled", false);
    }
    
    public boolean shouldPunishNonVanillaCheck() {
        return config.getBoolean("non-vanilla-check.punish", true);
    }
    
    public List<String> getNonVanillaCheckPunishments() {
        return config.getStringList("non-vanilla-check.punishments");
    }
    
    // ===== Brand Formatting Check Settings =====
    public boolean checkBrandFormatting() {
        return config.getBoolean("brand-formatting.enabled", true);
    }
    
    public boolean shouldPunishBrandFormatting() {
        return config.getBoolean("brand-formatting.punish", true);
    }
    
    public List<String> getBrandFormattingPunishments() {
        return config.getStringList("brand-formatting.punishments");
    }
    
    // ===== Channel Detection Settings =====
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }
    
    public boolean isExactChannelMatchRequired() {
        return config.getBoolean("blocked-channels.exact-match", true);
    }
    
    public List<String> getBlockedChannels() {
        return config.getStringList("blocked-channels.values");
    }
    
    public String getChannelWhitelistMode() {
        String mode = config.getString("blocked-channels.whitelist-mode", "FALSE");
        // Ensure valid value
        if (!mode.equals("FALSE") && !mode.equals("SIMPLE") && !mode.equals("STRICT")) {
            return "FALSE";
        }
        return mode;
    }
    
    public boolean isChannelWhitelistEnabled() {
        String mode = getChannelWhitelistMode();
        return mode.equals("SIMPLE") || mode.equals("STRICT");
    }
    
    public boolean isChannelWhitelistStrict() {
        return getChannelWhitelistMode().equals("STRICT");
    }
    
    public boolean shouldPunishBlockedChannels() {
        return config.getBoolean("blocked-channels.punish", true);
    }
    
    public List<String> getBlockedChannelsPunishments() {
        return config.getStringList("blocked-channels.punishments");
    }
    
    // ===== Brand Detection Settings =====
    public boolean isBlockedBrandsEnabled() {
        return config.getBoolean("blocked-brands.enabled", false);
    }
    
    public boolean isExactBrandMatchRequired() {
        return config.getBoolean("blocked-brands.exact-match", true);
    }
    
    public List<String> getBlockedBrands() {
        return config.getStringList("blocked-brands.values");
    }
    
    public boolean isBrandWhitelistEnabled() {
        return config.getBoolean("blocked-brands.whitelist-mode", false);
    }
    
    public boolean shouldPunishBlockedBrands() {
        return config.getBoolean("blocked-brands.punish", true);
    }
    
    public List<String> getBlockedBrandsPunishments() {
        return config.getStringList("blocked-brands.punishments");
    }
    
    // ===== Bedrock Handling Settings =====
    public String getBedrockHandlingMode() {
        String mode = config.getString("bedrock-handling.mode", "EXEMPT");
        // Ensure valid value
        if (!mode.equals("IGNORE") && !mode.equals("EXEMPT")) {
            return "EXEMPT";
        }
        return mode;
    }
    
    public boolean isBedrockExemptMode() {
        return getBedrockHandlingMode().equals("EXEMPT");
    }
    
    public boolean isPunishSpoofingGeyser() {
        return config.getBoolean("bedrock-handling.geyser-spoof.enabled", true);
    }
    
    public boolean shouldPunishGeyserSpoof() {
        return config.getBoolean("bedrock-handling.geyser-spoof.punish", true);
    }
    
    public List<String> getGeyserSpoofPunishments() {
        return config.getStringList("bedrock-handling.geyser-spoof.punishments");
    }
    
    public boolean isBedrockPrefixCheckEnabled() {
        return config.getBoolean("bedrock-handling.prefix-check.enabled", true);
    }
    
    public String getBedrockPrefix() {
        return config.getString("bedrock-handling.prefix-check.prefix", ".");
    }
    
    // ===== Fallback for legacy support =====
    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }
}
