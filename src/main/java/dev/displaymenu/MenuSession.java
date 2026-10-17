package dev.displaymenu;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One open menu for one player.
 *
 * The player rides an invisible ItemDisplay (can't walk) and every UI element is a TextDisplay
 * floating on a flat plane in front of their eyes.
 *
 * CURSOR mode: the player is put in spectator mode and spectates the mount entity (frozen camera,
 *            no HUD) while ALSO riding it, so the client keeps reporting its mouse rotation. The
 *            rotation drifts invisibly; the change per tick moves the cursor. No camera correction
 *            is needed, so nothing shakes.
 * FREE mode: the camera moves normally inside a small window; the crosshair is the pointer
 *            (ray from the eyes onto the UI plane). No camera correction -> no shaking.
 * LOCK mode: the camera is frozen; yaw/pitch changes move a cursor entity and the camera is
 *            snapped back every tick (can shake, this is a server-side limitation).
 */
final class MenuSession {

    private static final double PX = 0.025;             // blocks per font pixel at scale 1
    private static final double EYE_ABOVE_MOUNT = 1.02; // rough value, the real eye is measured after mounting
    private static final Key FONT = Key.key("menu", "ui");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    // glyphs in assets/menu/font/ui.json and texture sizes (keep in sync with the pack)
    private static final String G_LOGO = "\uE000", G_PANEL = "\uE001", G_CURSOR = "\uE002";
    private static final int LOGO_W = 192, PANEL_W = 128, PANEL_H = 16, CURSOR_PX = 8;

    private final DisplayMenuPlugin plugin;
    private final Player player;
    private final FileConfiguration cfg;
    private final List<Entity> spawned = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();

    private Location anchor;
    private Location returnLoc;
    private ItemDisplay mount;
    private TextDisplay cursor;
    private Vector cam, fwd, right, up;
    private float camYaw, camPitch;
    private double dist, halfW, halfH, degToUnit, limYaw, limPitch;
    private double cu, cv; // pointer position on the UI plane, in blocks from screen centre

    private enum Mode { CAMERA, CURSOR, LOCK, FREE }
    private Mode mode = Mode.CAMERA;
    private AttributeModifier noPick;
    private boolean hadInvis, hideHeld;
    private double curDu, curDv; // custom cursor image offset (blocks)
    private boolean hidden, debug;
    private GameMode prevGameMode;
    private float lastYaw, lastPitch;
    private int rebase;
    private int age;
    private boolean ready, closing, closed, prevInvulnerable;
    private long lastClick;
    private boolean keyHeld;
    private Button hovered;

    MenuSession(DisplayMenuPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.cfg = plugin.getConfig();
    }

    Player getPlayer() {
        return player;
    }

    boolean hidesPlayer() {
        return hidden && !closed;
    }

    // ---------------------------------------------------------------- lifecycle

    void open() {
        ConfigurationSection c = cfg.getConfigurationSection("camera");
        World w = Bukkit.getWorld(c.getString("world", player.getWorld().getName()));
        if (w == null) w = player.getWorld();
        camYaw = (float) c.getDouble("yaw");
        camPitch = (float) c.getDouble("pitch");
        anchor = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), camYaw, camPitch);
        dist = c.getDouble("distance", 2.2);
        String m = c.getString("mode", "CAMERA").toUpperCase(Locale.ROOT);
        mode = m.equals("LOCK") ? Mode.LOCK : m.equals("FREE") ? Mode.FREE : m.equals("CURSOR") ? Mode.CURSOR : Mode.CAMERA;
        if (mode == Mode.CURSOR && plugin.hasProtocolLib()) mode = Mode.CAMERA; // old configs still say CURSOR
        if (mode == Mode.CAMERA && !plugin.hasProtocolLib()) {
            plugin.getLogger().warning("camera.mode CAMERA needs ProtocolLib, falling back to CURSOR (left click won't work).");
            mode = Mode.CURSOR;
        }
        debug = cfg.getBoolean("debug", false);
        limYaw = c.getDouble("limit-yaw", 40);
        limPitch = c.getDouble("limit-pitch", 25);

        // remember where to send the player afterwards
        Location here = player.getLocation();
        Location saved = plugin.savedLocation(player);
        boolean atMenu = here.getWorld().equals(w) && here.distanceSquared(anchor) < 16;
        if (atMenu && saved != null && saved.getWorld() != null) returnLoc = saved; // rejoined while in the menu
        else returnLoc = here.clone();
        plugin.saveLocation(player, returnLoc);

        prevInvulnerable = player.isInvulnerable();
        player.setInvulnerable(true);

        if (mode == Mode.CURSOR) {
            GameMode savedGm = plugin.savedGameMode(player);
            prevGameMode = (savedGm != null && player.getGameMode() == GameMode.SPECTATOR) ? savedGm : player.getGameMode();
            plugin.saveGameMode(player, prevGameMode);
            player.setGameMode(GameMode.SPECTATOR);
        }

        if (mode == Mode.CAMERA) setNoPick(true); // pointing at blocks/entities would swallow the click

        hidden = cfg.getBoolean("invisible", true);
        if (hidden) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(player)) o.hideEntity(plugin, player);
            }
        }

        hadInvis = player.hasPotionEffect(PotionEffectType.INVISIBILITY);
        applyInvisibility(); // hides the first-person hand
        hideHeld = cfg.getBoolean("hide-held-item", true);
        if (hideHeld) plugin.stashHeld(player); // held item is set aside until the menu closes

        dbg("mode=" + mode + (mode == Mode.CAMERA ? " (left click)" : " (SHIFT/SPACE click only)"));

        // hides the setup; fades out by itself when the UI is ready
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
        player.teleport(mountBase());
    }

    /** CURSOR mode: the mount IS the camera, so it sits exactly at the camera position. */
    private Location mountBase() {
        return cursorLike() ? anchor.clone() : anchor.clone().subtract(0, EYE_ABOVE_MOUNT, 0);
    }

    void tick() {
        if (closed) return;
        age++;
        // the client may still be loading when the effect/camera first arrive, so send them again a few times
        if (age == 14 || age == 24 || age == 34) {
            applyInvisibility();
            if (ready && mode == Mode.CAMERA) sendCamera(mount.getEntityId());
        }
        if (age == 6) mountPlayer();
        else if (age == 9) buildUi();
        else if (ready) update();
    }

    void close(Location destination) {
        if (closed) return;
        closed = true;
        ready = false;
        if (!hadInvis) player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (hideHeld) plugin.restoreStash(player); // held item comes back
        if (mode == Mode.CAMERA) {
            sendCamera(player.getEntityId()); // camera back to the player
            setNoPick(false);
        }
        if (prevGameMode != null) {
            player.setGameMode(prevGameMode); // also ends the spectator camera
            plugin.clearGameMode(player);
        }
        player.leaveVehicle();
        for (Entity e : spawned) e.remove();
        spawned.clear();
        player.setInvulnerable(prevInvulnerable);
        if (hidden) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(player)) o.showEntity(plugin, player);
            }
        }
        plugin.forget(player);
        if (destination != null) plugin.clearLocation(player);
        if (destination != null && player.isOnline()) player.teleport(destination);
    }

    // ---------------------------------------------------------------- setup

    private void mountPlayer() {
        Location base = mountBase();
        mount = anchor.getWorld().spawn(base, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false); // only this player sees/rides it
        });
        spawned.add(mount);
        player.showEntity(plugin, mount);
        mount.addPassenger(player);
        if (mode == Mode.CURSOR) player.setSpectatorTarget(mount);
        else if (mode == Mode.CAMERA) sendCamera(mount.getEntityId());
    }

    /** Re-creates every UI entity (called when a resource pack finished loading while the menu is open). */
    void rebuildUi() {
        if (!ready || closed || closing) return;
        for (Entity e : new ArrayList<>(spawned)) {
            if (e != mount) {
                e.remove();
                spawned.remove(e);
            }
        }
        applyInvisibility();
        buttons.clear();
        hovered = null;
        cursor = null;
        ready = false;
        buildUi();
    }

    private void buildUi() {
        if (player.getVehicle() != mount) mount.addPassenger(player);

        // use the real eye position now that the player is seated
        Location eye = cursorLike() ? anchor : player.getEyeLocation();
        cam = eye.toVector();
        Location dir = eye.clone();
        dir.setYaw(camYaw);
        dir.setPitch(camPitch);
        fwd = dir.getDirection().normalize();
        right = fwd.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        up = right.clone().crossProduct(fwd).normalize();

        double fov = cfg.getDouble("camera.fov", 110);
        double aspect = cfg.getDouble("camera.aspect", 16.0 / 9.0);
        halfH = dist * Math.tan(Math.toRadians(fov) / 2);
        halfW = halfH * aspect;
        degToUnit = (2 * halfH / fov) * cfg.getDouble("camera.sensitivity", 1.0);

        ConfigurationSection l = cfg.getConfigurationSection("layout");
        double us = l.getDouble("ui-scale", 1.25);
        double left = l.getDouble("left", -0.55) * halfW;
        double screenW = halfW * 2;

        // full-screen background: an ItemsAdder image (e.g. a blurred screenshot)
        Component bgImage = customImage("background");
        if (bgImage != null) {
            double bpx = Math.max(1, cfg.getDouble("background.pixel-width", 1920));
            double bpy = Math.max(1, cfg.getDouble("background.pixel-height", 1080));
            TextDisplay bd = text(bgImage, cfg.getDouble("background.offset-u", 0.0) * halfW, cfg.getDouble("background.offset-v", 0.0) * halfH, dist + 0.05,
                    halfW * 2 * 1.05 / (bpx * PX), halfH * 2 * 1.05 / (bpy * PX));
            int bop = (int) Math.round(clamp(cfg.getDouble("background.opacity", 1.0), 0, 1) * 255);
            if (bop < 255) bd.setTextOpacity((byte) Math.max(26, bop)); // the client treats values under 26 as opaque
        }

        // optional see-through tint over the whole screen (frosted / dimmed look, NOT a real blur)
        double ovOpacity = clamp(cfg.getDouble("overlay.opacity", 0.0), 0, 1);
        if (ovOpacity > 0) {
            int rgb = parseRgb(cfg.getString("overlay.color", "#FFFFFF"), 0xFFFFFF);
            // a blank text line has a ~6x11 px background box; scale it to 4x the screen so it still covers everything if the client measures the box smaller
            TextDisplay ov = text(Component.text(" "), 0, cfg.getDouble("overlay.offset-v", 0.0) * halfH, dist + 0.04,
                    screenW * 4.0 / (6 * PX), halfH * 2 * 4.0 / (11 * PX));
            ov.setBackgroundColor(Color.fromARGB((int) Math.round(ovOpacity * 255), (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255));
        }

        // dark gradient strip (oversized so it always reaches the screen edges)
        double pw = screenW * l.getDouble("panel-width", 0.6) + 1.0;
        double ph = halfH * 2 * 2.2;
        Component customPanel = customImage("panel");
        if (customPanel != null) {
            double ppx = Math.max(1, cfg.getDouble("panel.pixel-width", PANEL_W));
            double ppy = Math.max(1, cfg.getDouble("panel.pixel-height", PANEL_H));
            text(customPanel, -halfW - 0.5 + pw / 2 + cfg.getDouble("panel.offset-u", 0.0) * halfW, cfg.getDouble("panel.offset-v", 0.0) * halfH, dist + 0.02,
                    pw / (ppx * PX), ph / (ppy * PX));
        } else if (useBuiltin("panel")) {
            glyph(G_PANEL, -halfW - 0.5 + pw / 2, 0, dist + 0.02, pw / (PANEL_W * PX), ph / (PANEL_H * PX));
        }

        // logo
        double logoW = screenW * l.getDouble("logo-width", 0.27) * us;
        double logoV = l.getDouble("logo-v", 0.34) * halfH;
        Component customLogo = customImage("logo"); // ItemsAdder font image from config.yml, or null
        if (customLogo != null) {
            double px = Math.max(1, cfg.getDouble("logo.pixel-width", 192));
            double mult = cfg.getDouble("logo.scale", 1.0);
            double logoScale = logoW / (px * PX) * mult;
            double shownW = logoW * mult;
            text(customLogo, left + shownW / 2 + cfg.getDouble("logo.offset-u", 0.0) * halfW,
                    logoV + cfg.getDouble("logo.offset-v", 0.0) * halfH, dist, logoScale, logoScale);
        } else if (useBuiltin("logo")) {
            double logoScale = logoW / (LOGO_W * PX);
            glyph(G_LOGO, left + logoW / 2, logoV, dist, logoScale, logoScale);
        }

        // buttons
        // sizes in blocks were tuned for FOV 70; scale them with the real FOV so they stay proportional
        double fovK = halfH / (2.2 * Math.tan(Math.toRadians(35))); // 2.2 = reference distance; text/cursor sizes follow camera.distance
        double scale = l.getDouble("button-scale", 0.6) * us * fovK;
        double v = l.getDouble("first-button-v", 0.06) * halfH;
        double step = l.getDouble("button-spacing", 0.15) * halfH * us;
        int hoverRgb = parseRgb(cfg.getString("hover.background", "#1E90FF"), 0x1E90FF);
        int hoverTextRgb = parseRgb(cfg.getString("hover.text", "#FFFFFF"), 0xFFFFFF);
        int hoverAlpha = (int) Math.max(4, Math.round(clamp(cfg.getDouble("hover.opacity", 0.6), 0, 1) * 255));
        Color hoverBg = Color.fromARGB(hoverAlpha, (hoverRgb >> 16) & 255, (hoverRgb >> 8) & 255, hoverRgb & 255);
        double prevMult = -1;
        for (Map<?, ?> m : cfg.getMapList("buttons")) {
            String label = String.valueOf(m.get("label"));
            String action = String.valueOf(m.get("action"));
            Object sc = m.get("scale"); // per-button size multiplier from config.yml (default 1.0)
            double mult = sc instanceof Number num ? num.doubleValue() : 1.0;
            if (mult <= 0) mult = 1.0;
            if (prevMult > 0) v -= step * (prevMult + mult) / 2; // bigger buttons get more room
            if (Boolean.TRUE.equals(m.get("gap-before"))) v -= step * 0.35;
            prevMult = mult;
            double bscale = scale * mult;

            double w = (label.length() * 6 + 2) * PX * bscale; // rough text width
            double h = 11 * PX * bscale;
            Component normal = Component.text(label, TextColor.color(0xEDEDED));
            Component hot = Component.text(label, TextColor.color(hoverTextRgb));
            double u = left + w / 2; // left-aligned
            TextDisplay td = text(normal, u, v, dist, bscale, bscale);
            td.setBackgroundColor(Button.IDLE_BG); // see Button.IDLE_BG
            buttons.add(new Button(action, td, normal, hot, hoverBg, u, v, w / 2 + 0.05 * us * fovK, h / 2 + 0.05 * us * fovK));
        }

        // cursor entity (LOCK mode only; in FREE mode the crosshair is the pointer)
        cu = mode == Mode.FREE ? 1e9 : 0;
        cv = mode == Mode.FREE ? 1e9 : 0;
        if (mode != Mode.FREE) {
            double cs = cfg.getDouble("layout.cursor-size", 0.07) * us * fovK;
            Component customCursor = customImage("cursor");
            if (customCursor != null) {
                double cpx = Math.max(1, cfg.getDouble("cursor.pixel-width", CURSOR_PX));
                double cScale = cs / (cpx * PX);
                curDu = cfg.getDouble("cursor.offset-u", 0.0) * cs; // shifts the image so its tip sits on the pointer
                curDv = cfg.getDouble("cursor.offset-v", 0.0) * cs;
                cursor = text(customCursor, curDu, curDv, dist - 0.02, cScale, cScale);
            } else if (useBuiltin("cursor")) {
                double cScale = cs / (CURSOR_PX * PX);
                cursor = glyph(G_CURSOR, 0, 0, dist - 0.02, cScale, cScale);
            }
        }

        // cursor glide time in ticks: 0 = instant (snappy but steppy), 1 = default, 2+ = smoother but laggier
        if (cursor != null) cursor.setTeleportDuration((int) clamp(cfg.getInt("cursor.smoothing", 1), 0, 59));

        lastYaw = camYaw;
        lastPitch = camPitch;
        ready = true;
        if (cursorLike() && Math.abs(camPitch) > 30) resetRotation();
    }

    // ---------------------------------------------------------------- per tick

    private void update() {
        if (player.getVehicle() != mount) mount.addPassenger(player); // Shift-dismount -> sit back down
        if (mode == Mode.CURSOR && player.getSpectatorTarget() != mount) player.setSpectatorTarget(mount);
        else if (mode == Mode.CAMERA && age % 20 == 0) sendCamera(mount.getEntityId()); // keep the client's camera on the mount
        if (age % 40 == 0) applyInvisibility();

        Location cur = player.getLocation();
        double dyaw = wrap(cur.getYaw() - camYaw);
        double dpitch = cur.getPitch() - camPitch;

        if (cursorLike()) {
            float y = cur.getYaw(), p = cur.getPitch();
            boolean overwritten = Math.abs(wrap(y - camYaw)) < 0.001 && Math.abs(p - camPitch) < 0.001;
            if (rebase > 0) {
                rebase--;
                lastYaw = y;
                lastPitch = p;
            } else if (!overwritten) { // ticks where the server wrote the camera's rotation carry no mouse info
                double dy = wrap(y - lastYaw), dp = p - lastPitch;
                lastYaw = y;
                lastPitch = p;
                if (dy != 0 || dp != 0) {
                    cu = clamp(cu + dy * degToUnit, -halfW, halfW);
                    cv = clamp(cv - dp * degToUnit, -halfH, halfH);
                    if (cursor != null) cursor.teleport(at(cu + curDu, cv + curDv, dist - 0.02));
                }
            }
            if (Math.abs(lastPitch) > 70) resetRotation(); // invisible: the view is frozen
            if (debug) {
                player.sendActionBar(Component.text(String.format("yaw %.1f pitch %.1f  cu %.2f cv %.2f", y, p, cu, cv)));
            }
        } else if (mode == Mode.FREE) {
            // keep the player looking at the menu: only push the camera back past the limits
            double cy = clamp(dyaw, -limYaw, limYaw);
            double cp = clamp(dpitch, -limPitch, limPitch);
            if (cy != dyaw || cp != dpitch) {
                lockTo(cur, camYaw + (float) cy, camPitch + (float) cp);
                dyaw = cy;
                dpitch = cp;
            }
            // pointer = where the crosshair hits the UI plane
            Location look = cur.clone();
            look.setYaw(camYaw + (float) dyaw);
            look.setPitch(camPitch + (float) dpitch);
            Vector d = look.getDirection();
            double f = d.dot(fwd);
            if (f > 0.05) {
                double t = dist / f;
                cu = d.dot(right) * t;
                cv = d.dot(up) * t;
            } else {
                cu = 1e9;
                cv = 1e9;
            }
        } else if (Math.abs(dyaw) > 0.001 || Math.abs(dpitch) > 0.001) { // LOCK
            cu = clamp(cu + dyaw * degToUnit, -halfW, halfW);
            cv = clamp(cv - dpitch * degToUnit, -halfH, halfH);
            lockTo(cur, camYaw, camPitch);
            if (cursor != null) cursor.teleport(at(cu + curDu, cv + curDv, dist - 0.02));
        }

        Button hit = null;
        for (Button b : buttons) {
            if (b.contains(cu, cv)) {
                hit = b;
                break;
            }
        }
        if (hit != hovered) {
            if (hovered != null) hovered.setHover(false);
            if (hit != null) {
                hit.setHover(true);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.6f);
            }
            hovered = hit;
        }
    }

    /** Puts the player's own (invisible) rotation back to neutral so pitch never hits +-90. */
    private void resetRotation() {
        lockTo(player.getLocation(), camYaw, 0f);
        lastYaw = camYaw;
        lastPitch = 0f;
        rebase = 4;
    }

    private void lockTo(Location cur, float yaw, float pitch) {
        Location l = cur.clone();
        l.setYaw(yaw);
        l.setPitch(pitch);
        player.teleport(l, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_VEHICLE);
    }

    // ---------------------------------------------------------------- clicks / actions

    boolean isActive() {
        return !closed;
    }

    /** Debug trace: shows which input signals actually reach the server. */
    void dbg(String what) {
        if (!debug) return;
        player.sendMessage(Component.text("[menu] " + what, NamedTextColor.GRAY));
        plugin.getLogger().info(player.getName() + ": " + what);
    }

    /** SPACE or SHIFT = click (works even when the client sends no arm swing in spectator mode). */
    void keyInput(boolean jump, boolean sneak) {
        boolean down = jump || sneak;
        if (down && !keyHeld) keyClick(jump ? "space" : "shift");
        keyHeld = down;
    }

    void keyClick(String what) {
        if (!cfg.getBoolean("keyboard-click", false)) return;
        dbg(what);
        click();
    }

    void click() {
        long now = System.currentTimeMillis();
        if (!ready || closing || now - lastClick < 150) return; // swing + interact can both fire
        lastClick = now;
        if (debug) player.sendActionBar(Component.text("click! hovered=" + (hovered != null ? hovered.action : "none")));
        if (hovered == null) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        run(hovered.action);
    }

    private void run(String action) {
        String type = action, arg = "";
        int i = action.indexOf(':');
        if (i >= 0) {
            type = action.substring(0, i);
            arg = action.substring(i + 1);
        }
        switch (type.toUpperCase(Locale.ROOT)) {
            case "PLAY" -> play();
            case "CLOSE" -> close(destination());
            case "COMMAND" -> player.performCommand(arg);
            case "CONSOLE" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg.replace("%player%", player.getName()));
            case "MESSAGE" -> player.sendMessage(LEGACY.deserialize(arg));
            case "KICK" -> player.kick(LEGACY.deserialize(arg));
            default -> plugin.getLogger().warning("Unknown menu action: " + action);
        }
    }

    private void play() {
        closing = true;
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false, false));
        Bukkit.getScheduler().runTaskLater(plugin, () -> close(destination()), 15L);
    }

    private Location destination() {
        if (cfg.getBoolean("return-to-last-location", true) && returnLoc != null && returnLoc.getWorld() != null) {
            return returnLoc;
        }
        ConfigurationSection s = cfg.getConfigurationSection("spawn");
        if (s != null) {
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w != null) {
                return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            }
        }
        return anchor.getWorld().getSpawnLocation();
    }

    // ---------------------------------------------------------------- helpers

    /** Short effect refreshed every 2 s, so a crash mid-menu can't leave the player invisible for long. */
    private void applyInvisibility() {
        if (hadInvis) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
    }

    /** ItemsAdder font image configured under "<section>.image" in config.yml, or null to use the built-in graphic. */
    /** image: "builtin" -> the glyph from DisplayMenu's own resource pack. Empty -> nothing is shown. */
    private boolean useBuiltin(String section) {
        String spec = cfg.getString(section + ".image", "");
        return spec != null && (spec.trim().equalsIgnoreCase("builtin") || spec.trim().equalsIgnoreCase("default"));
    }

    private Component customImage(String section) {
        String spec = cfg.getString(section + ".image", "");
        if (spec == null || spec.isBlank() || useBuiltin(section)) return null;
        String resolved = plugin.resolveFontImage(spec.trim());
        if (resolved == null) {
            plugin.getLogger().warning(section + ".image '" + spec + "' could not be resolved (is ItemsAdder loaded, is the id correct?) - nothing will be shown for it.");
            return null;
        }
        return LEGACY_SECTION.deserialize(resolved).colorIfAbsent(NamedTextColor.WHITE);
    }

    private boolean cursorLike() {
        return mode == Mode.CURSOR || mode == Mode.CAMERA;
    }

    /** ProtocolLib: makes the client's camera follow the given entity (works in any game mode). */
    private void sendCamera(int entityId) {
        if (!player.isOnline()) return;
        try {
            Cam.send(player, entityId);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not send camera packet: " + t);
        }
    }

    /** Interaction range 0 -> the client never targets a block/entity, so a left click is always an arm swing. */
    private void setNoPick(boolean on) {
        NamespacedKey key = new NamespacedKey(plugin, "menu_no_pick");
        if (noPick == null) noPick = new AttributeModifier(key, -1.0, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY);
        for (Attribute a : new Attribute[]{Attribute.BLOCK_INTERACTION_RANGE, Attribute.ENTITY_INTERACTION_RANGE}) {
            AttributeInstance inst = player.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (key.equals(m.getKey())) inst.removeModifier(m);
            }
            if (on) inst.addTransientModifier(noPick);
        }
    }

    /** Point on the UI plane: u to the right, v up, `depth` blocks in front of the eyes. */
    private Location at(double u, double v, double depth) {
        Vector p = cam.clone()
                .add(fwd.clone().multiply(depth))
                .add(right.clone().multiply(u))
                .add(up.clone().multiply(v));
        return p.toLocation(anchor.getWorld());
    }

    private TextDisplay glyph(String g, double u, double v, double depth, double sx, double sy) {
        return text(Component.text(g, NamedTextColor.WHITE).font(FONT), u, v, depth, sx, sy);
    }

    private TextDisplay text(Component c, double u, double v, double depth, double sx, double sy) {
        Location loc = at(u, v, depth);
        TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.text(c);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setSeeThrough(cfg.getBoolean("see-through", false)); // true = ignore depth (cursor/hover order can then break)
            t.setLineWidth(1000);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTeleportDuration(2);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f((float) sx, (float) sy, 1f), new AxisAngle4f()));
        });
        player.showEntity(plugin, td);
        spawned.add(td);
        return td;
    }

    private static int parseRgb(String hex, int def) {
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double wrap(double d) {
        d %= 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class Button {
        /**
         * Almost invisible (alpha 4 = 1.5%) instead of fully transparent. The client only starts drawing text
         * backgrounds once one is non-transparent; if that first happened on hover, the highlight would be drawn
         * after (on top of) the cursor. With this the background layer is already active before the cursor.
         */
        static final Color IDLE_BG = Color.fromARGB(4, 0, 0, 0);

        final String action;
        final TextDisplay display;
        final Component normal, hot;
        final Color hoverBg;
        final double u, v, hw, hh;

        Button(String action, TextDisplay display, Component normal, Component hot, Color hoverBg,
               double u, double v, double hw, double hh) {
            this.hoverBg = hoverBg;
            this.action = action;
            this.display = display;
            this.normal = normal;
            this.hot = hot;
            this.u = u;
            this.v = v;
            this.hw = hw;
            this.hh = hh;
        }

        boolean contains(double x, double y) {
            return Math.abs(x - u) <= hw && Math.abs(y - v) <= hh;
        }

        void setHover(boolean on) {
            display.text(on ? hot : normal);
            display.setBackgroundColor(on ? hoverBg : IDLE_BG);
        }
    }
}
