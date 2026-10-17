package dev.displaymenu;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DisplayMenuPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, MenuSession> sessions = new HashMap<>();
    final java.util.Set<UUID> inMenu = java.util.concurrent.ConcurrentHashMap.newKeySet(); // read from the network thread
    private final Set<UUID> awaitingPack = new HashSet<>();
    private File locFile;
    private YamlConfiguration locs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        locFile = new File(getDataFolder(), "locations.yml");
        locs = YamlConfiguration.loadConfiguration(locFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (hasProtocolLib()) {
            try {
                Guard.register(this);
            } catch (Throwable t) {
                getLogger().warning("Could not register the self-interact guard: " + t);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (MenuSession s : new ArrayList<>(sessions.values())) s.tick();
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (MenuSession s : new ArrayList<>(sessions.values())) s.close(null);
        sessions.clear();
        inMenu.clear();
    }

    public void open(Player p) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        MenuSession s = new MenuSession(this, p);
        sessions.put(p.getUniqueId(), s);
        inMenu.add(p.getUniqueId());
        s.open();
    }

    boolean hasProtocolLib() {
        var pl = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        return pl != null && pl.isEnabled();
    }

    /**
     * Turns an ItemsAdder font image into the text to display. Accepts "namespace:id", "%img_namespace:id%"
     * or an ItemsAdder placeholder such as ":logo:". Done by reflection so ItemsAdder is optional.
     * Returns null if ItemsAdder is missing or the image can't be found.
     */
    String resolveFontImage(String spec) {
        var ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        if (ia == null || !ia.isEnabled()) return null;
        try {
            Class<?> w = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
            String id = spec;
            if (id.startsWith("%img_") && id.endsWith("%")) id = id.substring(5, id.length() - 1);
            if (id.matches("[A-Za-z0-9_.\\-]+:[A-Za-z0-9_./\\-]+")) {
                Object inst = w.getConstructor(String.class).newInstance(id);
                try {
                    if (!(Boolean) w.getMethod("exists").invoke(inst)) return null;
                } catch (NoSuchMethodException ignored) {
                    // older API without exists()
                }
                String r = (String) w.getMethod("getString").invoke(inst);
                return r == null || r.isEmpty() ? null : r;
            }
            String r = (String) w.getMethod("replaceFontImages", String.class).invoke(null, spec);
            return r == null || r.equals(spec) ? null : r;
        } catch (Throwable t) {
            getLogger().warning("ItemsAdder font image lookup failed: " + t);
            return null;
        }
    }

    void forget(Player p) {
        sessions.remove(p.getUniqueId());
        inMenu.remove(p.getUniqueId());
    }

    // ---- saved "where the player was" locations ---------------------------------

    Location savedLocation(Player p) {
        return locs.getLocation(p.getUniqueId().toString());
    }

    void saveLocation(Player p, Location l) {
        locs.set(p.getUniqueId().toString(), l);
        flushLocations();
    }

    void clearLocation(Player p) {
        locs.set(p.getUniqueId().toString(), null);
        flushLocations();
    }

    GameMode savedGameMode(Player p) {
        String n = locs.getString("gm." + p.getUniqueId());
        if (n == null) return null;
        try {
            return GameMode.valueOf(n);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    void saveGameMode(Player p, GameMode gm) {
        locs.set("gm." + p.getUniqueId(), gm.name());
        flushLocations();
    }

    void clearGameMode(Player p) {
        locs.set("gm." + p.getUniqueId(), null);
        flushLocations();
    }

    // ---- held item stash (the held item is set aside while the menu is open) -------

    /** Moves the held item and off-hand item out of the player's hands; saved to disk so a crash can't lose them. */
    void stashHeld(Player p) {
        String base = "stash." + p.getUniqueId();
        if (locs.contains(base)) return; // already set aside (player rejoined while in the menu)
        PlayerInventory inv = p.getInventory();
        int slot = inv.getHeldItemSlot();
        ItemStack main = inv.getItem(slot);
        ItemStack off = inv.getItemInOffHand();
        boolean hasMain = main != null && !main.getType().isAir();
        boolean hasOff = !off.getType().isAir();
        if (!hasMain && !hasOff) return;
        locs.set(base + ".slot", slot);
        if (hasMain) locs.set(base + ".main", Base64.getEncoder().encodeToString(main.serializeAsBytes()));
        if (hasOff) locs.set(base + ".off", Base64.getEncoder().encodeToString(off.serializeAsBytes()));
        flushLocations();
        if (hasMain) inv.setItem(slot, null);
        if (hasOff) inv.setItemInOffHand(null);
    }

    /** Puts the set-aside items back into the same slots (or anywhere free if the slot got used). */
    void restoreStash(Player p) {
        String base = "stash." + p.getUniqueId();
        if (!locs.contains(base)) return;
        int slot = locs.getInt(base + ".slot");
        String main = locs.getString(base + ".main");
        String off = locs.getString(base + ".off");
        PlayerInventory inv = p.getInventory();
        try {
            if (main != null) {
                ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(main));
                ItemStack cur = inv.getItem(slot);
                if (cur == null || cur.getType().isAir()) inv.setItem(slot, it);
                else giveAnywhere(p, it);
            }
            if (off != null) {
                ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(off));
                if (inv.getItemInOffHand().getType().isAir()) inv.setItemInOffHand(it);
                else giveAnywhere(p, it);
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Could not restore held item of " + p.getName() + ": " + ex);
            return; // keep the saved data so nothing is lost
        }
        locs.set(base, null);
        flushLocations();
    }

    private void giveAnywhere(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void flushLocations() {
        try {
            locs.save(locFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save locations.yml: " + ex.getMessage());
        }
    }

    // ---- commands ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("menuset")) return handleSet(sender, args);

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("displaymenu.admin")) {
                sender.sendMessage("No permission.");
                return true;
            }
            reloadConfig();
            sender.sendMessage("DisplayMenu config reloaded.");
            return true;
        }
        if (sender instanceof Player p) open(p);
        else sender.sendMessage("Players only.");
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!p.hasPermission("displaymenu.admin")) {
            p.sendMessage("No permission.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
            Location l = p.getLocation();
            getConfig().set("spawn.world", l.getWorld().getName());
            getConfig().set("spawn.x", round(l.getX()));
            getConfig().set("spawn.y", round(l.getY()));
            getConfig().set("spawn.z", round(l.getZ()));
            getConfig().set("spawn.yaw", round(l.getYaw()));
            getConfig().set("spawn.pitch", round(l.getPitch()));
            saveConfig();
            p.sendMessage("Fallback spawn set to your position.");
            return true;
        }
        Location e = p.getEyeLocation();
        double pitch = Math.max(-60, Math.min(60, e.getPitch())); // steep angles break the UI maths
        getConfig().set("camera.world", e.getWorld().getName());
        getConfig().set("camera.x", round(e.getX()));
        getConfig().set("camera.y", round(e.getY()));
        getConfig().set("camera.z", round(e.getZ()));
        getConfig().set("camera.yaw", round(e.getYaw()));
        getConfig().set("camera.pitch", round(pitch));
        saveConfig();
        p.sendMessage("Menu camera set to your eyes and look direction. Keep ~3 free blocks in front. Test with /menu.");
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---- join / resource pack -------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        restoreStash(p); // items left over from a crash during the menu
        // players currently in the menu stay invisible for newcomers too
        for (MenuSession s : sessions.values()) {
            if (s.hidesPlayer()) p.hideEntity(this, s.getPlayer());
        }

        if (!getConfig().getBoolean("show-on-join", true)) return;
        String url = getConfig().getString("resource-pack.url", "");
        boolean wait = !url.isBlank() || getConfig().getBoolean("wait-for-pack", false);
        if (!url.isBlank()) {
            p.setResourcePack(url, getConfig().getString("resource-pack.sha1", ""), true);
        }
        long delay = Math.max(1, getConfig().getLong("join-delay", 40));
        if (wait) {
            awaitingPack.add(p.getUniqueId());
            // safety net: open anyway if the pack never reports "loaded" (declined, failed, already cached ...)
            long timeout = Math.max(1, getConfig().getLong("wait-for-pack-timeout", 15)) * 20L;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (awaitingPack.remove(p.getUniqueId()) && p.isOnline()) open(p);
            }, timeout);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> open(p), delay);
        }
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
                && awaitingPack.remove(e.getPlayer().getUniqueId())) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(this, () -> open(p), Math.max(1, getConfig().getLong("join-delay", 40)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        awaitingPack.remove(e.getPlayer().getUniqueId());
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.close(null);
    }

    // ---- clicks (the player is riding, so we read arm swings / interacts) -------

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        s.dbg("swing");
        s.click();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        s.dbg("interact");
        s.click();
    }

    // In spectator mode the client may not send arm swings, so there are extra click channels:
    // attacking/interacting with an entity, and the SPACE key (jump) as a keyboard click.
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        s.click();
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        MenuSession s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        s.click();
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.keyInput(e.getInput().isJump(), e.getInput().isSneak());
    }

    // SHIFT while spectating tries to leave the camera: use it as a click instead
    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        MenuSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !s.isActive()) return;
        e.setCancelled(true);
        s.keyClick("shift (stop spectating)");
    }

    // keep the inventory exactly as the menu left it
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (sessions.containsKey(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (sessions.containsKey(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
}
