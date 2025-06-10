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

public final class KeybindDebugManager extends PacketListenerAbstract {
    private final Map<UUID, Request> pending = new HashMap<>();
    private record Request(Player executor, String[] originalLines, long sentAt) {}

    public KeybindDebugManager() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void request(Player executor, Player target, String key) {
        Vector3i pos = new Vector3i(target.getLocation().getBlockX(), -64, target.getLocation().getBlockZ());
        String json = "{\"translate\":\"" + key + "\"}";
        String[] lines = { json, "", "", "" };
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        nbt.setTag("Text1", new NBTString(json));
        nbt.setTag("Text2", new NBTString(""));
        nbt.setTag("Text3", new NBTString(""));
        nbt.setTag("Text4", new NBTString(""));

        WrappedBlockState state;
        try {
            Method m = StateTypes.OAK_SIGN.getClass().getMethod("createBlockData");
            state = (WrappedBlockState) m.invoke(StateTypes.OAK_SIGN);
        } catch (Throwable ex) {
            ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
            state = StateTypes.OAK_SIGN.createBlockState(cv);
        }

        for (String m : new String[]{"setBlockEntityData", "setNbt"}) {
            try {
                Method method = state.getClass().getMethod(m, NBTCompound.class);
                method.invoke(state, nbt);
                break;
            } catch (Throwable ignore) {}
        }

        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerBlockChange(pos, state.getGlobalId()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(target, new WrapperPlayServerOpenSignEditor(pos, true));
        pending.put(target.getUniqueId(), new Request(executor, lines, System.currentTimeMillis()));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.UPDATE_SIGN) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Request req = pending.remove(p.getUniqueId());
        if (req == null) return;

        String[] reply = new WrapperPlayClientUpdateSign(e).getTextLines();
        String result = "???";
        for (int i = 0; i < reply.length; i++) {
            String orig = i < req.originalLines().length ? req.originalLines()[i] : "";
            if (!reply[i].isEmpty() && !reply[i].equals(orig)) {
                result = reply[i];
                break;
            }
        }

        long took = System.currentTimeMillis() - req.sentAt();
        req.executor().sendMessage(p.getName() + " | QO: result:\"" + result + "\" time:=" + took + "ms");
    }
}
