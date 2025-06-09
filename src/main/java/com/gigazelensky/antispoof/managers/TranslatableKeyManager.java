package com.gigazelensky.antispoof.managers;

import com.gigazelensky.antispoof.AntiSpoofPlugin;
import com.gigazelensky.antispoof.enums.TranslatableEventType;
import com.gigazelensky.antispoof.managers.ConfigManager;
import com.gigazelensky.antispoof.managers.ConfigManager.TranslatableModConfig;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder manager for translatable key detection.
 * Actual packet logic omitted due to environment limitations.
 */
public class TranslatableKeyManager {
    private final AntiSpoofPlugin plugin;
    private final DetectionManager detectionManager;
    private final ConfigManager config;

    private final Map<UUID, ProbeInfo> probes = new ConcurrentHashMap<>();

    public TranslatableKeyManager(AntiSpoofPlugin plugin, DetectionManager detectionManager, ConfigManager config) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.config = config;
    }

    private static class ProbeInfo {
        final LinkedHashMap<String, TranslatableModConfig> keys;
        final long timestamp;
        final boolean required;

        ProbeInfo(LinkedHashMap<String, TranslatableModConfig> keys, boolean required) {
            this.keys = keys;
            this.required = required;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public void probe(Player player) {
        if (!config.isTranslatableKeysEnabled()) return;
        LinkedHashMap<String, TranslatableModConfig> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.getTranslatableTestKeysPlain().entrySet()) {
            keys.put(e.getKey(), config.getTranslatableModConfig(e.getKey()));
        }
        boolean required = !config.getTranslatableRequiredKeys().isEmpty();
        probes.put(player.getUniqueId(), new ProbeInfo(keys, required));
        // Packet logic would go here
    }

    public void handleReply(Player player, String[] lines) {
        ProbeInfo info = probes.remove(player.getUniqueId());
        if (info == null) return;
        boolean anyTranslated = false;
        int i = 0;
        for (Map.Entry<String, TranslatableModConfig> entry : info.keys.entrySet()) {
            String key = entry.getKey();
            String received = i < lines.length ? lines[i] : "";
            TranslatableModConfig cfg = entry.getValue();
            if (!key.equals(received)) {
                anyTranslated = true;
                detectionManager.handleTranslatable(player, TranslatableEventType.TRANSLATED, key);
            } else if (info.required && config.getTranslatableRequiredKeys().contains(key)) {
                detectionManager.handleTranslatable(player, TranslatableEventType.REQUIRED_MISS, key);
            }
            i++;
        }
        if (!anyTranslated && info.required) {
            // already handled per-key; do nothing
        }
    }
}
