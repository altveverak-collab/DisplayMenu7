package dev.displaymenu;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

/**
 * Only loaded when ProtocolLib is present. While the camera sits on the mount entity the client can aim at the
 * player's own body and sends an "interact with entity" packet for it; vanilla kicks for that ("Cannot interact with
 * self!"). Those packets are dropped here while the player is in the menu.
 */
final class Guard {
    private Guard() {
    }

    static void register(DisplayMenuPlugin owner) {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(owner, ListenerPriority.LOWEST, PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent e) {
                        if (e.getPlayer() == null || !owner.inMenu.contains(e.getPlayer().getUniqueId())) return;
                        e.setCancelled(true);
                    }
                });
    }
}
