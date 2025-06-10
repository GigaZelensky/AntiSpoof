package com.gigazelensky.antispoof.keybind;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-version re-implementation of Grim’s “debug keybind” workflow.
 * <p>
 * A virtual sign containing a single
 * <code>{"translate":"&lt;translationKey&gt;"}</code> JSON component is sent
 * 64 blocks below the target. The client resolves the key, immediately
 * responds with UPDATE_SIGN, and we time that round-trip:
 *
 * <pre>
 * &lt;player&gt; | QO: result:"&lt;Translated&gt;" time:=&lt;ms&gt;ms
 * </pre>
 */
public final class KeybindDebugManager extends PacketListenerAbstract {

    /** Tracks an in-flight sign probe keyed by the target’s UUID. */
    private final Map<UUID, Request> pending = new HashMap<>();

    private record Request(Player executor,
                           String translationKey,
                           Vector3i pos,
                           String[] originalLines,
                           long sentAt) { }

    public KeybindDebugManager() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /**
     * Launch a localisation probe against {@code target}.
     *
     * @param executor command executor
     * @param target   player whose client resolves the key
     * @param key      e.g. {@code sodium.option_impact.medium}
     */
    public void request(Player executor, Player target, String key) {
        // Position 64 blocks below player (never visible or colliding).
        Vector3i pos = new Vector3i(target.getLocation().getBlockX(), -64,
                                    target.getLocation().getBlockZ());

        // Legacy Text1-4 format works on every MC version 1.8 → 1.21+
        String json = "{\"translate\":\"" + key + "\"}";
        String[] lines = { json, "", "", "" };

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id",   new NBTString("minecraft:sign"));
        nbt.setTag("Text1", new NBTString(json));
        nbt.setTag("Text2", new NBTString(""));
        nbt.setTag("Text3", new NBTString(""));
        nbt.setTag("Text4", new NBTString(""));

        // Obtain an OAK_SIGN WrappedBlockState regardless of PacketEvents version
        WrappedBlockState state;
        try {                                   // PacketEvents ≥ 2.x
            Method m = StateTypes.OAK_SIGN.getClass().getMethod("createBlockData");
            state = (WrappedBlockState) m.invoke(StateTypes.OAK_SIGN);
        } catch (Throwable ex) {                // PacketEvents ≤ 1.x
            ClientVersion cv =
                PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
            state = StateTypes.OAK_SIGN.createBlockState(cv);
        }

        // Set NBT with whichever setter exists
        boolean ok = false;
        for (String setter : new String[] { "setBlockEntityData", "setNbt" }) {
            try {
                state.getClass().getMethod(setter, NBTCompound.class)
                     .invoke(state, nbt);
                ok = true;
                break;
            } catch (Throwable ignore) {}
        }
        if (!ok) throw new IllegalStateException(
                "Cannot write NBT to WrappedBlockState (unknown PacketEvents build)");

        // Send packets via PlayerManager (present on all builds)
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerBlockChange(pos, state.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));

        pending.put(target.getUniqueId(),
                    new Request(executor, key, pos, lines, System.currentTimeMillis()));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (e.getPacketType() !=
            com.github.retrooper.packetevents.protocol.packettype.PacketType
                    .Play.Client.UPDATE_SIGN) return;

        Request req = pending.remove(p.getUniqueId());
        if (req == null) return; // Not ours.

        String[] received = new WrapperPlayClientUpdateSign(e).getTextLines();
        String translated = "???";
        for (int i = 0; i < received.length; i++) {
            String orig = i < req.originalLines().length ? req.originalLines()[i] : "";
            if (!received[i].isEmpty() && !received[i].equals(orig)) {
                translated = received[i];
                break;
            }
        }

        long ms = System.currentTimeMillis() - req.sentAt();
        req.executor().sendMessage(
                p.getName() + " | QO: result:\"" + translated + "\" time:=" + ms + "ms");
    }
}