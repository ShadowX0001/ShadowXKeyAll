# ShadowXKeyAll

Multi-key timed crate distribution for Paper servers. Built with Java 21 and Paper API 1.21.4.

## Current features
- Multiple independent key timers configured in `rewards.yml`.
- Per-key amount, display name, reward commands, permission, minimum playtime, world blacklist and online/offline mode.
- Persistent timer state and per-player pending key queues in `data/players.yml`.
- Offline rewards are delivered when the player next joins and passes configured eligibility checks. Pending rewards are also retried periodically while the player is online.
- First-join, daily, weekly and monthly per-key join bonuses.
- Optional daily, weekly and monthly calendar schedules in `schedules.yml`.
- Optional dynamic key intervals based on online player count.
- Temporary global key amount boosts with persisted expiry (`/keyall boost <multiplier> <seconds>`).
- Optional lucky extra keys, daily login streak rewards and per-player cooldowns.
- Admin settings GUI for BossBar, ActionBar, chat, scoreboard and maintenance toggles.
- Optional asynchronous Discord webhook notifications.
- Optional NuVotifier/Votifier vote-event rewards for configured key IDs.
- BossBar, ActionBar, chat countdown and configurable sidebar countdown.
- Configurable warning chat, title/subtitle and sounds.
- Inventory overview GUI and pending reward count.
- PlaceholderAPI expansion when PlaceholderAPI is installed.
- Distribution and reward history in `data/history.yml`.
- Admin commands with tab completion.

## Requirements
- Java 21
- Paper 1.21.x (compiled against Paper API 1.21.4)
- A crate plugin exposing a console command to grant keys (default example uses CrazyCrates)

## Configuration files
- `config.yml`: general settings and display toggles.
- `messages.yml`: player-facing messages.
- `rewards.yml`: independent key definitions, timers, commands and bonuses.
- `announcements.yml`: announcement channels and text.
- `gui.yml`: inventory layout and item text.
- `schedules.yml`: calendar-based distributions.
- `sounds.yml`: countdown, reward and announcement sounds.
- `data/players.yml`: persistent timer, player claims and pending rewards.
- `data/history.yml`: recent distribution and reward history.

All resource configuration files are created automatically on first start. Data files are created automatically.

## Build
Use Gradle 8.10.2 or newer:
```bash
gradle clean build
```
The JAR is generated under `build/libs/`. GitHub Actions builds pushes to supported branches and uploads a `ShadowXKeyAll` artifact.

## Install
1. Install the JAR in the server's `plugins` folder.
2. Start Paper once to generate configuration files.
3. Edit `plugins/ShadowXKeyAll/rewards.yml` and set the real crate IDs and correct console command syntax.
4. Run `/keyall reload`.

Default CrazyCrates command example:
```yaml
commands:
  - "crazycrates give virtual vote %amount% %player%"
```
Tokens: `%player%`, `%uuid%`, `%key%`, `%amount%`.

## Runtime timer and key management commands

All commands below are available from both `/shadowxkeyall` and its `/keyall` / `/ska` aliases:

- `/keyall setinterval <keyId> <seconds>` (alias: `set`) — changes and saves the recurring interval in `rewards.yml`, and resets that key's next run.
- `/keyall timer <keyId> <seconds>` — changes only the current remaining countdown; it does not change the recurring interval.
- `/keyall addkey <keyId> <intervalSeconds> [amount]` — creates a new enabled key timer in `rewards.yml`.
- `/keyall removekey <keyId>` — removes a key definition, its timer and its pending queued amount.
- `/keyall setamount <keyId> <amount>` — changes and saves the amount distributed each cycle.
- `/keyall setcommand <keyId> <console command...>` — replaces the key's reward command list.
- `/keyall addcommand <keyId> <console command...>` — appends another console reward command.
- `/keyall listcommands <keyId>` — displays the configured commands.

Reward commands support `%player%`, `%uuid%`, `%key%` and `%amount%`. Use a crate plugin's console grant command in `rewards.yml`; for example, `crazycrates give virtual vote %amount% %player%`. After editing files manually, run `/keyall reload`.

The chat countdown, warning/distribution broadcasts, and received/pending reward messages are editable in `messages.yml`. Countdown intervals are controlled by `config.yml` under `countdown.chat.seconds`; BossBar, ActionBar, chat and scoreboard displays can be enabled or disabled independently.

## Commands
- `/keyall` - open GUI
- `/keyall status` or `/keyall preview`
- `/keyall start|stop|pause|resume|reload`
- `/keyall set <keyId> <seconds>` - change a key timer live and save it to rewards.yml.\n- `/keyall give <player> <keyId> <amount>` - online players receive immediately; offline players are queued.
- `/keyall settings` - open admin settings GUI
- `/keyall giveall <keyId> [amount]`
- `/keyall pending <player>`
- `/keyall clearpending <player>`
- `/keyall history`

Admin permission: `shadowxkeyall.admin` (default OP). GUI permission: `shadowxkeyall.use` (default true).

## Vote rewards
If NuVotifier/Votifier is installed, set `integrations.voting.enabled: true` in `config.yml`, then list key IDs under `integrations.voting.keys`. Vote rewards for known players are granted immediately when eligible or queued for later delivery.

## PlaceholderAPI
When installed, these placeholders are registered:
- `%shadowxkeyall_keys_count%`
- `%shadowxkeyall_next_key%`
- `%shadowxkeyall_next_time%`
- `%shadowxkeyall_pending_total%`
- `%shadowxkeyall_time_<keyId>%`
- `%shadowxkeyall_pending_<keyId>%`

## Important notes
- `ONLINE_AND_OFFLINE` queues rewards for previously joined offline players. Their permission, playtime, AFK and world eligibility is evaluated when they next join.
- `OFFLINE_ONLY` skips online delivery and queues rewards for offline players.
- `player-interval-seconds` in each key can enforce an individual per-player cooldown. Dynamic intervals, lucky extra drops and streak bonuses are optional per-key settings.
- `ONLINE_ONLY` does not create offline queues.
- Crate reward commands are integration fallbacks: Bukkit confirming that a command was dispatched does not prove the crate plugin actually granted the key. Test your exact crate command before enabling automatic cycles.
- Paper 1.21.x is the supported target. Other Paper versions may work while APIs remain compatible. Server-side behavior still requires live testing on the target server.
