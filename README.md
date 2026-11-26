# ShadowXKeyAll

Timed crate-key distribution plugin for Paper servers. The project is built with Java 21 and Paper API 1.21.4.

## Features
- Configurable global interval and automatic distribution.
- Online-only or online + offline reward queue.
- Offline rewards persist in `plugins/ShadowXKeyAll/data.yml` and are delivered on next join.
- Permission, minimum-playtime, AFK inactivity and world blacklist eligibility filters.
- BossBar and ActionBar countdowns.
- Configurable pre-distribution announcements.
- Inventory timer GUI.
- Pause, resume, start, stop, set timer, reload, preview, give, giveall and pending-reward admin commands.
- Timer state and pending rewards persist across restart.
- Configurable console reward commands, so the crate command can be adapted to the installed crate plugin.

## Requirements
- Java 21
- Paper 1.21.x (compiled against Paper API 1.21.4)
- A crate plugin with a console command that grants keys

## Build
Use Gradle 8.10.2 or newer:
```bash
gradle clean build
```
The jar is generated under `build/libs/`. GitHub Actions also builds every push to `main` and uploads the jar as a workflow artifact.

## Install
1. Download the JAR from the successful GitHub Actions build artifact.
2. Put it in the server's `plugins` directory and start Paper.
3. Edit `plugins/ShadowXKeyAll/config.yml`.
4. Set `distribution.commands` to the exact console command supported by your crate plugin.
5. Run `/keyall reload`.

Example ExcellentCrates command (verify syntax against your installed version):
```yaml
distribution:
  commands:
    - "excellentcrates key give %player% %key% %amount%"
```
Supported command tokens: `%player%`, `%uuid%`, `%key%`, `%amount%`.

## Commands
- `/keyall` - open timer GUI
- `/keyall status`
- `/keyall start|stop|pause|resume|reload`
- `/keyall set <seconds>`
- `/keyall give <online-player> <amount>`
- `/keyall giveall [amount]`
- `/keyall preview`
- `/keyall pending <player>`
- `/keyall clearpending <player>`

Admin permission: `shadowxkeyall.admin` (default OP). The timer GUI is available to players with `shadowxkeyall.use` (default true).

## Compatibility
Paper 1.21.x is the supported target. Java 21 is required. Newer Paper releases may remain compatible while Bukkit/Paper APIs stay compatible, but future Minecraft versions cannot be guaranteed in advance. Spigot is not the primary tested platform.

## Important behavior
- In `ONLINE_AND_OFFLINE` mode, offline accounts that have joined before receive a queued reward each cycle. Their permission/playtime/world eligibility is checked when they next join; pending rewards remain queued until eligible.
- The crate-give command is intentionally configurable. The default is an example; ensure it matches the installed crate plugin's command syntax before enabling automatic distribution.
- This build has compile verification through GitHub Actions. Server-side behavior still needs your planned final test on your Paper server.
