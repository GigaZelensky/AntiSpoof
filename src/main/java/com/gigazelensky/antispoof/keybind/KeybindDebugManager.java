package com.gigazelensky.antispoof.keybind;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class KeybindDebugManager extends PacketListenerAbstract {
    private final Map<UUID, Request> pending = new HashMap<>();
    private record Request(Player executor, String[] sentLines, long sentAt) {}

    public KeybindDebugManager() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void request(Player executor, Player target, String key) {
        Vector3i pos = new Vector3i(
                target.getLocation().getBlockX(),
                target.getLocation().getBlockY() + 3,
                target.getLocation().getBlockZ());

        ClientVersion cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(target);
        boolean modern = cv.isNewerThanOrEquals(ClientVersion.V_1_20);

        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        String[] sent;

        if (modern) {
            NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
            messages.addTag(new NBTString("{\"translate\":\"" + key + "\"}"));
            messages.addTag(new NBTString("{\"text\":\"\"}"));
            messages.addTag(new NBTString("{\"text\":\"\"}"));
            messages.addTag(new NBTString("{\"text\":\"\"}"));

            NBTCompound front = new NBTCompound();
            front.setTag("messages", messages);
            front.setTag("color", new NBTString("black"));
            front.setTag("has_glowing_text", new NBTByte((byte) 0));
            nbt.setTag("front_text", front);

            sent = new String[]{
                    "{\"translate\":\"" + key + "\"}",
                    "{\"text\":\"\"}",
                    "{\"text\":\"\"}",
                    "{\"text\":\"\"}"
            };
        } else {
            String json = "{\"translate\":\"" + key + "\"}";
            nbt.setTag("Text1", new NBTString(json));
            nbt.setTag("Text2", new NBTString(""));
            nbt.setTag("Text3", new NBTString(""));
            nbt.setTag("Text4", new NBTString(""));
            sent = new String[]{json, "", "", ""};
        }

        WrappedBlockState signState;
        try {
            signState = (WrappedBlockState) StateTypes.OAK_SIGN.getClass()
                    .getMethod("createBlockData").invoke(StateTypes.OAK_SIGN);
        } catch (Throwable t) {
            signState = StateTypes.OAK_SIGN.createBlockState(cv);
        }

        // 1) place a sign block
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockChange(pos, signState.getGlobalId()));
        // 2) push its NBT (BLOCK_ENTITY_DATA)
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, nbt));
        // 3) open the sign editor
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerOpenSignEditor(pos, true));
        // 4) close it instantly (matches Grim behaviour so GUI doesn’t linger)
        PacketEvents.getAPI().getPlayerManager().sendPacket(target,
                new WrapperPlayServerCloseWindow(0));

        pending.put(target.getUniqueId(),
                new Request(executor, sent, System.currentTimeMillis()));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.UPDATE_SIGN)
            return;
        if (!(e.getPlayer() instanceof Player p)) return;

        Request req = pending.remove(p.getUniqueId());
        if (req == null) return;

        String[] reply = new WrapperPlayClientUpdateSign(e).getTextLines();
        String result = "???";
        for (int i = 0; i < reply.length && i < req.sentLines().length; i++) {
            if (!reply[i].isEmpty() && !reply[i].equals(req.sentLines()[i])) {
                result = reply[i];
                break;
            }
        }
        long took = System.currentTimeMillis() - req.sentAt();
        req.executor().sendMessage(p.getName() + " | QO: result:\"" + result + "\" time:=" + took + "ms");
    }
}