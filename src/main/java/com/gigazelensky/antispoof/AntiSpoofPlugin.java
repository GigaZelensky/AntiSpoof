package com.gigazelensky.antispoof;

import com.gigazelensky.antispoof.commands.AntiSpoofCommand;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.hooks.AntiSpoofPlaceholders;
import com.gigazelensky.antispoof.listeners.PermissionChangeListener;
import com.gigazelensky.antispoof.listeners.PlayerEventListener;
import com.gigazelensky.antispoof.managers.AlertManager;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.managers.DetectionManager;
import com.gigazelensky.antispoof.utils.DiscordWebhookHandler;
import com.gigazelensky.antispoof.utils.VersionChecker;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiSpoofPlugin extends JavaPlugin {
    
    private ConfigManager configManager;
    private DiscordWebhookHandler discordWebhookHandler;
    private AlertManager alertManager;
    private DetectionManager detectionManager;
    private PlayerEventListener playerEventListener;
    private VersionChecker versionChecker;
    
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    private FloodgateApi floodgateApi = null;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // Initialize managers
        this.configManager = new ConfigManager(this);
        this.alertManager = new AlertManager(this);
        this.detectionManager = new DetectionManager(this);
        this.discordWebhookHandler = new DiscordWebhookHandler(this);
        
        // Initialize version checker
        this.versionChecker = new VersionChecker(this);
        
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
        registerClientBrandChannel();
        
        // Register unified event listener
        this.playerEventListener = new PlayerEventListener(this);
        this.playerEventListener.register();
        
        // Register permission change listener
        PermissionChangeListener permissionListener = new PermissionChangeListener(this);
        getServer().getPluginManager().registerEvents(permissionListener, this);
        
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
        
        // After server startup, initialize the alert recipients list
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                alertManager.registerPlayer(player);
            }
            if (configManager.isDebugMode()) {
                getLogger().info("[Debug] Initialized alert recipients list");
            }
        }, 40L); // 2 seconds after server fully starts
        
        getLogger().info("AntiSpoof v" + getDescription().getVersion() + " enabled!");
    }
    
    private void registerClientBrandChannel() {
        // Use version-appropriate channel name
        String channelName = getServer().getBukkitVersion().contains("1.13") || 
                            Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13 ?
                            "minecraft:brand" : "MC|Brand";
        
        getServer().getMessenger().registerIncomingPluginChannel(this, channelName, 
            (channel, player, message) -> {
                // Brand message format: [length][brand]
                String brand = new String(message).substring(1);
                
                // Store brand by UUID to avoid name conflicts
                playerBrands.put(player.getUniqueId(), brand);
                
                if (configManager.isDebugMode()) {
                    getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                }
                
                // Trigger a check for this player if brand is now known
                detectionManager.checkPlayerAsync(player, false);
            });
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public DiscordWebhookHandler getDiscordWebhookHandler() {
        return discordWebhookHandler;
    }
    
    public AlertManager getAlertManager() {
        return alertManager;
    }
    
    public DetectionManager getDetectionManager() {
        return detectionManager;
    }
    
    public ConcurrentHashMap<UUID, PlayerData> getPlayerDataMap() {
        return playerDataMap;
    }

    public String getClientBrand(Player player) {
        if (player != null) {
            return playerBrands.get(player.getUniqueId());
        }
        return null;
    }
    
    public Map<UUID, String> getPlayerBrands() {
        return playerBrands;
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

    /**
     * Comprehensive method to check if a player is spoofing
     * @param player The player to check
     * @return True if the player is found to be spoofing, false otherwise
     */
    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;
        
        String brand = getClientBrand(player);
        
        // Check for missing brand first
        if (brand == null && configManager.isNoBrandCheckEnabled()) {
            return true;
        }
        
        // Skip other checks if brand is null
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
        
        // Check for Geyser spoofing first
        if (configManager.isPunishSpoofingGeyser() && isSpoofingGeyser(player)) {
            return true;
        }
        
        // Vanilla client check - this takes precedence over whitelist checks
        // A vanilla client should have no plugin channels
        if (configManager.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
            return true;
        }
        
        // Check for brand blocking
        if (configManager.isBlockedBrandsEnabled()) {
            boolean brandBlocked = configManager.isBrandBlocked(brand);
            
            // Only consider as spoofing if count-as-flag is true
            if (brandBlocked && configManager.shouldCountNonWhitelistedBrandsAsFlag()) {
                return true;
            }
        }
        
        // Non-vanilla with channels check
        if (configManager.shouldBlockNonVanillaWithChannels() && !claimsVanilla && hasChannels) {
            return true;
        }
        
        // Channel whitelist/blacklist check - only if not already flagged by vanilla check
        if (configManager.isBlockedChannelsEnabled() && hasChannels) {
            if (configManager.isChannelWhitelistEnabled()) {
                // Whitelist mode
                if (!detectionManager.checkChannelWhitelist(data.getChannels())) {
                    return true;
                }
            } else {
                // Blacklist mode
                String blockedChannel = detectionManager.findBlockedChannel(data.getChannels());
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

    /**
     * Checks if a player is spoofing Geyser
     * @param player The player to check
     * @return True if the player is spoofing Geyser, false otherwise
     */
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
    
    /**
     * Handles player quit - clean up all resources and alert states
     * @param uuid The player's UUID
     */
    public void handlePlayerQuit(UUID uuid) {
        // Clean up all tracked data for this player
        getDetectionManager().handlePlayerQuit(uuid);
        getAlertManager().handlePlayerQuit(uuid);
        getDiscordWebhookHandler().handlePlayerQuit(uuid);
        playerBrands.remove(uuid);
        playerDataMap.remove(uuid);
        
        if (configManager.isDebugMode()) {
            getLogger().info("Cleaned up all data for player with UUID: " + uuid);
        }
    }

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        playerBrands.clear();
        playerDataMap.clear();
        getLogger().info("AntiSpoof disabled!");
    }
}