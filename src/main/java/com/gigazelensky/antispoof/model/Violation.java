package com.gigazelensky.antispoof.model;

import java.util.Objects;

/**
 * Represents a detected violation
 */
public class Violation {
    private final ViolationType type;
    private final String reason;
    private final String clientBrand;
    private final String violatedChannel;
    private final ClientProfile associatedProfile;
    
    public Violation(ViolationType type, String reason, String clientBrand, 
                   String violatedChannel, ClientProfile associatedProfile) {
        this.type = type;
        this.reason = reason;
        this.clientBrand = clientBrand;
        this.violatedChannel = violatedChannel;
        this.associatedProfile = associatedProfile;
    }
    
    /**
     * Gets the violation type
     */
    public ViolationType getType() {
        return type;
    }
    
    /**
     * Gets the reason for the violation
     */
    public String getReason() {
        return reason;
    }
    
    /**
     * Gets the client brand associated with the violation
     */
    public String getClientBrand() {
        return clientBrand;
    }
    
    /**
     * Gets the channel involved in the violation, if any
     */
    public String getViolatedChannel() {
        return violatedChannel;
    }
    
    /**
     * Gets the profile that detected the violation
     */
    public ClientProfile getAssociatedProfile() {
        return associatedProfile;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Violation violation = (Violation) o;
        return type == violation.type &&
               Objects.equals(reason, violation.reason) &&
               Objects.equals(clientBrand, violation.clientBrand) &&
               Objects.equals(violatedChannel, violation.violatedChannel);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, reason, clientBrand, violatedChannel);
    }
    
    @Override
    public String toString() {
        return "Violation{" +
               "type=" + type +
               ", reason='" + reason + '\'' +
               ", clientBrand='" + clientBrand + '\'' +
               ", violatedChannel='" + violatedChannel + '\'' +
               '}';
    }
    
    /**
     * Enumeration of violation types
     */
    public static enum ViolationType {
        BRAND_MISMATCH,
        REQUIRED_CHANNEL_MISSING,
        BLOCKED_CHANNEL_FOUND,
        NO_BRAND,
        GEYSER_SPOOFING,
        VANILLA_WITH_CHANNELS,
        UNKNOWN_BRAND
    }
}