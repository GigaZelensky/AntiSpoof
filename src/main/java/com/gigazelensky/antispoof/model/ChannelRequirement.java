package com.gigazelensky.antispoof.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents channel requirements for a client profile
 */
public class ChannelRequirement {
    private final List<Pattern> required;
    private final List<String> requiredStrings;
    private final List<Pattern> blocked;
    private final List<String> blockedStrings;
    private final boolean alert;
    private final String alertMessage;
    private final String alertMessageConsole;
    private final boolean discordAlert;
    private final String discordAlertMessage;
    private final boolean punish;
    private final List<String> punishments;

    private ChannelRequirement(Builder builder) {
        this.required = Collections.unmodifiableList(new ArrayList<>(builder.required));
        this.requiredStrings = Collections.unmodifiableList(new ArrayList<>(builder.requiredStrings));
        this.blocked = Collections.unmodifiableList(new ArrayList<>(builder.blocked));
        this.blockedStrings = Collections.unmodifiableList(new ArrayList<>(builder.blockedStrings));
        this.alert = builder.alert;
        this.alertMessage = builder.alertMessage;
        this.alertMessageConsole = builder.alertMessageConsole;
        this.discordAlert = builder.discordAlert;
        this.discordAlertMessage = builder.discordAlertMessage;
        this.punish = builder.punish;
        this.punishments = Collections.unmodifiableList(new ArrayList<>(builder.punishments));
    }

    /**
     * Validates a player's channels against these requirements
     * 
     * @param playerChannels The player's channels
     * @param violations List to add violation reasons to
     * @return True if channels are valid, false otherwise
     */
    public boolean validateChannels(Set<String> playerChannels, List<String> violations) {
        boolean valid = true;
        
        // Check for required channels
        if (!required.isEmpty()) {
            for (int i = 0; i < required.size(); i++) {
                Pattern pattern = required.get(i);
                String patternStr = requiredStrings.get(i);
                boolean found = false;
                
                for (String channel : playerChannels) {
                    try {
                        if (pattern.matcher(channel).matches()) {
                            found = true;
                            break;
                        }
                    } catch (Exception e) {
                        // Fallback to simple contains check
                        if (channel.toLowerCase().contains(patternStr.toLowerCase())) {
                            found = true;
                            break;
                        }
                    }
                }
                
                if (!found) {
                    violations.add("Missing required channel: " + patternStr);
                    valid = false;
                }
            }
        }
        
        // Check for blocked channels
        if (!blocked.isEmpty()) {
            for (String channel : playerChannels) {
                for (int i = 0; i < blocked.size(); i++) {
                    Pattern pattern = blocked.get(i);
                    String patternStr = blockedStrings.get(i);
                    
                    try {
                        if (pattern.matcher(channel).matches()) {
                            violations.add("Using blocked channel: " + channel);
                            valid = false;
                            break;
                        }
                    } catch (Exception e) {
                        // Fallback to simple contains check
                        if (channel.toLowerCase().contains(patternStr.toLowerCase())) {
                            violations.add("Using blocked channel: " + channel);
                            valid = false;
                            break;
                        }
                    }
                }
            }
        }
        
        return valid;
    }

    /**
     * Whether to send alerts for channel violations
     */
    public boolean shouldAlert() {
        return alert;
    }

    /**
     * Gets the alert message for channel violations
     */
    public String getAlertMessage() {
        return alertMessage;
    }

    /**
     * Gets the console alert message for channel violations
     */
    public String getAlertMessageConsole() {
        return alertMessageConsole;
    }

    /**
     * Whether to send Discord alerts for channel violations
     */
    public boolean shouldDiscordAlert() {
        return discordAlert;
    }

    /**
     * Gets the Discord alert message for channel violations
     */
    public String getDiscordAlertMessage() {
        return discordAlertMessage;
    }

    /**
     * Whether to punish for channel violations
     */
    public boolean shouldPunish() {
        return punish;
    }

    /**
     * Gets the punishment commands for channel violations
     */
    public List<String> getPunishments() {
        return punishments;
    }
    
    /**
     * Gets the required channel patterns
     */
    public List<Pattern> getRequired() {
        return required;
    }
    
    /**
     * Gets the required channel pattern strings
     */
    public List<String> getRequiredStrings() {
        return requiredStrings;
    }
    
    /**
     * Gets the blocked channel patterns
     */
    public List<Pattern> getBlocked() {
        return blocked;
    }
    
    /**
     * Gets the blocked channel pattern strings
     */
    public List<String> getBlockedStrings() {
        return blockedStrings;
    }

    /**
     * Builder for creating ChannelRequirement instances
     */
    public static class Builder {
        private final List<Pattern> required = new ArrayList<>();
        private final List<String> requiredStrings = new ArrayList<>();
        private final List<Pattern> blocked = new ArrayList<>();
        private final List<String> blockedStrings = new ArrayList<>();
        private boolean alert = true;
        private String alertMessage = "&8[&cAntiSpoof&8] &e%player% flagged! &cChannels violation";
        private String alertMessageConsole = "%player% flagged! Channels violation";
        private boolean discordAlert = false;
        private String discordAlertMessage = "Channels violation";
        private boolean punish = false;
        private final List<String> punishments = new ArrayList<>();

        public Builder addRequiredChannel(String pattern) {
            try {
                this.required.add(Pattern.compile(pattern));
                this.requiredStrings.add(pattern);
            } catch (PatternSyntaxException e) {
                // If pattern is invalid, use exact matching
                String safePattern = "^" + Pattern.quote(pattern) + "$";
                this.required.add(Pattern.compile(safePattern));
                this.requiredStrings.add(safePattern);
            }
            return this;
        }

        public Builder addBlockedChannel(String pattern) {
            try {
                this.blocked.add(Pattern.compile(pattern));
                this.blockedStrings.add(pattern);
            } catch (PatternSyntaxException e) {
                // If pattern is invalid, use exact matching
                String safePattern = "^" + Pattern.quote(pattern) + "$";
                this.blocked.add(Pattern.compile(safePattern));
                this.blockedStrings.add(safePattern);
            }
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

        public Builder alertMessageConsole(String alertMessageConsole) {
            if (alertMessageConsole != null) {
                this.alertMessageConsole = alertMessageConsole;
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

        public ChannelRequirement build() {
            return new ChannelRequirement(this);
        }
    }
}