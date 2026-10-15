# Minecraft Abilities — version 2.0.0 iteration source

Paper plugin source for `me.jjonlinux.minecraftabilities.MinecraftAbilities`.

## Build

Use Java 21 and compile against Paper API 1.20.5 or newer. Place the resulting JAR in `plugins/`.

## Configuration

The default template is `src/main/resources/config.yml`. The live configuration is generated at `plugins/MinecraftAbilities/config.yml`; an existing live config is retained and missing defaults are added when the plugin starts.

- `xray.view-radius`: default scan radius in blocks, 1–8 (default 6).
- `xray.safe-radius`: default nearby radius kept visible, 1–3 (default 2).
- `xray.update-period-ticks`: shared Xray refresh period.
- `xray.hidden-blocks`: default hidden-block choices copied into player files.

The Xray block-settings GUI includes click controls for both radii and item lore explaining the trade-offs. Plugin metadata remains version `2.0.0` during this development iteration.

## Commands

- `/abilities` — open the GUI.
- `/ability help` — command help.
- `/ability <name> [level]` — toggle an ability and optionally update its level.
- `/ability xray STONE:true, GRANITE:false` — toggle Xray and update only the listed block settings.


## Per-player settings

Ability toggles and levels, plus each player's Xray view radius, safe radius, and hidden-block selection, are stored separately in `plugins/MinecraftAbilities/players/<UUID>_<PlayerName>.yml`. The UUID identifies the player if their Minecraft name changes; on login, the plugin migrates an existing file to the current name when needed. `config.yml` provides defaults for new player files. The Xray update period remains a shared server performance setting.

Defaults: view radius 6 (allowed 1–8), safe radius 2 (allowed 1–3). Player changes do not alter `config.yml` or other players' files.
