package dev.displaymenu;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Player;

/** Only loaded when ProtocolLib is present. */
final class Cam {
    private Cam() {
    }

    static void send(Player p, int entityId) {
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        PacketContainer pc = pm.createPacket(PacketType.Play.Server.CAMERA);
        pc.getIntegers().write(0, entityId);
        pm.sendServerPacket(p, pc);
    }
}
