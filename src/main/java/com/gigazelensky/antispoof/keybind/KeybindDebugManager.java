
package com.gigazelensky.antispoof.keybind;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Light‑weight re‑implementation of Grimʼs “debug keybind” logic
 * without relying on any Grim runtime classes.
 * <p>
 *     Flow:
 *     <ol>
 *         <li>Command issues {@link #request(Player, Player, String)}</li>
 *         <li>Fake sign (with “translate” JSON component) is sent only to the target client.</li>
 *         <li>When the client immediately confirms the editor, it sends an UPDATE_SIGN packet.</li>
 *         <li>We capture the first differing line ‑ thatʼs the client‑side translation result.</li>
 *     </ol>
 */
public final class KeybindDebugManager extends PacketListenerAbstract {

    private static final class Request {
        final Player executor;
        final String translationKey;
        final Vector3i pos;
        final String[] originalLines;
        final long sentAt;

        Request(Player executor, String translationKey, Vector3i pos, String[] originalLines) {
            this.executor = executor;
            this.translationKey = translationKey;
            this.pos = pos;
            this.originalLines = originalLines;
            this.sentAt = System.currentTimeMillis();
        }
    }

    private final Map<UUID, Request> pending = new HashMap<>();

    public KeybindDebugManager() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /**
     * Initiates a key‑bind translation probe for {@code target}.
     */
    public void request(Player executor, Player target, String translationKey) {
        // Pick an out‑of‑world coordinate ‑ always Y = ‑64 under player, chunk‑local so collisions are fine.
        Vector3i pos = new Vector3i(target.getLocation().getBlockX(), -64, target.getLocation().getBlockZ());

        // Compose sign front_text NBT with the “translate” component so the client does the localisation for us.
        String json = "{"translate":"" + translationKey + ""}";
        String[] signLines = new String[]{json, "", "", ""};

        // Build sign NBT for 1.20 format
        NBTCompound frontText = buildSignTextCompound(signLines);
        NBTCompound signNbt = new NBTCompound();
        signNbt.setTag("front_text", frontText);
        signNbt.setTag("id", new NBTString("minecraft:sign"));

        // Create a wrapped OAK_SIGN block state with our NBT
        ClientVersion cv = PacketEvents.getAPI().getPlayerUtils().getClientVersion(target);
        WrappedBlockState signState = StateTypes.OAK_SIGN.createBlockState(cv);
        signState.setNbt(signNbt);

        // Send block change & open‑editor packets ‑ only to the target client.
        new WrapperPlayServerBlockChange(pos, signState.getGlobalId()).send(target);
        new WrapperPlayServerOpenSignEditor(pos, true).send(target);

        // Track request
        pending.put(target.getUniqueId(), new Request(executor, translationKey, pos, signLines));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (event.getPacketType() == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.UPDATE_SIGN) {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);

            Request req = pending.remove(player.getUniqueId());
            if (req == null) return; // Not ours.

            String[] newLines = wrapper.getTextLines();
            // Compare first line ‑ thatʼs where we put the translation key.
            String translated = "";
            for (int i = 0; i < newLines.length; i++) {
                String original = (req.originalLines.length > i) ? req.originalLines[i] : "";
                if (!newLines[i].equals(original) && !newLines[i].isEmpty()) {
                    translated = newLines[i];
                    break;
                }
            }

            long took = System.currentTimeMillis() - req.sentAt;
            if (translated.isEmpty()) translated = "???";

            String msg = player.getName() + " | QO: result:"" + translated + "" time:=" + took + "ms";
            req.executor.sendMessage(msg);
        }
    }

    // Helper for 1.20 sign JSON front_text compound
    private static NBTCompound buildSignTextCompound(String[] jsonLines) {
        NBTCompound front = new NBTCompound();
        NBTList linesList = new NBTList();

        for (String j : jsonLines) {
            linesList.addTag(new NBTString(j));
        }
        front.setTag("messages", linesList);
        front.setTag("color", new NBTString("black"));
        front.setTag("has_glowing_text", new NBTByte((byte) 0));
        return front;
    }
}
