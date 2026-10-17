# DisplayMenu

Display-entity main menu (logo, buttons with hover highlight, mouse cursor) for Paper 1.21.x.

## Build
    mvn package          # -> target/DisplayMenu-1.0.0.jar  (edit paper-api version in pom.xml to match your server)

## Install
1. Drop the jar into plugins/, start the server once.
2. Host DisplayMenu-resourcepack.zip (any direct-download URL) and put it in plugins/DisplayMenu/config.yml:
       resource-pack.url: "https://.../DisplayMenu-resourcepack.zip"
       resource-pack.sha1: "993912894cd841c85ba022981a7fbbc2966915b6"
   (recompute the SHA-1 if you edit the pack: `sha1sum DisplayMenu-resourcepack.zip`)
3. Set camera.x/y/z/yaw/pitch to where the menu "camera" (the player's EYES) should be. Keep ~3 free blocks in front of it.
4. /menu to test, /menu reload after config edits.

## Customising
- Logo: replace assets/menu/textures/font/logo.png (keep 192x64, or change LOGO_W and the "height" in font/ui.json).
- Panel / cursor: panel.png (128x16), cursor.png (8x8).
- Buttons/actions: config.yml -> buttons.
- If the pack's pack_format doesn't match your client version, edit pack.mcmeta.

## How it works
- Player rides an invisible ItemDisplay (cannot walk).
- Every UI piece is a TextDisplay (billboard CENTER, visible only to that player) on a flat plane
  in front of the eyes. Logo/panel/cursor are font glyphs from the pack; buttons are plain text
  whose background turns yellow on hover.
- Each tick: yaw/pitch change since the locked camera angle = mouse movement -> move the cursor,
  then snap the camera back (teleport with RETAIN_VEHICLE).
- Click = arm swing / interact event while the cursor is inside a button's box.
- PLAY: blindness fade, dismount, teleport to spawn.


## v1.1 changes
- `/menuset` (permission displaymenu.admin): stand where you want the menu camera, look at the direction you want, run it.
  `/menuset spawn` sets the fallback spawn used when return-to-last-location is false.
- PLAY / CLOSE now teleports the player back to where they were (saved in plugins/DisplayMenu/locations.yml).
- `invisible: true` hides the player from other players while in the menu.
- `camera.fov` must match the in-game FOV (110 for "Quake Pro"). `layout.ui-scale` makes everything bigger/smaller.
- `camera.mode: FREE` (default, no shaking: aim the crosshair at buttons) or `LOCK` (frozen camera + mouse cursor, can shake).

## v1.2
- New default `camera.mode: CURSOR`: spectator-camera trick. Frozen camera, mouse cursor, no shaking, no HUD/hand.
  The player's game mode is restored afterwards (also if they disconnect or the server restarts mid-menu).
- `debug: true` shows yaw/pitch/cursor values in the action bar if the cursor doesn't move.
- FREE and LOCK modes are still available as fallbacks.

## v1.3
- New default `camera.mode: CAMERA` (needs the ProtocolLib plugin): the client camera is attached to the mount with a
  camera packet, the player stays in their normal game mode, and block/entity interaction range is set to 0 while in
  the menu, so a left click always arrives as an arm swing. `keyboard-click: true` also lets SPACE/SHIFT click.

## v1.4 - ItemsAdder logo
- config.yml -> `logo.image`: an ItemsAdder font image (`namespace:id`, `%img_namespace:id%` or `:id:`). Leave empty for the built-in logo.
- Tune it with `logo.pixel-width`, `logo.scale`, `logo.offset-u`, `logo.offset-v`.
- With ItemsAdder's resource pack: leave `resource-pack.url` empty and set `wait-for-pack: true`.
- Same for the side strip and mouse cursor: `panel.image` and `cursor.image` (see config.yml for the sizing options).
- `logo/panel/cursor.image`: empty shows nothing; `builtin` uses DisplayMenu's own pack glyph; otherwise an ItemsAdder font image.
- Buttons: each entry in `buttons:` takes an optional `scale:` (size multiplier), e.g. a big PLAY and smaller QUIT.
- `hide-held-item`: the held + off-hand item are set aside (saved to locations.yml) while the menu is open and restored to the same slots on close.
- `overlay`: optional see-through tint over the whole screen (opacity/color). Not a real blur.
- `background.image` (full-screen ItemsAdder image, e.g. a pre-blurred screenshot, with `opacity`).
- `see-through: true` draws all menu elements on top of nearby blocks (spawn order = draw order: background, overlay, panel, logo, buttons, cursor).
- `see-through` now defaults to false and `camera.distance` to 0.5: depth-tested layers keep the cursor in front. Text/cursor sizes follow camera.distance.
