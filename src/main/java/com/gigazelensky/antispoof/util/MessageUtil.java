
package com.gigazelensky.antispoof.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Utility to parse MiniMessage and return legacy section-colored strings
 * so we can still use Player#sendMessage while supporting <#hex> colours.
 */
public class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * Converts a MiniMessage-formatted string (allowing hex colours like <#abcd12>)
     * into a legacy formatted string understood by the Spigot chat system.
     *
     * @param message MiniMessage string
     * @return legacy-colour formatted string (§ codes)
     */
    public static String colorize(String message) {
        if (message == null) return "";
        Component component = MINI.deserialize(message);
        return LEGACY.serialize(component);
    }
}
