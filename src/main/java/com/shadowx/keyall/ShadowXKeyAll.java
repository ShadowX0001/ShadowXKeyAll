package com.shadowx.keyall;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

public final class ShadowXKeyAll extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private long nextRun;
    private long pausedRemaining = -1;
    private boolean running;
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private final Set<UUID> afk = new HashSet<>();
    private final Map<UUID, Long> activity = new HashMap<>();
    private final Map<UUID, Integer> pending = new HashMap<>();
    private final Map<UUID, Long> playtime = new HashMap<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask ticker;
    private long lastSave;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadData();
        running = getConfig().getBoolean("timer.start-on-enable", true);
        if (nextRun <= 0) nextRun = System.currentTimeMillis() + intervalMillis();
        if (getCommand("keyall") != null) { getCommand("keyall").setExecutor(this); getCommand("keyall").setTabCompleter(this); }
        Bukkit.getPluginManager().registerEvents(this, this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) onJoin(new PlayerJoinEvent(p, ""));
        getLogger().info("ShadowXKeyAll enabled. Timer state restored.");
    }
    @Override public void onDisable() { if (ticker != null) ticker.cancel(); saveData(); bars.values().forEach(BossBar::removeAll); bars.clear(); }

    private long intervalMillis() { return Math.max(1, getConfig().getLong("timer.interval-seconds", 3600)) * 1000L; }
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            long idle = now - activity.getOrDefault(p.getUniqueId(), now);
            boolean isAfk = idle >= getConfig().getLong("eligibility.afk-timeout-seconds", 300) * 1000L;
            if (isAfk) afk.add(p.getUniqueId()); else afk.remove(p.getUniqueId());
            updateBar(p);
            if (getConfig().getBoolean("countdown.actionbar")) p.sendActionBar(color("&bNext key in &f" + format(Math.max(0, nextRun-now))));
        }
        if (running && now >= nextRun) distribute();
        if (running && getConfig().getBoolean("announcements.enabled")) {
            long left = Math.max(0, (nextRun-now)/1000);
            for (int s : getConfig().getIntegerList("announcements.before-seconds"))
                if (left == s) broadcast(getConfig().getString("announcements.warning"), Map.of("%time%", format(left*1000L)));
        }
        if (now-lastSave >= 30000) { saveData(); lastSave=now; }
    }
    private void distribute() {
        int amount = Math.max(1, getConfig().getInt("distribution.amount", 1));
        String key = getConfig().getString("distribution.key-id", "vote");
        int paid=0, queued=0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!eligible(p)) continue;
            give(p, amount, key); paid++;
        }
        if ("ONLINE_AND_OFFLINE".equalsIgnoreCase(getConfig().getString("distribution.mode","ONLINE_AND_OFFLINE"))) {
            for (var entry : Bukkit.getOfflinePlayers()) {
                if (entry.isOnline() || !entry.hasPlayedBefore()) continue;
                if (!getConfig().getString("eligibility.permission","").isBlank() && !entry.isOp()) continue;
                pending.merge(entry.getUniqueId(), amount, Integer::sum); queued++;
            }
        }
        broadcast(getConfig().getString("announcements.distributed"), Map.of("%amount%", String.valueOf(amount), "%key%", key));
        getLogger().info("KeyAll completed. Online rewards: "+paid+", offline queued: "+queued);
        nextRun = System.currentTimeMillis() + intervalMillis();
        saveData();
    }
    private boolean eligible(Player p) {
        String perm=getConfig().getString("eligibility.permission","");
        if (!perm.isBlank() && !p.hasPermission(perm)) return false;
        if (getConfig().getBoolean("eligibility.require-not-afk") && afk.contains(p.getUniqueId())) return false;
        if (getConfig().getLong("eligibility.minimum-playtime-seconds") > p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)/20L) return false;
        return !getConfig().getStringList("eligibility.worlds-blacklist").contains(p.getWorld().getName());
    }
    private void give(Player p, int amount, String key) {
        List<String> commands=getConfig().getStringList("distribution.commands");
        if (commands.isEmpty()) { pending.merge(p.getUniqueId(), amount, Integer::sum); return; }
        for (String cmd : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%",p.getName()).replace("%key%",key).replace("%amount%",String.valueOf(amount)));
    }
    private void updateBar(Player p) {
        if (!getConfig().getBoolean("countdown.bossbar")) return;
        long left=Math.max(0,nextRun-System.currentTimeMillis());
        BossBar bar=bars.computeIfAbsent(p.getUniqueId(), id -> Bukkit.createBossBar(color("&bKeyAll &8» &f%time%"), BarColor.BLUE, BarStyle.SOLID));
        bar.setTitle(color("&bNext KeyAll &8» &f"+format(left)));
        bar.setProgress(Math.max(0,Math.min(1,1.0-(double)left/intervalMillis())));
        if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }
    private void openGui(Player p) {
        int size=getConfig().getInt("gui.size",27); if(size<9||size>54||size%9!=0) size=27;
        Inventory inv=Bukkit.createInventory(null,size,color(getConfig().getString("gui.title","&8ShadowXKeyAll")));
        Material mat=Material.matchMaterial(getConfig().getString("gui.material","TRIPWIRE_HOOK")); if(mat==null) mat=Material.TRIPWIRE_HOOK;
        ItemStack item=new ItemStack(mat); ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(color("&bNext Key Reward"));
        meta.setLore(List.of(color("&7Key: &f"+getConfig().getString("distribution.key-id","vote")),color("&7Amount: &f"+getConfig().getInt("distribution.amount",1)),color("&7Time: &f"+format(Math.max(0,nextRun-System.currentTimeMillis())))));
        item.setItemMeta(meta); int slot=Math.max(0,Math.min(size-1,getConfig().getInt("gui.info-slot",13))); inv.setItem(slot,item); p.openInventory(inv);
    }
    @EventHandler public void onClick(InventoryClickEvent e) { if(e.getView().getTitle().equals(color(getConfig().getString("gui.title","&8ShadowXKeyAll")))) e.setCancelled(true); }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); activity.put(p.getUniqueId(),System.currentTimeMillis());
        int owed=pending.getOrDefault(p.getUniqueId(),0);
        if(owed>0) { give(p,owed,getConfig().getString("distribution.key-id","vote")); pending.remove(p.getUniqueId()); saveData(); }
        updateBar(p);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { BossBar b=bars.remove(e.getPlayer().getUniqueId()); if(b!=null)b.removeAll(); }
    @EventHandler public void onMove(PlayerMoveEvent e) { if(e.getTo()!=null && (e.getTo().getBlockX()!=e.getFrom().getBlockX()||e.getTo().getBlockY()!=e.getFrom().getBlockY()||e.getTo().getBlockZ()!=e.getFrom().getBlockZ())) activity.put(e.getPlayer().getUniqueId(),System.currentTimeMillis()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { activity.put(e.getPlayer().getUniqueId(),System.currentTimeMillis()); }
    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if(a.length==0){ if(s instanceof Player p)openGui(p); else send(s,"&eNext KeyAll: &b"+format(Math.max(0,nextRun-System.currentTimeMillis()))); return true; }
        String sub=a[0].toLowerCase(Locale.ROOT);
        if(!s.hasPermission("shadowxkeyall.admin")){send(s,getConfig().getString("messages.no-permission"));return true;}
        switch(sub){
            case "status" -> send(s,getConfig().getString("messages.status").replace("%time%",format(Math.max(0,nextRun-System.currentTimeMillis()))));
            case "reload" -> {reloadConfig();send(s,getConfig().getString("messages.reloaded"));}
            case "start" -> {running=true;if(pausedRemaining>=0){nextRun=System.currentTimeMillis()+pausedRemaining;pausedRemaining=-1;}else if(nextRun<System.currentTimeMillis())nextRun=System.currentTimeMillis()+intervalMillis();send(s,getConfig().getString("messages.started"));}
            case "stop" -> {running=false;pausedRemaining=-1;send(s,getConfig().getString("messages.stopped"));}
            case "pause" -> {if(running){pausedRemaining=Math.max(0,nextRun-System.currentTimeMillis());running=false;}send(s,getConfig().getString("messages.paused"));}
            case "resume" -> {if(!running){running=true;if(pausedRemaining>=0){nextRun=System.currentTimeMillis()+pausedRemaining;pausedRemaining=-1;}}send(s,getConfig().getString("messages.resumed"));}
            case "set" -> {if(a.length<2){send(s,"&cUsage: /keyall set <seconds>");break;}nextRun=System.currentTimeMillis()+Math.max(1,Long.parseLong(a[1]))*1000L;running=true;send(s,"&aTimer updated.");}
            case "give" -> {if(a.length<3){send(s,"&c/keyall give <player> <amount>");break;}Player t=Bukkit.getPlayerExact(a[1]);if(t==null){send(s,"&cPlayer must be online.");break;}give(t,Math.max(1,Integer.parseInt(a[2])),getConfig().getString("distribution.key-id","vote"));send(s,"&aKey reward sent.");}
            case "giveall" -> {int n=a.length>1?Math.max(1,Integer.parseInt(a[1])):1;for(Player p:Bukkit.getOnlinePlayers())give(p,n,getConfig().getString("distribution.key-id","vote"));send(s,"&aRewards sent.");}
            case "preview" -> send(s,"&ePreview: &f"+getConfig().getInt("distribution.amount",1)+"x "+getConfig().getString("distribution.key-id","vote")+" in "+format(Math.max(0,nextRun-System.currentTimeMillis())));
            default -> send(s,getConfig().getString("messages.help"));
        }
        saveData();return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){return args.length==1?List.of("status","start","stop","pause","resume","reload","set","give","giveall","preview").stream().filter(x->x.startsWith(args[0].toLowerCase())).toList():List.of();}
    private void send(CommandSender s,String m){s.sendMessage(color(getConfig().getString("messages.prefix","")+m));}
    private void broadcast(String m,Map<String,String> vars){if(m==null)return;for(var e:vars.entrySet())m=m.replace(e.getKey(),e.getValue());Bukkit.broadcastMessage(color(m));}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
    private String format(long ms){long sec=Math.max(0,ms/1000);return String.format("%02d:%02d:%02d",sec/3600,(sec%3600)/60,sec%60);}
    private void loadData(){if(!getDataFolder().exists())getDataFolder().mkdirs();org.bukkit.configuration.file.YamlConfiguration d=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(),"data.yml"));nextRun=d.getLong("next-run",0);running=d.getBoolean("running",true);pausedRemaining=d.getLong("paused-remaining",-1);for(String k:d.getKeys(false))if(k.startsWith("pending."))try{pending.put(UUID.fromString(k.substring(8)),d.getInt(k));}catch(IllegalArgumentException ignored){}}
    private void saveData(){org.bukkit.configuration.file.YamlConfiguration d=new org.bukkit.configuration.file.YamlConfiguration();d.set("next-run",nextRun);d.set("running",running);d.set("paused-remaining",pausedRemaining);pending.forEach((id,n)->d.set("pending."+id,n));try{d.save(new java.io.File(getDataFolder(),"data.yml"));}catch(java.io.IOException ex){getLogger().severe("Could not save data.yml: "+ex.getMessage());}}
}
