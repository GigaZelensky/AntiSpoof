package com.gigazelensky.antispoof;

import com.gigazelensky.antispoof.commands.AntiSpoofCommand;
import com.gigazelensky.antispoof.data.PlayerData;
import com.gigazelensky.antispoof.hooks.AntiSpoofPlaceholders;
import com.gigazelensky.antispoof.listeners.PermissionChangeListener;
import com.gigazelensky.antispoof.listeners.PlayerEventListener;
import com.gigazelensky.antispoof.managers.AlertManager;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.managers.DetectionManager;
import com.gigazelensky.antispoof.managers.TranslatableKeyManager;
import com.gigazelensky.antispoof.utils.DiscordWebhookHandler;
import com.gigazelensky.antispoof.utils.VersionChecker;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AntiSpoofPlugin extends JavaPlugin {
    
    private ConfigManager configManager;
    private DiscordWebhookHandler discordWebhookHandler;
    private AlertManager alertManager;
    private DetectionManager detectionManager;
    private TranslatableKeyManager translatableKeyManager;
    private PlayerEventListener playerEventListener;
    
    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    private final Set<UUID> brandAlertedPlayers = ConcurrentHashMap.newKeySet();
    private FloodgateApi floodgateApi = null;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // --- CORRECT INITIALIZATION ORDER ---
        
        // 1. Initialize PacketEvents FIRST
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
            .reEncodeByDefault(false)
            .checkForUpdates(false);
        PacketEvents.getAPI().load();
        
        // 2. Initialize all your managers
        this.configManager = new ConfigManager(this);
        this.alertManager = new AlertManager(this);
        this.detectionManager = new DetectionManager(this);
        this.discordWebhookHandler = new DiscordWebhookHandler(this);
        // TranslatableKeyManager is just a manager now, NOT a listener
        this.translatableKeyManager = new TranslatableKeyManager(this, detectionManager, configManager);
        // PlayerEventListener is our SINGLE packet listener
        this.playerEventListener = new PlayerEventListener(this);
        
        // Initialize other components
        new VersionChecker(this);
        
        if (getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                floodgateApi = FloodgateApi.getInstance();
                getLogger().info("Successfully hooked into Floodgate API!");
            } catch (Exception e) {
                getLogger().warning("Failed to hook into Floodgate API: " + e.getMessage());
            }
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AntiSpoofPlaceholders(this).register();
            getLogger().info("Successfully registered PlaceholderAPI expansion!");
        }
        
        registerClientBrandChannel();
        
        // This now ONLY registers the Bukkit events, not the packet listener
        this.playerEventListener.register();
        
        getServer().getPluginManager().registerEvents(new PermissionChangeListener(this), this);
        
        AntiSpoofCommand commandExecutor = new AntiSpoofCommand(this);
        getCommand("antispoof").setExecutor(commandExecutor);
        getCommand("antispoof").setTabCompleter(commandExecutor);
        
        // 3. Finish PacketEvents full initialization
        PacketEvents.getAPI().init();

        // 4. NOW it is safe to register our SINGLE packet listener
        PacketEvents.getAPI().getEventManager().registerListener(this.playerEventListener);
        
        // --- END OF CORRECTED INITIALIZATION ---
        
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
        
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                alertManager.registerPlayer(player);
            }
            if (configManager.isDebugMode()) {
                getLogger().info("[Debug] Initialized alert recipients list");
            }
        }, 40L);
        
        getLogger().info("AntiSpoof v" + getDescription().getVersion() + " enabled!");
    }
    
    private void registerClientBrandChannel() {
        String channelName = getServer().getBukkitVersion().contains("1.13") || 
                            Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13 ?
                            "minecraft:brand" : "MC|Brand";
        
        getServer().getMessenger().registerIncomingPluginChannel(this, channelName, 
            (channel, player, message) -> {
                String brand = new String(message).substring(1);
                UUID playerUuid = player.getUniqueId();
                
                String previousBrand = playerBrands.get(playerUuid);
                boolean isNewBrand = previousBrand == null || !previousBrand.equals(brand);
                
                if (isNewBrand) {
                    playerBrands.put(playerUuid, brand);
                    
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }
                    
                    detectionManager.checkPlayerAsync(player, false);
                }
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

    public TranslatableKeyManager getTranslatableKeyManager() {
        return translatableKeyManager;
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
    
    public boolean hasPlayerBeenBrandAlerted(Player player) {
        return brandAlertedPlayers.contains(player.getUniqueId());
    }
    
    public void markPlayerBrandAlerted(Player player) {
        brandAlertedPlayers.add(player.getUniqueId());
    }
    
    public boolean sendBrandAlert(Player player, String brand, String brandKey) {
        if (hasPlayerBeenBrandAlerted(player)) {
            if (configManager.isDebugMode()) {
                getLogger().info("[Debug] Suppressing duplicate brand alert for " + player.getName());
            }
            return false;
        }
        
        markPlayerBrandAlerted(player);
        
        if (brandKey == null) {
            alertManager.sendSimpleBrandAlert(player, brand);
            return true;
        }
        
        ConfigManager.ClientBrandConfig brandConfig = configManager.getClientBrandConfig(brandKey);
        
        if (!brandConfig.shouldAlert()) {
            return false;
        }
        
        String alertMessage = brandConfig.getAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);
        
        String consoleMessage = brandConfig.getConsoleAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);
        
        getLogger().info(consoleMessage);
        alertManager.sendAlertToRecipients(alertMessage);
        
        if (configManager.isDiscordWebhookEnabled() && brandConfig.shouldDiscordAlert()) {
            List<String> brandInfo = new ArrayList<>();
            brandInfo.add("Client brand: " + brand);
            
            discordWebhookHandler.sendAlert(
                player,
                "Using client: " + brandKey,
                brand,
                null,
                brandInfo
            );
        }
        
        return true;
    }
    
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        
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

    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;
        
        String brand = getClientBrand(player);
        
        if (brand == null) {
            if (configManager.isNoBrandCheckEnabled()) {
                return true;
            }
            if (configManager.shouldBlockNonVanillaWithChannels()) {
                return true;
            }
            return false;
        }
        
        boolean isBedrockPlayer = isBedrockPlayer(player);
        
        if (isBedrockPlayer && configManager.getBedrockHandlingMode().equals("IGNORE")) {
            return false;
        }
        
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);
        if (data == null) return false;
        
        Set<String> filteredChannels = detectionManager.getFilteredChannels(data.getChannels());
        boolean hasChannels = !filteredChannels.isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");
        
        if (configManager.isPunishSpoofingGeyser() && isSpoofingGeyser(player)) {
            return true;
        }
        
        if (configManager.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
            return true;
        }
        
        if (configManager.isBlockedBrandsEnabled()) {
            boolean brandBlocked = configManager.isBrandBlocked(brand);
            
            if (brandBlocked && configManager.shouldCountNonWhitelistedBrandsAsFlag()) {
                return true;
            }
        }
        
        if (configManager.shouldBlockNonVanillaWithChannels() && (!claimsVanilla || hasChannels)) {
            return true;
        }
        
        if (configManager.isBlockedChannelsEnabled() && hasChannels) {
            if (configManager.isChannelWhitelistEnabled()) {
                if (!detectionManager.checkChannelWhitelist(filteredChannels)) {
                    return true;
                }
            } else {
                String blockedChannel = detectionManager.findBlockedChannel(filteredChannels);
                if (blockedChannel != null) {
                    return true;
                }
            }
        }
        
        String matchedBrand = configManager.getMatchingClientBrand(brand);
        if (matchedBrand != null && hasChannels) {
            ConfigManager.ClientBrandConfig brandConfig = configManager.getClientBrandConfig(matchedBrand);
            
            if (!brandConfig.getRequiredChannels().isEmpty()) {
                boolean missingRequiredChannels = false;
                
                for (int i = 0; i < brandConfig.getRequiredChannels().size(); i++) {
                    Pattern pattern = brandConfig.getRequiredChannels().get(i);
                    boolean patternMatched = false;
                    
                    for (String channel : filteredChannels) {
                        try {
                            if (pattern.matcher(channel).matches()) {
                                patternMatched = true;
                                break;
                            }
                        } catch (Exception e) {
                            String simplePatternStr = pattern.toString()
                                .replace("(?i)", "")
                                .replace(".*", "")
                                .replace("^", "")
                                .replace("$", "");
                                
                            if (channel.toLowerCase().contains(simplePatternStr.toLowerCase())) {
                                patternMatched = true;
                                break;
                            }
                        }
                    }
                    
                    if (!patternMatched) {
                        missingRequiredChannels = true;
                        break;
                    }
                }
                
                if (missingRequiredChannels) {
                    return true;
                }
            }
        }
        
        if (isBedrockPlayer && configManager.isBedrockExemptMode()) {
            return false;
        }
        
        return false;
    }

    public boolean isSpoofingGeyser(Player player) {
        if (player == null) return false;
        
        if (!configManager.isPunishSpoofingGeyser()) {
            return false;
        }
        
        String brand = getClientBrand(player);
        if (brand == null) return false;
        
        boolean claimsGeyser = brand.toLowerCase().contains("geyser");
        
        return claimsGeyser && !isBedrockPlayer(player);
    }
    
    public void handlePlayerQuit(UUID uuid) {
        getDetectionManager().handlePlayerQuit(uuid);
        if (translatableKeyManager != null) {
            // cleanup placeholder
        }
        getAlertManager().handlePlayerQuit(uuid);
        getDiscordWebhookHandler().handlePlayerQuit(uuid);
        playerBrands.remove(uuid);
        playerDataMap.remove(uuid);
        brandAlertedPlayers.remove(uuid);
        
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
        brandAlertedPlayers.clear();
        getLogger().info("AntiSpoof disabled!");
    }
}