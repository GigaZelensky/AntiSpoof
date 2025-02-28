package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.nio.charset.StandardCharsets;

public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;

    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }

    // Existing packet handling with added brand validation
    private void handleBrandPayload(byte[] payload, PlayerData data) {
        String brand = new String(payload, StandardCharsets.UTF_8)
                .replace(" (Velocity)", "")
                .replaceAll("§.", "")
                .trim();
        
        if (plugin.getConfigManager().checkBrandFormatting()) {
            brand = ChatColor.stripColor(brand);
        }
        
        data.setClientBrand(brand);
    }
}