package com.gigazelensky.antispoof;

import com.gigazelensky.antispoof.api.AntiSpoofApi;
import com.gigazelensky.antispoof.command.AntiSpoofCommand;
import com.gigazelensky.antispoof.config.ConfigManager;
import com.gigazelensky.antispoof.detection.DetectionService;
import com.gigazelensky.antispoof.detection.ProfileManager;
import com.gigazelensky.antispoof.listener.PacketListener;
import com.gigazelensky.antispoof.listener.PlayerListener;
import com.gigazelensky.antispoof.model.PlayerSession;
import com.gigazelensky.antispoof.service.AlertService;
import com.gigazelensky.antispoof.service.BedrockService;
import com.gigazelensky.antispoof.service.DiscordService;
import com.gigazelensky.antispoof.service.PunishmentService;
import com.gigazelensky.antispoof.util.VersionChecker;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main AntiSpoof plugin class that coordinates all components.
 */
public class AntiSpoofPlugin extends JavaPlugin {
    
    private ConfigManager configManager;
    private ProfileManager profileManager;
    private DetectionService detectionService;
    private AlertService alertService;
    private DiscordService discordService;
    private PunishmentService punishmentService;
    private BedrockService bedrockService;
    private VersionChecker versionChecker;
    private AntiSpoofApi api;
    
    // Single source of truth for player data
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    
    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        
        // Initialize PacketEvents first to ensure it's ready
        initializePacketEvents();
        
        // Initialize services in dependency order
        this.configManager = new ConfigManager(this);
        this.profileManager = new ProfileManager(this);
        this.alertService = new AlertService(this);
        this.discordService = new DiscordService(this);
        this.punishmentService = new PunishmentService(this);
        this.bedrockService = new BedrockService(this);
        this.detectionService = new DetectionService(this);
        this.versionChecker = new VersionChecker(this);
        this.api = new AntiSpoofApi(this);
        
        // Register event listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        // Register plugin channels
        registerPluginChannels();
        
        // Initialize PacketEvents
        PacketEvents.getAPI().init();
        
        // Register existing online players (in case of reload)
        registerOnlinePlayers();
        
        long enableTime = System.currentTimeMillis() - startTime;
        getLogger().info("AntiSpoof v" + getDescription().getVersion() + " enabled in " + enableTime + "ms!");
    }
    
    private void initializePacketEvents() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
            .reEncodeByDefault(false)
            .checkForUpdates(false);
        PacketEvents.getAPI().load();
    }
    
    private void registerListeners() {
        new PlayerListener(this).register();
        new PacketListener(this).register();
    }
    
    private void registerCommands() {
        getCommand("antispoof").setExecutor(new AntiSpoofCommand(this));
    }
    
    private void registerPluginChannels() {
        // Register for client brand channel
        String channelName = getServer().getBukkitVersion().contains("1.13") || 
                Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13 ?
                "minecraft:brand" : "MC|Brand";
        
        getServer().getMessenger().registerIncomingPluginChannel(this, channelName, 
            (channel, player, message) -> {
                // Processing done in PacketListener
            });
    }
    
    private void registerOnlinePlayers() {
        // Handle case where plugin is reloaded with players online
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip players with bypass permission
            if (player.hasPermission("antispoof.bypass")) {
                continue;
            }
            
            // Create player session
            registerPlayerSession(player);
            
            // Register for alerts if they have permission
            alertService.registerPlayer(player);
        }
    }
    
    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        
        // Clear all player data
        playerSessions.clear();
        
        getLogger().info("AntiSpoof disabled!");
    }
    
    // Getters for services
    public ConfigManager getConfigManager() { return configManager; }
    public ProfileManager getProfileManager() { return profileManager; }
    public DetectionService getDetectionService() { return detectionService; }
    public AlertService getAlertService() { return alertService; }
    public DiscordService getDiscordService() { return discordService; }
    public PunishmentService getPunishmentService() { return punishmentService; }
    public BedrockService getBedrockService() { return bedrockService; }
    public AntiSpoofApi getApi() { return api; }
    
    // Player session management
    public PlayerSession getPlayerSession(UUID uuid) {
        return playerSessions.get(uuid);
    }
    
    public PlayerSession getPlayerSession(Player player) {
        if (player == null) return null;
        return getPlayerSession(player.getUniqueId());
    }
    
    public void registerPlayerSession(Player player) {
        if (player == null) return;
        PlayerSession session = new PlayerSession(player);
        playerSessions.put(player.getUniqueId(), session);
        
        if (configManager.isDebugMode()) {
            getLogger().info("[Debug] Registered new session for " + player.getName());
        }
    }
    
    public void removePlayerSession(UUID uuid) {
        if (uuid == null) return;
        playerSessions.remove(uuid);
    }
    
    public Map<UUID, PlayerSession> getPlayerSessions() {
        return playerSessions;
    }
    /**
     * Gets the version checker
     */
    public VersionChecker getVersionChecker() { 
        return versionChecker; 
    }
}