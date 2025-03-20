package com.gigazelensky.antispoof.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents a client type profile
 */
public class ClientProfile {
    private final String id;
    private final boolean enabled;
    private final List<Pattern> brandPatterns;
    private final List<String> patternStrings;
    private final ChannelRequirement channelRequirement;
    private final boolean flag;
    private final boolean alert;
    private final String alertMessage;
    private final String consoleAlertMessage;
    private final boolean discordAlert;
    private final String discordAlertMessage;
    private final boolean punish;
    private final List<String> punishments;
    private final boolean flagNonFloodgate;  // Special flag for Geyser
    
    private ClientProfile(Builder builder) {
        this.id = builder.id;
        this.enabled = builder.enabled;
        this.brandPatterns = Collections.unmodifiableList(new ArrayList<>(builder.brandPatterns));
        this.patternStrings = Collections.unmodifiableList(new ArrayList<>(builder.patternStrings));
        this.channelRequirement = builder.channelRequirement;
        this.flag = builder.flag;
        this.alert = builder.alert;
        this.alertMessage = builder.alertMessage;
        this.consoleAlertMessage = builder.consoleAlertMessage;
        this.discordAlert = builder.discordAlert;
        this.discordAlertMessage = builder.discordAlertMessage;
        this.punish = builder.punish;
        this.punishments = Collections.unmodifiableList(new ArrayList<>(builder.punishments));
        this.flagNonFloodgate = builder.flagNonFloodgate;
    }
    
    /**
     * Gets the profile ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Whether this profile is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Checks if a brand matches this profile
     */
    public boolean matches(String brand) {
        if (brand == null) {
            // Special case for empty/null brands
            return patternStrings.contains("^$");
        }
        
        for (Pattern pattern : brandPatterns) {
            try {
                if (pattern.matcher(brand).matches()) {
                    return true;
                }
            } catch (Exception e) {
                // Fallback to direct comparison if regex fails
                if (brand.equals(pattern.pattern())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Gets the channel requirement for this profile
     */
    public ChannelRequirement getChannelRequirement() {
        return channelRequirement;
    }
    
    /**
     * Whether this profile should flag matching clients
     */
    public boolean shouldFlag() {
        return flag;
    }
    
    /**
     * Whether this profile should trigger alerts
     */
    public boolean shouldAlert() {
        return alert;
    }
    
    /**
     * Gets the alert message for this profile
     */
    public String getAlertMessage() {
        return alertMessage;
    }
    
    /**
     * Gets the console alert message for this profile
     */
    public String getConsoleAlertMessage() {
        return consoleAlertMessage;
    }
    
    /**
     * Whether this profile should trigger Discord alerts
     */
    public boolean shouldDiscordAlert() {
        return discordAlert;
    }
    
    /**
     * Gets the Discord alert message for this profile
     */
    public String getDiscordAlertMessage() {
        return discordAlertMessage;
    }
    
    /**
     * Whether this profile should trigger punishments
     */
    public boolean shouldPunish() {
        return punish;
    }
    
    /**
     * Gets the punishment commands for this profile
     */
    public List<String> getPunishments() {
        return punishments;
    }
    
    /**
     * Whether this profile should flag non-Floodgate players
     * (used for Geyser profile)
     */
    public boolean shouldFlagNonFloodgate() {
        return flagNonFloodgate;
    }
    
    /**
     * Gets the pattern strings for this profile
     */
    public List<String> getPatternStrings() {
        return patternStrings;
    }
    
    /**
     * Builder for creating ClientProfile instances
     */
    public static class Builder {
        private String id;
        private boolean enabled = true;
        private final List<Pattern> brandPatterns = new ArrayList<>();
        private final List<String> patternStrings = new ArrayList<>();
        private ChannelRequirement channelRequirement = new ChannelRequirement.Builder().build();
        private boolean flag = false;
        private boolean alert = true;
        private String alertMessage = "&8[&cAntiSpoof&8] &e%player% flagged!";
        private String consoleAlertMessage = "%player% flagged!";
        private boolean discordAlert = false;
        private String discordAlertMessage = "";
        private boolean punish = false;
        private final List<String> punishments = new ArrayList<>();
        private boolean flagNonFloodgate = false;
        
        public Builder(String id) {
            this.id = id;
        }
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder addBrandPattern(String pattern) {
            try {
                this.brandPatterns.add(Pattern.compile(pattern));
                this.patternStrings.add(pattern);
            } catch (PatternSyntaxException e) {
                // If pattern is invalid, use exact matching
                String safePattern = "^" + Pattern.quote(pattern) + "$";
                this.brandPatterns.add(Pattern.compile(safePattern));
                this.patternStrings.add(safePattern);
            }
            return this;
        }
        
        public Builder channelRequirement(ChannelRequirement requirement) {
            this.channelRequirement = requirement;
            return this;
        }
        
        public Builder flag(boolean flag) {
            this.flag = flag;
            return this;
        }
        
        public Builder alert(boolean alert) {
            this.alert = alert;
            return this;
        }
        
        public Builder alertMessage(String alertMessage) {
            if (alertMessage != null) {
                this.alertMessage = alertMessage;
            }
            return this;
        }
        
        public Builder consoleAlertMessage(String consoleAlertMessage) {
            if (consoleAlertMessage != null) {
                this.consoleAlertMessage = consoleAlertMessage;
            }
            return this;
        }
        
        public Builder discordAlert(boolean discordAlert) {
            this.discordAlert = discordAlert;
            return this;
        }
        
        public Builder discordAlertMessage(String discordAlertMessage) {
            if (discordAlertMessage != null) {
                this.discordAlertMessage = discordAlertMessage;
            }
            return this;
        }
        
        public Builder punish(boolean punish) {
            this.punish = punish;
            return this;
        }
        
        public Builder addPunishment(String punishment) {
            if (punishment != null && !punishment.isEmpty()) {
                this.punishments.add(punishment);
            }
            return this;
        }
        
        public Builder flagNonFloodgate(boolean flagNonFloodgate) {
            this.flagNonFloodgate = flagNonFloodgate;
            return this;
        }
        
        public ClientProfile build() {
            return new ClientProfile(this);
        }
    }
}