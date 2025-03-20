package com.gigazelensky.antispoof.util;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for plugin updates
 */
public class VersionChecker implements Listener {
    private final AntiSpoofPlugin plugin;
    private final Logger logger;
    private String latestVersion = null;
    private boolean updateAvailable = false;
    private static final String GITHUB_API_URL = "https://api.github.com/repos/GigaZelensky/AntiSpoof/releases/latest";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public VersionChecker(AntiSpoofPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        
        // Only initialize if enabled in config
        if (plugin.getConfigManager().isUpdateCheckerEnabled()) {
            // Register events
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            
            // Check for updates
            checkForUpdates();
        }
    }

    /**
     * Checks GitHub for the latest release version
     */
    public void checkForUpdates() {
        if (!plugin.getConfigManager().isUpdateCheckerEnabled()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                // Get current plugin version
                String currentVersion = plugin.getDescription().getVersion();
                
                // Get latest version from GitHub
                String latestVersion = getLatestRelease();
                
                if (latestVersion != null) {
                    this.latestVersion = latestVersion;
                    
                    // Compare versions
                    if (isNewerVersion(latestVersion, currentVersion)) {
                        this.updateAvailable = true;
                        
                        // Log update notification
                        logger.info("=======================================================");
                        logger.info("A new version of AntiSpoof is available: v" + latestVersion);
                        logger.info("You are currently running v" + currentVersion);
                        logger.info("Download the latest version from:");
                        logger.info("https://github.com/GigaZelensky/AntiSpoof/releases/latest");
                        logger.info("=======================================================");
                        
                        // Notify online admins
                        notifyAdmins();
                    } else {
                        logger.info("You are running the latest version of AntiSpoof (v" + currentVersion + ")");
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to check for updates: " + e.getMessage());
            }
        });
    }

    /**
     * Fetches the latest release version from GitHub API
     */
    private String getLatestRelease() throws IOException, URISyntaxException {
        URL url = new URI(GITHUB_API_URL).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("User-Agent", "AntiSpoof-UpdateChecker");
        
        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                
                // Simple JSON parsing to extract tag_name
                String json = response.toString();
                int tagNameIndex = json.indexOf("\"tag_name\":");
                
                if (tagNameIndex != -1) {
                    int startIndex = json.indexOf("\"", tagNameIndex + 11) + 1;
                    int endIndex = json.indexOf("\"", startIndex);
                    
                    if (startIndex != -1 && endIndex != -1) {
                        String version = json.substring(startIndex, endIndex);
                        // Remove 'v' prefix if present
                        return version.startsWith("v") ? version.substring(1) : version;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Compares versions to determine if the latest is newer than current
     */
    private boolean isNewerVersion(String latestVersion, String currentVersion) {
        Matcher latestMatcher = VERSION_PATTERN.matcher(latestVersion);
        Matcher currentMatcher = VERSION_PATTERN.matcher(currentVersion);
        
        if (latestMatcher.find() && currentMatcher.find()) {
            // Extract major, minor, patch numbers
            int latestMajor = Integer.parseInt(latestMatcher.group(1));
            int latestMinor = Integer.parseInt(latestMatcher.group(2));
            int latestPatch = Integer.parseInt(latestMatcher.group(3));
            
            int currentMajor = Integer.parseInt(currentMatcher.group(1));
            int currentMinor = Integer.parseInt(currentMatcher.group(2));
            int currentPatch = Integer.parseInt(currentMatcher.group(3));
            
            // Compare versions
            if (latestMajor > currentMajor) {
                return true;
            } else if (latestMajor == currentMajor && latestMinor > currentMinor) {
                return true;
            } else if (latestMajor == currentMajor && latestMinor == currentMinor && latestPatch > currentPatch) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notifies all online admins about the update
     */
    private void notifyAdmins() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("antispoof.admin")) {
                sendUpdateNotification(player);
            }
        }
    }

    /**
     * Sends update notification to a specific player
     */
    public void sendUpdateNotification(Player player) {
        player.sendMessage(ChatColor.GRAY + "=======================================================");
        player.sendMessage(ChatColor.AQUA + "A new version of " + ChatColor.BOLD + "AntiSpoof" + 
                          ChatColor.AQUA + " is available: " + ChatColor.GREEN + "v" + latestVersion);
        player.sendMessage(ChatColor.AQUA + "You are currently running " + 
                          ChatColor.YELLOW + "v" + plugin.getDescription().getVersion());
        player.sendMessage(ChatColor.AQUA + "Download from: " + ChatColor.GREEN + 
                          "github.com/GigaZelensky/AntiSpoof/releases/latest");
        player.sendMessage(ChatColor.GRAY + "=======================================================");
    }

    /**
     * Notify admins when they join
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isUpdateNotifyOnJoinEnabled()) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Only notify admins and only if an update is available
        if (updateAvailable && player.hasPermission("antispoof.admin")) {
            // Delay the message slightly to ensure it's seen after join messages
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    sendUpdateNotification(player);
                }
            }, 20L); // 1 second delay
        }
    }
    
    /**
     * Gets the latest version
     */
    public String getLatestVersion() {
        return latestVersion;
    }
    
    /**
     * Whether an update is available
     */
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
}