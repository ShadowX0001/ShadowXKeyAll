package com.shadowx.keyall;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.Sound;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ShadowXKeyAll extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Long> nextRuns = new LinkedHashMap<>();
    private final Map<String, Long> pausedRemaining = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> pending = new HashMap<>();
    private final Map<UUID, Long> activity = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> scoreboards = new HashMap<>();
    private final Set<String> countdownMarks = new HashSet<>();
    private final List<String> history = new ArrayList<>();
    private FileConfiguration messages, rewards, announcements, gui, schedules, sounds, playersData, historyData;
    private BukkitTask ticker;
    private boolean timersRunning;
    private long lastSave;
    private long lastPendingRetry;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadAllFiles();
        loadData();
        initializeTimers();
        PluginCommand command = getCommand("shadowxkeyall");
        if (command == null) {
            getLogger().severe("Command shadowxkeyall is missing from plugin.yml; disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("integrations.placeholderapi", true)
                && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ShadowXKeyAllExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered: %shadowxkeyall_<placeholder>%");
        }
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            activity.put(player.getUniqueId(), System.currentTimeMillis());
            updateBar(player);
            deliverPending(player);
        }
        getLogger().info("Loaded " + nextRuns.size() + " independent key timer(s).");
        getLogger().info("ShadowXKeyAll enabled successfully.");
    }

    @Override public void onDisable() {
        if (ticker != null) ticker.cancel();
        saveData();
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
    }

    private File file(String name) {
        File f = new File(getDataFolder(), name);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!f.exists()) {
            try { f.createNewFile(); } catch (IOException e) { getLogger().severe("Cannot create " + name + ": " + e.getMessage()); }
        }
        return f;
    }

    private FileConfiguration loadYaml(String name) {
        File f = file(name);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(f);
            return yaml;
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().severe("Cannot load " + name + ": " + ex.getMessage());
            File backup = new File(f.getParentFile(), f.getName() + ".broken-" + System.currentTimeMillis());
            try {
                Files.copy(f.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().severe("Invalid file backed up to " + backup.getPath());
            } catch (IOException copyError) {
                getLogger().severe("Could not back up invalid " + name + ": " + copyError.getMessage());
            }
            if (getResource(name) != null) {
                if (!f.delete()) getLogger().warning("Could not replace invalid config file " + name);
                saveResource(name, false);
                try { yaml.load(f); return yaml; }
                catch (IOException | InvalidConfigurationException reloadError) {
                    getLogger().severe("Bundled default for " + name + " could not be loaded: " + reloadError.getMessage());
                }
            }
            return new YamlConfiguration();
        }
    }

    private void loadAllFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        String[] names = {"messages.yml", "rewards.yml", "announcements.yml", "gui.yml", "schedules.yml", "sounds.yml"};
        for (String name : names) if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
        messages = loadYaml("messages.yml");
        rewards = loadYaml("rewards.yml");
        announcements = loadYaml("announcements.yml");
        gui = loadYaml("gui.yml");
        schedules = loadYaml("schedules.yml");
        sounds = loadYaml("sounds.yml");
        playersData = loadYaml("data/players.yml");
        historyData = loadYaml("data/history.yml");
        validateConfiguration();
    }

    private void validateConfiguration() {
        ConfigurationSection keys = rewards.getConfigurationSection("keys");
        if (keys == null || keys.getKeys(false).isEmpty()) {
            getLogger().severe("rewards.yml: missing keys section or no key definitions. Timers will remain empty.");
            return;
        }
        for (String id : keys.getKeys(false)) {
            String path = "keys." + id;
            if (rewards.getLong(path + ".interval-seconds", 0) < 1)
                getLogger().warning("rewards.yml: " + path + ".interval-seconds must be at least 1; using 3600.");
            String mode = rewards.getString(path + ".mode", getConfig().getString("distribution.default-mode", "ONLINE_AND_OFFLINE"));
            if (!List.of("ONLINE_ONLY", "ONLINE_AND_OFFLINE", "BOTH").contains(mode.toUpperCase(Locale.ROOT)))
                getLogger().warning("rewards.yml: invalid mode for " + id + "; using ONLINE_AND_OFFLINE.");
        }
    }

    private void initializeTimers() {
        ConfigurationSection keys = rewards.getConfigurationSection("keys");
        if (keys == null) return;
        long now = System.currentTimeMillis();
        nextRuns.keySet().removeIf(id -> !keys.getBoolean(id + ".enabled", true));
        for (String id : keys.getKeys(false)) {
            if (!keys.getBoolean(id + ".enabled", true)) continue;
            long interval = Math.max(1, keys.getLong(id + ".interval-seconds", 3600)) * 1000L;
            nextRuns.putIfAbsent(id, now + interval);
        }
        }

    private void loadData() {
        ConfigurationSection timers = playersData.getConfigurationSection("timers");
        if (timers != null) for (String id : timers.getKeys(false)) {
            long next = timers.getLong(id + ".next-run", 0);
            if (next > 0) nextRuns.put(id, next);
            if (timers.contains(id + ".paused-remaining")) pausedRemaining.put(id, timers.getLong(id + ".paused-remaining"));
        }
        timersRunning = playersData.getBoolean("state.running", getConfig().getBoolean("timer.start-on-enable", true));
        ConfigurationSection queued = playersData.getConfigurationSection("pending");
        if (queued != null) for (String uuidText : queued.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                ConfigurationSection perPlayer = queued.getConfigurationSection(uuidText);
                if (perPlayer == null) continue;
                Map<String, Integer> map = new HashMap<>();
                for (String key : perPlayer.getKeys(false)) {
                    int amount = perPlayer.getInt(key);
                    if (amount > 0) map.put(key, amount);
                }
                if (!map.isEmpty()) pending.put(uuid, map);
            } catch (IllegalArgumentException ex) { getLogger().warning("Ignoring invalid player UUID in data/players.yml: " + uuidText); }
        }
        history.clear();
        history.addAll(historyData.getStringList("entries"));
    }

    private void saveData() {
        playersData.set("state.running", timersRunning);
        playersData.set("timers", null);
        for (String id : nextRuns.keySet()) {
            playersData.set("timers." + id + ".next-run", nextRuns.get(id));
            if (pausedRemaining.containsKey(id)) playersData.set("timers." + id + ".paused-remaining", pausedRemaining.get(id));
        }
        playersData.set("pending", null);
        pending.forEach((uuid, map) -> map.forEach((key, amount) ->
            playersData.set("pending." + uuid + "." + key, amount)));
        try { playersData.save(file("data/players.yml")); }
        catch (IOException ex) { getLogger().severe("Could not save data/players.yml: " + ex.getMessage()); }
        historyData.set("entries", history);
        try { historyData.save(file("data/history.yml")); }
        catch (IOException ex) { getLogger().severe("Could not save data/history.yml: " + ex.getMessage()); }
    }

    private Set<String> keyIds() { return new LinkedHashSet<>(nextRuns.keySet()); }
    private String displayName(String id) { return color(rewards.getString("keys." + id + ".display-name", id)); }
    private int amount(String id) { return Math.max(1, rewards.getInt("keys." + id + ".amount", 1)); }
    private long interval(String id) {
        String base = "keys." + id + ".";
        long seconds = Math.max(1, rewards.getLong(base + "interval-seconds", 3600));
        if (rewards.getBoolean(base + "dynamic-interval.enabled", false)) {
            long configuredBase = Math.max(1, rewards.getLong(base + "dynamic-interval.base-seconds", seconds));
            long perPlayer = rewards.getLong(base + "dynamic-interval.seconds-per-online-player", 0);
            long min = Math.max(1, rewards.getLong(base + "dynamic-interval.min-seconds", 60));
            long max = Math.max(min, rewards.getLong(base + "dynamic-interval.max-seconds", configuredBase));
            seconds = Math.max(min, Math.min(max, configuredBase + perPlayer * Bukkit.getOnlinePlayers().size()));
        }
        return seconds * 1000L;
    }
    private String mode(String id) { return rewards.getString("keys." + id + ".mode", getConfig().getString("distribution.default-mode", "ONLINE_AND_OFFLINE")); }

    private void tick() {
        if (!getConfig().getBoolean("maintenance.enabled", false)) runSchedules();
        long now = System.currentTimeMillis();
        if (timersRunning && !getConfig().getBoolean("maintenance.enabled", false)) {
            for (String id : new ArrayList<>(nextRuns.keySet())) {
                long left = Math.max(0, (nextRuns.get(id) - now + 999) / 1000);
                if (left > 0 && left <= 10 && sounds.getBoolean("countdown.enabled", true)
                        && countdownMarks.add(id + ":" + left)) playAll("countdown");
                announceCountdown(id, left);
                if (now >= nextRuns.get(id)) distribute(id);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateBar(player);
            updateScoreboard(player);
            if (getConfig().getBoolean("countdown.actionbar.enabled", false)) {
                String nearest = nearestTimer();
                if (nearest != null) player.sendActionBar(color(getConfig().getString("countdown.actionbar.message", "&bNext key in %time%")
                        .replace("%time%", format(Math.max(0, nextRuns.get(nearest) - now)))
                        .replace("%key%", displayName(nearest))));
            }
            if (getConfig().getBoolean("countdown.chat.enabled", false)) {
                for (String id : nextRuns.keySet()) {
                    long left = Math.max(0, (nextRuns.get(id) - now + 999) / 1000);
                    if (getConfig().getIntegerList("countdown.chat.seconds").contains((int) left)
                            && countdownMarks.add("chat:" + id + ":" + left))
                        broadcast(msg("countdown.chat").replace("%time%", format(left * 1000L)).replace("%key%", displayName(id)));
                }
            }
        }
        if (now - lastPendingRetry >= 10000L) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Map<String, Integer> queued = pending.get(player.getUniqueId());
                if (queued != null && !queued.isEmpty()) deliverPending(player);
            }
            lastPendingRetry = now;
        }
        if (now - lastSave >= Math.max(10, getConfig().getLong("timer.save-interval-seconds", 30)) * 1000L) {
            saveData(); lastSave = now;
        }
    }

    private void announceCountdown(String id, long left) {
        if (!announcements.getBoolean("enabled", true) || !getConfig().getBoolean("announcements.enabled", true)) return;
        if (!announcements.getIntegerList("before-seconds").contains((int) left)) return;
        if (!countdownMarks.add("announce:" + id + ":" + left)) return;
        String text = announcements.getString("messages.warning", msg("announcements.warning"));
        text = text.replace("%key%", displayName(id)).replace("%time%", format(left * 1000L));
        if (announcements.getBoolean("channels.chat", true)) broadcast(text);
        if (announcements.getBoolean("channels.title", true)) {
            String title = announcements.getString("messages.title", "%key%").replace("%key%", displayName(id));
            String subtitle = announcements.getString("messages.subtitle", "");
            subtitle = subtitle.replace("%time%", format(left * 1000L));
            for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(color(title), color(subtitle), 5, 35, 10);
        }
        if (announcements.getBoolean("channels.sound", true)) playAll("announcement");
    }

    private void distribute(String id) {
        int delivered = 0, queued = 0, count = amount(id);
        String distributionMode = mode(id).toUpperCase(Locale.ROOT);
        if (!distributionMode.equals("OFFLINE_ONLY")) for (Player p : Bukkit.getOnlinePlayers()) {
            if (!eligible(p, id)) continue;
            int actualCount = count;
            double luckyChance = rewards.getDouble("keys." + id + ".lucky-chance-percent", 0.0);
            if (luckyChance > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble(100.0) < luckyChance)
                actualCount += Math.max(1, rewards.getInt("keys." + id + ".lucky-extra-amount", 1));
            if (give(p, id, actualCount)) delivered++;
            else queue(p.getUniqueId(), id, actualCount);
        }
        if (!distributionMode.equals("ONLINE_ONLY")) {
            for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                if (off.isOnline() || !off.hasPlayedBefore()) continue;
                queue(off.getUniqueId(), id, count);
                queued++;
            }
        }
        String text = announcements.getString("messages.distributed", msg("announcements.distributed"));
        text = text.replace("%key%", displayName(id)).replace("%amount%", String.valueOf(count))
                .replace("%online%", String.valueOf(delivered)).replace("%offline%", String.valueOf(queued));
        if (announcements.getBoolean("channels.chat", true)) broadcast(text);
        addHistory("DISTRIBUTE key=" + id + " amount=" + count + " online=" + delivered + " queued=" + queued);
        nextRuns.put(id, System.currentTimeMillis() + interval(id));
        countdownMarks.removeIf(mark -> mark.startsWith(id + ":") || mark.startsWith("chat:" + id + ":") || mark.startsWith("announce:" + id + ":"));
        saveData();
        getLogger().info("Key cycle " + id + ": delivered=" + delivered + ", queued=" + queued);
        sendDiscord("Key cycle **" + id + "**: delivered " + delivered + ", queued " + queued + ".");
    }

    private void queue(UUID uuid, String id, int count) {
        pending.computeIfAbsent(uuid, ignored -> new HashMap<>()).merge(id, count, Integer::sum);
    }

    private boolean eligible(Player p, String id) {
        long playerNext = playersData.getLong("player-timers." + p.getUniqueId() + "." + id, 0L);
        if (playerNext > System.currentTimeMillis()) return false;
        String perm = rewards.getString("keys." + id + ".permission", "");
        if (!perm.isBlank() && !p.hasPermission(perm)) return false;
        if (getConfig().getBoolean("eligibility.require-not-afk", false)) {
            long idle = System.currentTimeMillis() - activity.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
            if (idle >= getConfig().getLong("eligibility.afk-timeout-seconds", 300) * 1000L) return false;
        }
        long min = rewards.getLong("keys." + id + ".minimum-playtime-seconds", 0);
        if (p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L < min) return false;
        return !rewards.getStringList("keys." + id + ".worlds-blacklist").contains(p.getWorld().getName());
    }

    private boolean give(Player player, String id, int count) {
        List<String> commands = rewards.getStringList("keys." + id + ".commands");
        if (commands.isEmpty()) return false;
        boolean success = true;
        for (String raw : commands) {
            String command = raw.replace("%player%", player.getName()).replace("%uuid%", player.getUniqueId().toString())
                    .replace("%key%", id).replace("%amount%", String.valueOf(count));
            try { success &= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
            catch (RuntimeException ex) { success = false; getLogger().warning("Reward command failed for " + id + ": " + ex.getMessage()); }
        }
        if (success) {
            long playerInterval = rewards.getLong("keys." + id + ".player-interval-seconds", 0L);
            if (playerInterval > 0) playersData.set("player-timers." + player.getUniqueId() + "." + id,
                    System.currentTimeMillis() + playerInterval * 1000L);
            player.sendMessage(color(msg("prefix") + msg("rewards.received").replace("%amount%", String.valueOf(count)).replace("%key%", displayName(id))));
            play(player, "reward");
            addHistory("GIVE player=" + player.getUniqueId() + " key=" + id + " amount=" + count);
        }
        return success;
    }

    private void deliverPending(Player player) {
        if (getConfig().getBoolean("maintenance.enabled", false)) return;
        if (!getConfig().getBoolean("distribution.retry-pending-on-join", true)) return;
        Map<String, Integer> map = pending.get(player.getUniqueId());
        if (map == null || map.isEmpty()) return;
        for (String id : new ArrayList<>(map.keySet())) {
            if (!eligible(player, id)) continue;
            int count = map.getOrDefault(id, 0);
            if (count > 0 && give(player, id, count)) map.remove(id);
        }
        if (map.isEmpty()) pending.remove(player.getUniqueId());
        saveData();
    }

    private void updateBar(Player p) {
        if (!getConfig().getBoolean("countdown.bossbar.enabled", true)) {
            BossBar old = bars.remove(p.getUniqueId());
            if (old != null) old.removeAll();
            return;
        }
        String id = nearestTimer();
        if (id == null) return;
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), ignored ->
                Bukkit.createBossBar("", barColor(), barStyle()));
        long left = Math.max(0, nextRuns.get(id) - System.currentTimeMillis());
        String title = getConfig().getString("countdown.bossbar.title", "&bShadowXKeyAll &8» &f%time%");
        bar.setTitle(color(title.replace("%time%", format(left)).replace("%key%", displayName(id))));
        bar.setColor(barColor()); bar.setStyle(barStyle());
        long total = interval(id);
        bar.setProgress(Math.max(0, Math.min(1, 1.0 - (double) left / total)));
        if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }

    private String nearestTimer() {
        String nearest = null; long soonest = Long.MAX_VALUE;
        for (String id : nextRuns.keySet()) {
            long value = nextRuns.get(id);
            if (value < soonest) { soonest = value; nearest = id; }
        }
        return nearest;
    }
    private BarColor barColor() { try { return BarColor.valueOf(getConfig().getString("countdown.bossbar.color", "BLUE").toUpperCase(Locale.ROOT)); } catch (Exception e) { return BarColor.BLUE; } }
    private BarStyle barStyle() { try { return BarStyle.valueOf(getConfig().getString("countdown.bossbar.style", "SOLID").toUpperCase(Locale.ROOT)); } catch (Exception e) { return BarStyle.SOLID; } }

    private void openGui(Player p) {
        if (!getConfig().getBoolean("gui.enabled", true)) return;
        int size = gui.getInt("size", 45);
        if (size < 9 || size > 54 || size % 9 != 0) size = 45;
        Inventory inv = Bukkit.createInventory(null, size, color(gui.getString("title", "&8ShadowXKeyAll")));
        if (gui.getBoolean("filler.enabled", true)) {
            Material filler = material(gui.getString("filler.material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
            for (int i = 0; i < size; i++) inv.setItem(i, item(filler, gui.getString("filler.name", " "), List.of()));
        }
        int overviewSlot = gui.getInt("items.overview.slot", 4);
        String nearestId = nearestTimer();
        String nextText = nearestId == null ? "--:--" : format(Math.max(0, nextRuns.get(nearestId) - System.currentTimeMillis()));
        List<String> overviewLore = new ArrayList<>();
        for (String line : gui.getStringList("items.overview.lore")) {
            overviewLore.add(color(line.replace("%key_count%", String.valueOf(keyIds().size()))
                    .replace("%next%", nextText).replace("%next_key%", nearestId == null ? "none" : nearestId)));
        }
        inv.setItem(Math.max(0, Math.min(size - 1, overviewSlot)),
                item(material(gui.getString("items.overview.material", "NETHER_STAR"), Material.NETHER_STAR),
                        gui.getString("items.overview.name", "&bKeyAll Overview"), overviewLore));
        int slot = 10;
        for (String id : keyIds()) {
            if (slot >= size - 1) break;
            String name = gui.getString("items.keys.name", "&b%key_name%").replace("%key_name%", displayName(id)).replace("%key_id%", id);
            List<String> lore = gui.getStringList("items.keys.lore");
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(color(line.replace("%key_name%", displayName(id)).replace("%key_id%", id)
                    .replace("%amount%", String.valueOf(amount(id))).replace("%time%", format(Math.max(0, nextRuns.get(id) - System.currentTimeMillis())))));
            inv.setItem(slot++, item(material(gui.getString("items.keys.material", "TRIPWIRE_HOOK"), Material.TRIPWIRE_HOOK), name, lines));
        }
        int pendingSlot = gui.getInt("items.pending.slot", size - 5);
        int total = pending.getOrDefault(p.getUniqueId(), Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum();
        List<String> lore = gui.getStringList("items.pending.lore").stream().map(s -> color(s.replace("%pending_total%", String.valueOf(total)))).toList();
        inv.setItem(Math.max(0, Math.min(size - 1, pendingSlot)), item(material(gui.getString("items.pending.material", "CHEST"), Material.CHEST),
                color(gui.getString("items.pending.name", "&aYour Pending Keys")), lore));
        p.openInventory(inv);
    }

    private ItemStack item(Material type, String name, List<String> lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) { meta.setDisplayName(color(name)); meta.setLore(lore); stack.setItemMeta(meta); }
        return stack;
    }
    private Material material(String name, Material fallback) { Material m = Material.matchMaterial(name); return m == null ? fallback : m; }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(color(gui.getString("title", "&8ShadowXKeyAll")))) {
            e.setCancelled(true);
            return;
        }
        if (title.equals(color("&8ShadowXKeyAll Admin Settings"))) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player player) || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
            switch (e.getRawSlot()) {
                case 10 -> toggleSetting(player, "countdown.bossbar.enabled");
                case 12 -> toggleSetting(player, "countdown.actionbar.enabled");
                case 14 -> toggleSetting(player, "countdown.chat.enabled");
                case 16 -> toggleSetting(player, "countdown.scoreboard.enabled");
                case 22 -> toggleSetting(player, "maintenance.enabled");
                default -> { return; }
            }
            openAdminGui(player);
        }
    }

    private void toggleSetting(Player player, String path) {
        boolean value = !getConfig().getBoolean(path, false);
        getConfig().set(path, value);
        saveConfig();
        refreshBars();
        send(player, "&e" + path + " &7set to " + (value ? "&aON" : "&cOFF"));
    }

    private void openAdminGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&8ShadowXKeyAll Admin Settings"));
        Material[] mats = {Material.DRAGON_BREATH, Material.FEATHER, Material.PAPER, Material.OAK_SIGN, Material.REDSTONE_BLOCK};
        String[] paths = {"countdown.bossbar.enabled", "countdown.actionbar.enabled", "countdown.chat.enabled",
                "countdown.scoreboard.enabled", "maintenance.enabled"};
        String[] names = {"BossBar", "ActionBar", "Chat Countdown", "Scoreboard", "Maintenance Mode"};
        int[] slots = {10, 12, 14, 16, 22};
        for (int i = 0; i < slots.length; i++) {
            boolean enabled = getConfig().getBoolean(paths[i], false);
            inv.setItem(slots[i], item(mats[i], (enabled ? "&a&lON " : "&c&lOFF ") + names[i],
                    List.of(color("&7Click to toggle"), color("&8" + paths[i]))));
        }
        player.openInventory(inv);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        activity.put(p.getUniqueId(), System.currentTimeMillis());
        applyJoinBonuses(p);
        deliverPending(p);
        updateBar(p);
        updateScoreboard(p);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BossBar b = bars.remove(id); if (b != null) b.removeAll();
        scoreboards.remove(id);
        e.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()))
            activity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) { activity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }
    @EventHandler public void onCommandUse(PlayerCommandPreprocessEvent e) { activity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                if (!p.hasPermission("shadowxkeyall.use")) { send(p, msg("no-permission")); return true; }
                openGui(p);
            } else send(sender, "&eActive key timers: &b" + nextRuns.size());
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status") || sub.equals("preview")) {
            for (String id : keyIds()) send(sender, "&b" + id + "&7: " + (timersRunning ? format(Math.max(0, nextRuns.get(id) - System.currentTimeMillis())) : "paused/stopped"));
            return true;
        }
        if (!sender.hasPermission("shadowxkeyall.admin")) { send(sender, msg("no-permission")); return true; }
        try {
            switch (sub) {
                case "settings" -> {
                    if (sender instanceof Player p) openAdminGui(p);
                    else send(sender, "&cThis command can only be used in-game.");
                }
                case "reload" -> {
                    saveData();
                    reloadConfig(); loadAllFiles();
                    for (String id : new ArrayList<>(nextRuns.keySet())) if (!rewards.getBoolean("keys." + id + ".enabled", true)) nextRuns.remove(id);
                    initializeTimers(); refreshBars(); send(sender, msg("admin.reloaded"));
                }
                case "start" -> { timersRunning = true; for (String id : keyIds()) if (nextRuns.get(id) <= System.currentTimeMillis()) nextRuns.put(id, System.currentTimeMillis() + interval(id)); send(sender, msg("admin.started")); }
                case "stop" -> { timersRunning = false; pausedRemaining.clear(); send(sender, msg("admin.stopped")); }
                case "pause" -> { if (timersRunning) for (String id : keyIds()) pausedRemaining.put(id, Math.max(0, nextRuns.get(id) - System.currentTimeMillis())); timersRunning = false; send(sender, msg("admin.paused")); }
                case "resume" -> { timersRunning = true; pausedRemaining.forEach((id, left) -> nextRuns.put(id, System.currentTimeMillis() + left)); pausedRemaining.clear(); send(sender, msg("admin.resumed")); }
                case "reset" -> {
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /keyall reset <keyId>");
                    requireKey(args[1]);
                    nextRuns.put(args[1], System.currentTimeMillis() + interval(args[1]));
                    countdownMarks.removeIf(mark -> mark.startsWith(args[1] + ":") || mark.contains(":" + args[1] + ":"));
                    send(sender, "&aTimer reset for " + args[1] + ".");
                }
                case "set" -> {
                    if (args.length < 3) throw new IllegalArgumentException("Usage: /keyall set <keyId> <seconds>");
                    requireKey(args[1]); nextRuns.put(args[1], System.currentTimeMillis() + Math.max(1, Long.parseLong(args[2])) * 1000L);
                    send(sender, "&aTimer for " + args[1] + " updated.");
                }
                case "give" -> {
                    if (args.length < 4) throw new IllegalArgumentException("Usage: /keyall give <player> <keyId> <amount>");
                    requireKey(args[2]); int n = Math.max(1, Integer.parseInt(args[3]));
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target != null) {
                        if (!give(target, args[2], n)) throw new IllegalStateException("Reward command failed; check rewards.yml.");
                        send(sender, "&aReward processed for online player.");
                    } else {
                        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                        queue(offline.getUniqueId(), args[2], n);
                        saveData();
                        send(sender, "&aQueued " + n + " " + args[2] + " key(s) for " + args[1] + ".");
                    }
                }
                case "giveall" -> {
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /keyall giveall <keyId> [amount]");
                    requireKey(args[1]); int n = args.length > 2 ? Math.max(1, Integer.parseInt(args[2])) : amount(args[1]);
                    int done = 0; for (Player p : Bukkit.getOnlinePlayers()) if (give(p, args[1], n)) done++;
                    send(sender, "&aProcessed rewards for " + done + " online player(s).");
                }
                case "pending" -> {
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /keyall pending <player>");
                    org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    Map<String, Integer> map = pending.getOrDefault(target.getUniqueId(), Collections.emptyMap());
                    send(sender, "&ePending keys: &f" + map);
                }
                case "clearpending" -> {
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /keyall clearpending <player>");
                    pending.remove(Bukkit.getOfflinePlayer(args[1]).getUniqueId()); send(sender, "&aPending rewards cleared.");
                }
                case "history" -> {
                    int count = Math.min(10, history.size());
                    if (count == 0) send(sender, "&7No history yet.");
                    else for (String line : history.subList(history.size() - count, history.size())) send(sender, "&7" + line);
                }
                default -> send(sender, msg("help"));
            }
        } catch (NumberFormatException ex) { send(sender, msg("errors.invalid-number")); }
        catch (IllegalArgumentException | IllegalStateException ex) { send(sender, "&c" + ex.getMessage()); }
        saveData();
        return true;
    }

    private void requireKey(String id) { if (!nextRuns.containsKey(id)) throw new IllegalArgumentException(msg("errors.key-not-found")); }

    public String placeholderValue(String identifier, Player player) {
        if (identifier.equalsIgnoreCase("keys_count")) return String.valueOf(nextRuns.size());
        if (identifier.equalsIgnoreCase("pending_total")) {
            return String.valueOf(pending.getOrDefault(player.getUniqueId(), Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum());
        }
        if (identifier.equalsIgnoreCase("next_key")) {
            String id = nearestTimer();
            return id == null ? "" : id;
        }
        if (identifier.equalsIgnoreCase("next_time")) {
            String id = nearestTimer();
            return id == null ? "--:--" : format(Math.max(0, nextRuns.get(id) - System.currentTimeMillis()));
        }
        if (identifier.startsWith("time_")) {
            String id = identifier.substring(5);
            return nextRuns.containsKey(id) ? format(Math.max(0, nextRuns.get(id) - System.currentTimeMillis())) : "";
        }
        if (identifier.startsWith("pending_")) {
            String id = identifier.substring(8);
            return String.valueOf(pending.getOrDefault(player.getUniqueId(), Collections.emptyMap()).getOrDefault(id, 0));
        }
        return null;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "start", "stop", "pause", "resume", "reload", "set", "reset", "give", "giveall", "pending", "clearpending", "history", "preview", "settings").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && List.of("set", "reset", "giveall").contains(args[0].toLowerCase(Locale.ROOT))) return keyIds().stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && List.of("give", "pending", "clearpending").contains(args[0].toLowerCase(Locale.ROOT))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return keyIds().stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }

    private void refreshBars() { for (Player p : Bukkit.getOnlinePlayers()) { updateBar(p); updateScoreboard(p); } }

    private void applyJoinBonuses(Player player) {
        UUID uuid = player.getUniqueId();
        boolean firstJoin = !playersData.getBoolean("players." + uuid + ".first-join-claimed", false);
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        YearMonth month = YearMonth.from(today);
        for (String id : keyIds()) {
            String base = "keys." + id + ".";
            if (firstJoin) grantBonus(player, id, rewards.getInt(base + "first-join-bonus", 0), "first-join");
            claimCalendarBonus(player, id, "daily", rewards.getInt(base + "daily-bonus", 0), today.toString());
            claimCalendarBonus(player, id, "weekly", rewards.getInt(base + "weekly-bonus", 0), monday.toString());
            claimCalendarBonus(player, id, "monthly", rewards.getInt(base + "monthly-bonus", 0), month.toString());
        }
        if (firstJoin) playersData.set("players." + uuid + ".first-join-claimed", true);
        saveData();
    }

    private void claimCalendarBonus(Player player, String id, String period, int count, String periodKey) {
        if (count <= 0) return;
        String path = "players." + player.getUniqueId() + ".claims." + period + "." + id;
        if (periodKey.equals(playersData.getString(path, ""))) return;
        grantBonus(player, id, count, period);
        playersData.set(path, periodKey);
        if (period.equals("daily")) {
            String streakPath = "players." + player.getUniqueId() + ".streak." + id;
            String previous = playersData.getString(streakPath + ".last-day", "");
            LocalDate current = LocalDate.parse(periodKey);
            int streak = previous.equals(current.minusDays(1).toString())
                    ? playersData.getInt(streakPath + ".days", 0) + 1 : 1;
            playersData.set(streakPath + ".days", streak);
            playersData.set(streakPath + ".last-day", periodKey);
            int every = rewards.getInt("keys." + id + ".streak-every-days", 0);
            int streakBonus = rewards.getInt("keys." + id + ".streak-bonus", 0);
            if (every > 0 && streakBonus > 0 && streak % every == 0)
                grantBonus(player, id, streakBonus, "streak-" + streak);
        }
    }

    private void grantBonus(Player player, String id, int count, String reason) {
        if (count <= 0 || !nextRuns.containsKey(id)) return;
        if (!eligible(player, id) || !give(player, id, count)) queue(player.getUniqueId(), id, count);
        addHistory("BONUS type=" + reason + " player=" + player.getUniqueId() + " key=" + id + " amount=" + count);
    }

    private void grantScheduled(String scheduleName, List<String> ids) {
        for (String id : ids) {
            if (!nextRuns.containsKey(id)) { getLogger().warning("schedules.yml: unknown key id '" + id + "' in " + scheduleName); continue; }
            int count = amount(id), delivered = 0, queued = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!eligible(player, id)) continue;
                if (give(player, id, count)) delivered++; else { queue(player.getUniqueId(), id, count); queued++; }
            }
            for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                if (off.isOnline() || !off.hasPlayedBefore()) continue;
                queue(off.getUniqueId(), id, count); queued++;
            }
            addHistory("SCHEDULE name=" + scheduleName + " key=" + id + " online=" + delivered + " queued=" + queued);
            getLogger().info("Schedule " + scheduleName + " granted " + id + ": online=" + delivered + ", queued=" + queued);
        }
        saveData();
    }

    private void runSchedules() {
        if (!schedules.getBoolean("enabled", false)) return;
        ZoneId zone;
        try { zone = ZoneId.of(schedules.getString("timezone", "server").equalsIgnoreCase("server") ?
                java.time.ZoneId.systemDefault().getId() : schedules.getString("timezone")); }
        catch (Exception ex) { zone = ZoneId.systemDefault(); }
        ZonedDateTime now = ZonedDateTime.now(zone);
        String minute = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        if (schedules.getBoolean("daily.enabled", false) && now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")).equals(schedules.getString("daily.time", "18:00")))
            fireScheduleOnce("daily", minute, schedules.getStringList("daily.keys"));
        if (schedules.getBoolean("weekly.enabled", false) &&
                now.getDayOfWeek().name().equalsIgnoreCase(schedules.getString("weekly.day", "SUNDAY")) &&
                now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")).equals(schedules.getString("weekly.time", "18:00")))
            fireScheduleOnce("weekly", minute, schedules.getStringList("weekly.keys"));
        if (schedules.getBoolean("monthly.enabled", false) && now.getDayOfMonth() == schedules.getInt("monthly.day", 1) &&
                now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")).equals(schedules.getString("monthly.time", "18:00")))
            fireScheduleOnce("monthly", minute, schedules.getStringList("monthly.keys"));
    }

    private void fireScheduleOnce(String name, String minute, List<String> ids) {
        if (minute.equals(playersData.getString("schedules.last." + name, ""))) return;
        playersData.set("schedules.last." + name, minute);
        grantScheduled(name, ids);
    }

    private void updateScoreboard(Player player) {
        if (!getConfig().getBoolean("countdown.scoreboard.enabled", false)) {
            if (scoreboards.remove(player.getUniqueId()) != null)
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            return;
        }
        org.bukkit.scoreboard.Scoreboard board = scoreboards.computeIfAbsent(player.getUniqueId(), id ->
                Bukkit.getScoreboardManager().getNewScoreboard());
        org.bukkit.scoreboard.Objective objective = board.getObjective("sxka");
        if (objective == null) objective = board.registerNewObjective("sxka", org.bukkit.scoreboard.Criteria.DUMMY,
                color(getConfig().getString("countdown.scoreboard.title", "&b&lKEYALL")));
        objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        String id = nearestTimer();
        String time = id == null ? "--:--" : format(Math.max(0, nextRuns.get(id) - System.currentTimeMillis()));
        String keyName = id == null ? "No active key" : displayName(id);
        String[] lines = {color("&7Next: &f" + keyName), color("&7Time: &b" + time), color("&8ShadowXKeyAll")};
        new HashSet<>(board.getEntries()).forEach(board::resetScores);
        for (int i = 0; i < lines.length; i++) objective.getScore(lines[i]).setScore(lines.length - i);
        player.setScoreboard(board);
    }


    private void addHistory(String entry) {
        history.add(Instant.now() + " " + entry);
        while (history.size() > 500) history.remove(0);
    }
    private void sendDiscord(String message) {
        if (!getConfig().getBoolean("integrations.discord.enabled", false)) return;
        String webhook = getConfig().getString("integrations.discord.webhook-url", "").trim();
        if (webhook.isEmpty()) return;
        String json = "{\"content\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> { getLogger().warning("Discord webhook request failed: " + error.getMessage()); return null; });
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid Discord webhook URL in config.yml.");
        }
    }

    private void playAll(String path) { for (Player p : Bukkit.getOnlinePlayers()) play(p, path); }
    private void play(Player p, String path) {
        if (!sounds.getBoolean(path + ".enabled", false)) return;
        String name = sounds.getString(path + ".sound", "");
        try { p.playSound(p.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)),
                (float) sounds.getDouble(path + ".volume", 1), (float) sounds.getDouble(path + ".pitch", 1)); }
        catch (IllegalArgumentException ex) { getLogger().warning("Invalid sound in sounds.yml: " + name); }
    }
    private String msg(String path) { return messages.getString(path, path); }
    private void send(CommandSender sender, String text) { sender.sendMessage(color(msg("prefix") + text)); }
    private void broadcast(String text) { if (text != null) Bukkit.broadcastMessage(color(text)); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    private String format(long millis) {
        long sec = Math.max(0, (millis + 999) / 1000), h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
