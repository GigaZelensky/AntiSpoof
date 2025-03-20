package com.gigazelensky.antispoof.detection;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.model.ChannelRequirement;
import com.gigazelensky.antispoof.model.ClientProfile;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages client profiles loaded from configuration
 */
public class ProfileManager {
    private final AntiSpoofPlugin plugin;
    private final Map<String, ClientProfile> profiles = new HashMap<>();
    private ClientProfile globalProfile;
    private ClientProfile defaultProfile;
    
    public ProfileManager(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        loadProfiles();
    }
    
    /**
     * Loads all profiles from configuration
     */
    public void loadProfiles() {
        profiles.clear();
        
        // Load profiles from config
        ConfigurationSection profilesSection = plugin.getConfig().getConfigurationSection("profiles");
        if (profilesSection == null) {
            plugin.getLogger().warning("No profiles section found in config! Using defaults.");
            loadDefaultProfiles();
            return;
        }
        
        // Load global profile first
        globalProfile = loadProfile("global", profilesSection.getConfigurationSection("global"));
        
        // Load default profile
        defaultProfile = loadProfile("default", profilesSection.getConfigurationSection("default"));
        
        // Load all other profiles
        for (String key : profilesSection.getKeys(false)) {
            if (key.equals("global") || key.equals("default")) continue;
            
            ConfigurationSection profileSection = profilesSection.getConfigurationSection(key);
            if (profileSection == null) continue;
            
            ClientProfile profile = loadProfile(key, profileSection);
            profiles.put(key, profile);
        }
        
        plugin.getLogger().info("Loaded " + profiles.size() + " client profiles.");
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Loaded profiles: " + String.join(", ", profiles.keySet()));
        }
    }
    
    /**
     * Loads default profiles when none are configured
     */
    private void loadDefaultProfiles() {
        // Create a basic global profile
        globalProfile = new ClientProfile.Builder("global")
            .enabled(true)
            .build();
            
        // Create a basic default profile
        defaultProfile = new ClientProfile.Builder("default")
            .enabled(true)
            .flag(true)
            .alert(true)
            .alertMessage("&8[&cAntiSpoof&8] &e%player% using unknown client: &c%brand%")
            .consoleAlertMessage("%player% using unknown client: %brand%")
            .build();
            
        // Create vanilla profile
        ClientProfile vanillaProfile = new ClientProfile.Builder("vanilla")
            .enabled(true)
            .addBrandPattern("^vanilla$")
            .channelRequirement(new ChannelRequirement.Builder()
                .addBlockedChannel(".*")
                .alert(true)
                .alertMessage("&8[&cAntiSpoof&8] &e%player% &cVanilla client with plugin channels")
                .alertMessageConsole("%player% flagged! Vanilla client with plugin channels")
                .build())
            .flag(false)
            .alert(false)
            .build();
        
        profiles.put("vanilla", vanillaProfile);
        
        // Add a basic no-brand profile
        ClientProfile noBrandProfile = new ClientProfile.Builder("no-brand")
            .enabled(true)
            .addBrandPattern("^$")
            .flag(true)
            .alert(true)
            .alertMessage("&8[&cAntiSpoof&8] &e%player% flagged! &cNo client brand detected")
            .consoleAlertMessage("%player% flagged! No client brand detected")
            .build();
            
        profiles.put("no-brand", noBrandProfile);
    }
    
    /**
     * Loads a specific profile from configuration
     */
    private ClientProfile loadProfile(String id, ConfigurationSection section) {
        if (section == null) {
            if (id.equals("global")) {
                return new ClientProfile.Builder(id).build();
            } else if (id.equals("default")) {
                return new ClientProfile.Builder(id)
                    .flag(true)
                    .alert(true)
                    .build();
            }
            return null;
        }
        
        ClientProfile.Builder builder = new ClientProfile.Builder(id)
            .enabled(section.getBoolean("enabled", true))
            .flag(section.getBoolean("flag", false))
            .alert(section.getBoolean("alert", true))
            .alertMessage(section.getString("alert-message", "&8[&cAntiSpoof&8] &e%player% flagged!"))
            .consoleAlertMessage(section.getString("console-alert-message", "%player% flagged!"))
            .discordAlert(section.getBoolean("discord-alert", false))
            .discordAlertMessage(section.getString("discord-alert-message", ""))
            .punish(section.getBoolean("punish", false))
            .flagNonFloodgate(section.getBoolean("flag-non-floodgate", false));
            
        // Load patterns
        List<String> patterns = section.getStringList("patterns");
        for (String pattern : patterns) {
            builder.addBrandPattern(pattern);
        }
        
        // Load punishments
        List<String> punishments = section.getStringList("punishments");
        for (String punishment : punishments) {
            builder.addPunishment(punishment);
        }
        
        // Load channel requirements
        ConfigurationSection channelsSection = section.getConfigurationSection("channels");
        if (channelsSection != null) {
            ChannelRequirement.Builder channelBuilder = new ChannelRequirement.Builder()
                .alert(channelsSection.getBoolean("alert", true))
                .alertMessage(channelsSection.getString("alert-message", "&8[&cAntiSpoof&8] &e%player% flagged! &cChannel violation"))
                .alertMessageConsole(channelsSection.getString("alert-message-console", "%player% flagged! Channel violation"))
                .discordAlert(channelsSection.getBoolean("discord-alert", false))
                .discordAlertMessage(channelsSection.getString("discord-alert-message", "Channel violation"))
                .punish(channelsSection.getBoolean("punish", false));
                
            // Load required channels
            List<String> requiredChannels = channelsSection.getStringList("required");
            for (String channel : requiredChannels) {
                channelBuilder.addRequiredChannel(channel);
            }
            
            // Load blocked channels
            List<String> blockedChannels = channelsSection.getStringList("blocked");
            for (String channel : blockedChannels) {
                channelBuilder.addBlockedChannel(channel);
            }
            
            // Load punishments
            List<String> channelPunishments = channelsSection.getStringList("punishments");
            for (String punishment : channelPunishments) {
                channelBuilder.addPunishment(punishment);
            }
            
            builder.channelRequirement(channelBuilder.build());
        }
        
        return builder.build();
    }
    
    /**
     * Gets the global profile
     */
    public ClientProfile getGlobalProfile() {
        return globalProfile;
    }
    
    /**
     * Gets the default profile
     */
    public ClientProfile getDefaultProfile() {
        return defaultProfile;
    }
    
    /**
     * Gets a specific profile by ID
     */
    public ClientProfile getProfile(String id) {
        return profiles.get(id);
    }
    
    /**
     * Finds the profile that matches a brand
     */
    public ClientProfile findMatchingProfile(String brand) {
        if (brand == null) {
            // Special case for no-brand detection
            for (ClientProfile profile : profiles.values()) {
                if (profile.isEnabled() && profile.matches("")) {
                    return profile;
                }
            }
            return defaultProfile;
        }
        
        for (ClientProfile profile : profiles.values()) {
            if (profile.isEnabled() && profile.matches(brand)) {
                return profile;
            }
        }
        
        return defaultProfile;
    }
}