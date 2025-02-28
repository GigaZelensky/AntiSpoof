package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;

    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Get or create player data
        PlayerData data = plugin.getPlayerDataMap().computeIfAbsent(
            playerUUID, uuid -> new PlayerData()
        );
        
        // Handle plugin messages based on the packet type
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            handlePluginMessage(playerUUID, packet.getChannelName(), packet.getData(), data);
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            handlePluginMessage(playerUUID, packet.getChannelName(), packet.getData(), data);
        }
    }
    
    private void handlePluginMessage(UUID playerUUID, String channel, byte[] data, PlayerData playerData) {
        if (isBrandChannel(channel)) {
            handleBrandPayload(data, playerData);
            return;
        }

        // Handle channel registration/unregistration (for Fabric/Forge mods)
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            handleChannelRegistration(channel, data, playerData);
        } else {
            // Direct channel usage
            playerData.addChannel(channel);
            logDebug("Direct channel used: " + channel);
        }
    }
    
    private boolean isBrandChannel(String channel) {
        return channel.equals("minecraft:brand") || channel.equals("MC|Brand");
    }
    
    private void handleBrandPayload(byte[] data, PlayerData playerData) {
        if (data.length == 0 || data.length > 64) {
            playerData.setClientBrand("invalid-brand-length-" + data.length);
            return;
        }
        
        // Skip the first byte which is the length
        byte[] brandBytes = new byte[data.length - 1];
        System.arraycopy(data, 1, brandBytes, 0, brandBytes.length);
        
        String brand = new String(brandBytes, StandardCharsets.UTF_8)
                .replace(" (Velocity)", "")  // Remove Velocity suffix
                .replaceAll("§.", "")        // Remove color codes
                .trim();
        
        // Strip color codes if configured
        if (plugin.getConfigManager().checkBrandFormatting()) {
            brand = ChatColor.stripColor(brand);
        }
        
        playerData.setClientBrand(brand);
        logDebug("Client brand set: " + brand);
    }
    
    private void handleChannelRegistration(String channel, byte[] data, PlayerData playerData) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        
        for (String registeredChannel : channels) {
            if (channel.equals("minecraft:register")) {
                playerData.addChannel(registeredChannel);
                logDebug("Channel registered: " + registeredChannel);
            } else {
                playerData.removeChannel(registeredChannel);
                logDebug("Channel unregistered: " + registeredChannel);
            }
        }
    }
    
    private void logDebug(String message) {
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] " + message);
        }
    }
}