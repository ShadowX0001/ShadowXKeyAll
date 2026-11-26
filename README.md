# ShadowXKeyAll

A configurable timed crate-key distribution plugin for Paper servers.

## Requirements
- Java 21
- Paper 1.21+ (built against Paper API 1.21.4)
- A crate plugin whose console command can be configured in `distribution.commands`

## Build
```bash
./gradlew build
```
The jar is created at `build/libs/ShadowXKeyAll-1.0.0.jar`.

## Install
1. Copy the jar into the server's `plugins` directory.
2. Start the server once to generate configuration.
3. Edit `plugins/ShadowXKeyAll/config.yml` and set the crate command for your crate plugin.
4. Run `/keyall reload`.

## Commands
- `/keyall` - open timer GUI
- `/keyall status|start|stop|pause|resume|reload`
- `/keyall set <seconds>`
- `/keyall give <online-player> <amount>`
- `/keyall giveall [amount]`
- `/keyall preview`

Permission: `shadowxkeyall.admin` (default OP).

## Compatibility
The plugin targets Java 21 and Paper API 1.21.4. It is intended for Paper 1.21.x and newer Paper releases that retain compatible Bukkit/Paper APIs. No plugin can guarantee compatibility with every future Minecraft version; test on a staging server before production upgrades. Spigot may work but Paper is the supported platform.

## Notes
Offline rewards are queued and delivered on next join when mode is `ONLINE_AND_OFFLINE`. The configured crate command must be correct for the installed crate plugin. The default command is an example and may need adjustment.
