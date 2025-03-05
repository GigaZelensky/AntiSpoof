package com.gigazelensky.antispoof.hooks;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public class AntiSpoofPlaceholders extends PlaceholderExpansion {
    private final AntiSpoofPlugin plugin;

    public AntiSpoofPlaceholders(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "antispoof";
    }

    @Override
    public @NotNull String getAuthor() {
        return "GigaZelensky";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // This is required or placeholders will stop working on reload
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        // %antispoof_brand%
        if (identifier.equals("brand")) {
            String brand = plugin.getClientBrand(player);
            return brand != null ? brand : "unknown";
        }

        // %antispoof_channels%
        if (identifier.equals("channels")) {
            UUID uuid = player.getUniqueId();
            PlayerData data = plugin.getPlayerDataMap().get(uuid);
            if (data == null) {
                return "none";
            }
            
            Set<String> channels = data.getChannels();
            if (channels.isEmpty()) {
                return "none";
            }
            
            return String.join(", ", channels);
        }

        // %antispoof_channels_count%
        if (identifier.equals("channels_count")) {
            UUID uuid = player.getUniqueId();
            PlayerData data = plugin.getPlayerDataMap().get(uuid);
            if (data == null) {
                return "0";
            }
            
            return String.valueOf(data.getChannels().size());
        }

        // %antispoof_is_spoofing%
        if (identifier.equals("is_spoofing")) {
            return plugin.isPlayerSpoofing(player) ? "true" : "false";
        }

        // %antispoof_is_bedrock%
        if (identifier.equals("is_bedrock")) {
            return plugin.isBedrockPlayer(player) ? "true" : "false";
        }

        return null; // Placeholder is not valid
    }
}
