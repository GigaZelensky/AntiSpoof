package com.gigazelensky.antispoof.util;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for formatting messages with placeholders
 */
public class MessageFormatter {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([a-zA-Z_]+)%");
    
    /**
     * Formats a message with player and violation placeholders
     */
    public static String format(String message, Player player, List<String> reasons, 
                             String brand, String channel) {
        if (message == null) return "";
        
        // Apply color codes
        String formatted = ChatColor.translateAlternateColorCodes('&', message);
        
        // Apply placeholders
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(formatted);
        StringBuffer buffer = new StringBuffer();
        
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = getPlaceholderValue(placeholder, player, reasons, brand, channel);
            
            if (replacement != null) {
                // Escape $ and \ for the replacement
                replacement = replacement.replace("\\", "\\\\").replace("$", "\\$");
                matcher.appendReplacement(buffer, replacement);
            }
        }
        
        matcher.appendTail(buffer);
        return buffer.toString();
    }
    
    /**
     * Gets the value for a placeholder
     */
    private static String getPlaceholderValue(String placeholder, Player player, 
                                         List<String> reasons, String brand, String channel) {
        switch (placeholder.toLowerCase()) {
            case "player":
                return player != null ? player.getName() : "unknown";
                
            case "brand":
                return brand != null ? brand : "unknown";
                
            case "channel":
                return channel != null ? channel : "";
                
            case "reason":
                if (reasons != null && !reasons.isEmpty()) {
                    return reasons.get(0);
                }
                return "";
                
            case "reasons":
            case "violations":
                if (reasons != null && !reasons.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String reason : reasons) {
                        sb.append("\n• ").append(reason);
                    }
                    return sb.toString();
                }
                return "";
                
            default:
                return null;
        }
    }
    
    /**
     * Formats a message with just player placeholder
     */
    public static String format(String message, Player player) {
        return format(message, player, null, null, null);
    }
    
    /**
     * Formats a message with player and brand placeholders
     */
    public static String format(String message, Player player, String brand) {
        return format(message, player, null, brand, null);
    }
}