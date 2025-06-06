package com.gigazelensky.antispoof.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/**
 * Utility that accepts either MiniMessage <tags>, legacy & colour codes,
 * or already-sectioned (§) strings and always returns a section-colour
 * string Spigot can print.  Hex colours are supported through Adventure’s
 * legacy serializer (requires 1.16+ clients).
 */
public class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    /**
     * Colours a string for chat output, accepting both MiniMessage and legacy formats.
     *
     * @param raw Raw string from config / code.
     * @return Colourised string with § codes, safe to pass to Player#sendMessage.
     */
    public static String colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        // Unescape typical command-line escapes (e.g. \n)
        String msg = raw.replace("\\n", "\n");

        Component component = null;

        // If it looks like MiniMessage (<...>) try that path first.
        if (msg.contains("<") && msg.contains(">")) {
            try {
                component = MINI.deserialize(msg);
            } catch (Exception ignored) {
                // fall through to legacy handling
            }
        }

        if (component == null) {
            // Translate & codes to § so legacySection can parse hex too.
            String ampersandTranslated = ChatColor.translateAlternateColorCodes('&', msg);
            // Deserialize via Adventure legacy to support § and §x hex colours.
            component = LEGACY_SECTION.deserialize(ampersandTranslated);
        }

        // Re‑serialize to section-colour codes (modern clients understand hex with §x§r…).
        return LEGACY_SECTION.serialize(component);
    }
}
