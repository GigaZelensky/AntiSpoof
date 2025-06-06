package com.gigazelensky.antispoof.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified colour utility:
 *  • Accepts MiniMessage tags (<gradient>, <#hex>, </bold>, etc)
 *  • Accepts legacy & and § colour/format codes (including &x&F&F&0… hex)
 *  • Returns a §-coloured string ready for Player#sendMessage
 *
 *  Strategy:
 *   1. Unescape common sequences ("\\n" → "\n") for config friendliness.
 *   2. Translate ampersand codes (&e) to section (§e).
 *   3. Convert section codes to MiniMessage tags so MiniMessage parser doesn’t scream.
 *   4. Feed through MiniMessage; if it fails we fall back to legacy-only path.
 *   5. Serialize back to section-coloured string.
 */
public class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedHexFormat() // §x§A§B§C…
            .build();

    // Regex for §x hex format (and &x translated later)
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("[§&]x([§&][0-9A-Fa-f]){6}");
    // Regex for &#abcdef format
    private static final Pattern LEGACY_HASH_HEX_PATTERN = Pattern.compile("[§&]#([0-9A-Fa-f]{6})");

    public static String colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        // 1) unescape newline etc
        String msg = raw.replace("\\n", "\n");

        // 2) translate & to § for easier processing
        msg = ChatColor.translateAlternateColorCodes('&', msg);

        // 3) convert § colour/format codes into MiniMessage tags so the parser will accept them
        msg = convertLegacyToMini(msg);

        Component component;
        try {
            component = MINI.deserialize(msg);
        } catch (Exception ex) {
            // Fallback – just strip MiniMessage angle brackets and do legacy
            component = LEGACY_SECTION.deserialize(ChatColor.stripColor(msg).replace('<', ' ').replace('>', ' '));
        }

        // 5) serialize back to section colours
        return LEGACY_SECTION.serialize(component);
    }

    /**
     * Swap § codes for their MiniMessage equivalents.
     * Keeps existing MiniMessage tags (<gradient> etc) intact.
     */
    private static String convertLegacyToMini(String input) {
        if (input.indexOf('§') == -1) return input; // nothing to do

        String s = input;

        // Hex §x format → <#abcdef>
        Matcher m = LEGACY_HEX_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group().replace("§x", "").replace("§", "");
            String replacement = "<#" + hex + ">";
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        s = sb.toString();

        // §#abcdef format (rare) → <#abcdef>
        s = LEGACY_HASH_HEX_PATTERN.matcher(s).replaceAll(result -> {
            String hex = result.group(1);
            return "<#" + hex + ">";
        });

        // Simple colour/format codes
        s = s.replace("§0", "<black>")
             .replace("§1", "<dark_blue>")
             .replace("§2", "<dark_green>")
             .replace("§3", "<dark_aqua>")
             .replace("§4", "<dark_red>")
             .replace("§5", "<dark_purple>")
             .replace("§6", "<gold>")
             .replace("§7", "<gray>")
             .replace("§8", "<dark_gray>")
             .replace("§9", "<blue>")
             .replace("§a", "<green>")
             .replace("§b", "<aqua>")
             .replace("§c", "<red>")
             .replace("§d", "<light_purple>")
             .replace("§e", "<yellow>")
             .replace("§f", "<white>")
             .replace("§l", "<bold>")
             .replace("§n", "<underlined>")
             .replace("§m", "<strikethrough>")
             .replace("§o", "<italic>")
             .replace("§k", "<obfuscated>")
             .replace("§r", "<reset>");
        return s;
    }
}
