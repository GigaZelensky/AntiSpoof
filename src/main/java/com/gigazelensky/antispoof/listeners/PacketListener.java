package com.gigazelensky.antispoof.listeners;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
            handlePluginMessage(wrapper, getPlayerData(event.getUser().getUUID()));
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage wrapper = new WrapperConfigClientPluginMessage(event);
            handlePluginMessage(wrapper, getPlayerData(event.getUser().getUUID()));
        }
    }

    private PlayerData getPlayerData(UUID uuid) {
        return plugin.getPlayerDataMap().getOrDefault(uuid, null);
    }

    private void handlePluginMessage(WrapperPlayClientPluginMessage wrapper, PlayerData data) {
        String channel = wrapper.getChannelName();
        byte[] payload = wrapper.getData();

        if (isBrandChannel(channel)) {
            handleBrandPayload(payload, data);
            return;
        }

        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            handleChannelRegistration(channel, payload, data);
        } else {
            data.addChannel(channel);
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Channel used: " + channel);
            }
        }
    }
}