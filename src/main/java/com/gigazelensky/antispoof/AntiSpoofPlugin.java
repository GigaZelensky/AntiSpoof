package com.gigazelensky.antispoof;

import com.gigazelensky.antispoof.commands.AntiSpoofCommand;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.hooks.AntiSpoofPlaceholders;
import com.gigazelensky.antispoof.listeners.PacketListener;
import com.gigazelensky.antispoof.listeners.PlayerJoinListener;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.utils.DiscordWebhookHandler;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiSpoofPlugin extends JavaPlugin {
    
    private ConfigManager configManager;
    private DiscordWebhookHandler discordWebhookHandler;
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<String, String> playerBrands = new HashMap<>();
    private FloodgateApi floodgateApi = null;
    private PacketListener packetListener;
    
    // Add a map to track the last alert time for each player
    private final Map<UUID, Long> lastAlertTime = new HashMap<>();
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.discordWebhookHandler = new DiscordWebhookHandler(this);
        
        // Initialize PacketEvents
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
            .reEncodeByDefault(false)
            .checkForUpdates(false);
        PacketEvents.getAPI().load();
        
        // Try to initialize FloodgateApi
        if (getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                floodgateApi = FloodgateApi.getInstance();
                getLogger().info("Successfully hooked into Floodgate API!");
            } catch (Exception e) {
                getLogger().warning("Failed to hook into Floodgate API: " + e.getMessage());
            }
        }

        // Register PlaceholderAPI expansion if available
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AntiSpoofPlaceholders(this).register();
            getLogger().info("Successfully registered PlaceholderAPI expansion!");
        }
        
        // Register plugin channels for client brand
        if (getServer().getBukkitVersion().contains("1.13") || 
            Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13) {
            getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", 
                (channel, player, message) -> {
                    // Brand message format: [length][brand]
                    String brand = new String(message).substring(1);
                    playerBrands.put(player.getName(), brand);
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }
                });
        } else {
            getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", 
                (channel, player, message) -> {
                    // Brand message format: [length][brand]
                    String brand = new String(message).substring(1);
                    playerBrands.put(player.getName(), brand);
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }
                });
        }
        
        // Register event listeners
        new PlayerJoinListener(this).register();
        packetListener = new PacketListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        
        // Register command with tab completion
        AntiSpoofCommand commandExecutor = new AntiSpoofCommand(this);
        getCommand("antispoof").setExecutor(commandExecutor);
        getCommand("antispoof").setTabCompleter(commandExecutor);
        
        PacketEvents.getAPI().init();
        
        // Log Discord webhook status
        if (configManager.isDiscordWebhookEnabled()) {
            String webhookUrl = configManager.getDiscordWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                getLogger().info("Discord webhook integration is enabled.");
            } else {
                getLogger().warning("Discord webhook is enabled but no URL is configured!");
            }
        } else {
            if (configManager.isDebugMode()) {
                getLogger().info("Discord webhook integration is disabled.");
            }
        }
        
        getLogger().info("AntiSpoof v" + getDescription().getVersion() + " enabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public DiscordWebhookHandler getDiscordWebhookHandler() {
        return discordWebhookHandler;
    }
    
    public ConcurrentHashMap<UUID, PlayerData> getPlayerDataMap() {
        return playerDataMap;
    }

    public String getClientBrand(Player player) {
        if (player != null) {
            return playerBrands.get(player.getName());
        }
        return null;
    }
    
    public Map<String, String> getPlayerBrands() {
        return playerBrands;
    }
    
    public PacketListener getPacketListener() {
        return packetListener;
    }
    
    // Add a method to check if an alert can be sent for a player
    public boolean canSendAlert(UUID playerUUID) {
        long now = System.currentTimeMillis();
        Long lastAlert = lastAlertTime.get(playerUUID);
        
        // Allow alert if no previous alert or if it's been more than 3 seconds
        if (lastAlert == null || now - lastAlert > 3000) {
            lastAlertTime.put(playerUUID, now);
            return true;
        }
        
        return false;
    }
    
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        
        // Try to use Floodgate API first if available
        if (floodgateApi != null) {
            try {
                if (floodgateApi.isFloodgatePlayer(player.getUniqueId())) {
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Player " + player.getName() + 
                                       " identified as Bedrock player via Floodgate API");
                    }
                    return true;
                }
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    getLogger().warning("[Debug] Error checking Floodgate API for " + 
                                       player.getName() + ": " + e.getMessage());
                }
            }
        }
        
        // Fall back to prefix check if Floodgate isn't available or check failed
        if (configManager.isBedrockPrefixCheckEnabled()) {
            String prefix = configManager.getBedrockPrefix();
            if (player.getName().startsWith(prefix)) {
                if (configManager.isDebugMode()) {
                    getLogger().info("[Debug] Player " + player.getName() + 
                                   " identified as Bedrock player via prefix check");
                }
                return true;
            }
        }
        
        return false;
    }
    
    public boolean isSpoofingGeyser(Player player) {
        if (player == null) return false;
        
        // Don't check if not configured to detect Geyser spoofing
        if (!configManager.isPunishSpoofingGeyser()) {
            return false;
        }
        
        String brand = getClientBrand(player);
        if (brand == null) return false;
        
        // Check if brand contains "geyser" (case insensitive)
        boolean claimsGeyser = brand.toLowerCase().contains("geyser");
        
        // If player claims to be using Geyser but isn't detected as a Bedrock player
        return claimsGeyser && !isBedrockPlayer(player);
    }
    
    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;
        
        String brand = getClientBrand(player);
        if (brand == null) return false;
        
        // Check if player is a Bedrock player
        boolean isBedrockPlayer = isBedrockPlayer(player);
        
        // If player is a Bedrock player and we're set to ignore them, return false immediately
        if (isBedrockPlayer && configManager.getBedrockHandlingMode().equals("IGNORE")) {
            return false;
        }
        
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);
        if (data == null) return false;
        
        boolean hasChannels = !data.getChannels().isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        // Handle potential Geyser spoofing
        if (configManager.isPunishSpoofingGeyser() && isSpoofingGeyser(player)) {
            return true;
        }
        
        // Check for brand blocking - Now using configManager.isBrandBlocked method
        if (configManager.isBlockedBrandsEnabled()) {
            boolean brandBlocked = configManager.isBrandBlocked(brand);
            
            // Only consider as spoofing if count-as-flag is true
            if (brandBlocked && configManager.shouldCountNonWhitelistedBrandsAsFlag()) {
                return true;
            }
        }
        
        // Vanilla with channels check
        if (configManager.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
            return true;
        }
        
        // Non-vanilla with channels check
        if (configManager.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            return true;
        }
        
        // Channel whitelist/blacklist check
        if (configManager.isBlockedChannelsEnabled()) {
            if (configManager.isChannelWhitelistEnabled()) {
                // Whitelist mode
                if (!checkChannelWhitelist(data.getChannels())) {
                    return true;
                }
            } else {
                // Blacklist mode
                String blockedChannel = findBlockedChannel(data.getChannels());
                if (blockedChannel != null) {
                    return true;
                }
            }
        }
        
        // If we got here and player is a Bedrock player in EXEMPT mode, return false
        if (isBedrockPlayer && configManager.isBedrockExemptMode()) {
            return false;
        }
        
        return false;
    }
    
    private boolean checkChannelWhitelist(Set<String> playerChannels) {
        boolean strictMode = configManager.isChannelWhitelistStrict();
        List<String> whitelistedChannels = configManager.getBlockedChannels();
        
        // If no channels are whitelisted, then fail if player has any channels
        if (whitelistedChannels.isEmpty()) {
            return playerChannels.isEmpty();
        }
        
        // SIMPLE mode: Player must have at least one of the whitelisted channels
        if (!strictMode) {
            for (String playerChannel : playerChannels) {
                if (configManager.matchesChannelPattern(playerChannel)) {
                    return true; // Pass if player has at least one whitelisted channel
                }
            }
            return false; // Fail if player has no whitelisted channels
        } 
        // STRICT mode: Player must have ALL whitelisted channels AND only whitelisted channels
        else {
            // 1. Check if every player channel is whitelisted
            for (String playerChannel : playerChannels) {
                if (!configManager.matchesChannelPattern(playerChannel)) {
                    return false; // Fail if any player channel is not whitelisted
                }
            }
            
            // 2. Also check if player has ALL whitelisted channels
            for (String whitelistedChannel : whitelistedChannels) {
                boolean playerHasChannel = false;
                
                for (String playerChannel : playerChannels) {
                    try {
                        if (playerChannel.matches(whitelistedChannel)) {
                            playerHasChannel = true;
                            break;
                        }
                    } catch (Exception e) {
                        // If regex is invalid, just do direct match as fallback
                        if (playerChannel.equals(whitelistedChannel)) {
                            playerHasChannel = true;
                            break;
                        }
                    }
                }
                
                if (!playerHasChannel) {
                    return false; // Fail if player is missing any whitelisted channel
                }
            }
            
            // Player has passed both checks
            return true;
        }
    }
    
    public String findBlockedChannel(Set<String> playerChannels) {
        for (String playerChannel : playerChannels) {
            if (configManager.matchesChannelPattern(playerChannel)) {
                return playerChannel;
            }
        }
        
        return null; // No blocked channels found
    }

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        playerBrands.clear();
        lastAlertTime.clear();
        getLogger().info("AntiSpoof disabled!");
    }
}