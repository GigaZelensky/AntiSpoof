package com.gigazelensky.antispoof.listener;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;

/**
 * Handles packet events for client brand and channel detection
 */
public class PacketListener extends PacketListenerAbstract {
    private final AntiSpoofPlugin plugin;
    
    public PacketListener(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Registers this listener with PacketEvents
     */
    public void register() {
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        
        // Skip if player has bypass permission
        if (player.hasPermission("antispoof.bypass")) return;
        
        // Handle plugin messages
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        } 
        else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        }
    }
    
    /**
     * Handles a plugin message packet
     */
    private void handlePluginMessage(Player player, String channel, byte[] data) {
        // Handle brand channel
        String brandChannel = getBrandChannel();
        if (channel.equals(brandChannel)) {
            handleBrandMessage(player, data);
            return;
        }
        
        // Handle channel registration/unregistration
        if (channel.equals("minecraft:register")) {
            handleChannelRegistration(player, data);
        } 
        else if (channel.equals("minecraft:unregister")) {
            handleChannelUnregistration(player, data);
        } 
        else {
            // Direct channel usage - register the channel
            plugin.getDetectionService().handleChannelRegistration(player, channel);
        }
    }
    
    /**
     * Handles a client brand message
     */
    private void handleBrandMessage(Player player, byte[] data) {
        // Brand message format: [length][brand]
        if (data.length <= 1) return;
        
        String brand = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Brand packet from " + player.getName() + ": " + brand);
        }
        
        plugin.getDetectionService().handleClientBrand(player, brand);
    }
    
    /**
     * Handles a channel registration message
     */
    private void handleChannelRegistration(Player player, byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Channel registration from " + player.getName() + 
                                  ": " + String.join(", ", channels));
        }
        
        for (String channel : channels) {
            if (channel.isEmpty()) continue;
            plugin.getDetectionService().handleChannelRegistration(player, channel);
        }
    }
    
    /**
     * Handles a channel unregistration message
     */
    private void handleChannelUnregistration(Player player, byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[Debug] Channel unregistration from " + player.getName() + 
                                  ": " + String.join(", ", channels));
        }
        
        for (String channel : channels) {
            if (channel.isEmpty()) continue;
            plugin.getDetectionService().handleChannelUnregistration(player, channel);
        }
    }
    
    /**
     * Gets the appropriate brand channel for the server version
     */
    private String getBrandChannel() {
        // Use version-appropriate channel name
        return plugin.getServer().getBukkitVersion().contains("1.13") || 
               Integer.parseInt(plugin.getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13 ?
               "minecraft:brand" : "MC|Brand";
    }
}