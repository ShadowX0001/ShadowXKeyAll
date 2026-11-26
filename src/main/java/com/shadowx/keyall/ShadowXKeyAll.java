package com.shadowx.keyall;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
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
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ShadowXKeyAll extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private long nextRun, pausedRemaining=-1, lastSave;
    private boolean running;
    private final Map<UUID,Integer> pending=new HashMap<>();
    private final Map<UUID,Long> activity=new HashMap<>();
    private final Map<UUID,BossBar> bars=new HashMap<>();
    private BukkitTask ticker;
    private org.bukkit.configuration.file.FileConfiguration messages, sounds;
    private final Set<Long> soundMarks = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig(); loadTextFiles(); loadData();
        if(nextRun<=0){nextRun=System.currentTimeMillis()+intervalMillis();running=getConfig().getBoolean("timer.start-on-enable",true);}
        PluginCommand command=getCommand("shadowxkeyall");
        if(command!=null){command.setExecutor(this);command.setTabCompleter(this);}
        Bukkit.getPluginManager().registerEvents(this,this);
        ticker=Bukkit.getScheduler().runTaskTimer(this,this::tick,20L,20L);
        for(Player p:Bukkit.getOnlinePlayers()){activity.put(p.getUniqueId(),System.currentTimeMillis());updateBar(p);deliverPending(p);}
        getLogger().info("ShadowXKeyAll enabled; timer restored.");
    }
    @Override public void onDisable(){if(ticker!=null)ticker.cancel();saveData();bars.values().forEach(BossBar::removeAll);bars.clear();}
    private void loadTextFiles(){
        File mf=new File(getDataFolder(),"messages.yml"); if(!mf.exists())saveResource("messages.yml",false);
        File sf=new File(getDataFolder(),"sounds.yml"); if(!sf.exists())saveResource("sounds.yml",false);
        messages=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(mf);
        sounds=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(sf);
    }
    private String msg(String path){return messages==null?path:messages.getString(path,path);}
    private void playConfigured(Player p,String path){
        if(sounds==null||!sounds.getBoolean(path+".enabled",true))return;
        String name=sounds.getString(path+".sound",""); if(name.isBlank())return;
        try{p.playSound(p.getLocation(),org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT)),(float)sounds.getDouble(path+".volume",1),(float)sounds.getDouble(path+".pitch",1));}
        catch(IllegalArgumentException ex){getLogger().warning("Invalid sounds.yml sound: "+name);}
    }
    private long intervalMillis(){return Math.max(1,getConfig().getLong("timer.interval-seconds",3600))*1000L;}
    private String key(){return getConfig().getString("distribution.key-id","vote");}
    private int amount(){return Math.max(1,getConfig().getInt("distribution.amount",1));}
    private String mode(){return getConfig().getString("distribution.mode","ONLINE_AND_OFFLINE");}

    private void tick(){
        long now=System.currentTimeMillis();
        long countdownSeconds=Math.max(0,(nextRun-now+999)/1000);
        if(running && getConfig().getBoolean("countdown.sounds-enabled",true) && countdownSeconds>0 && countdownSeconds<=10 && soundMarks.add(countdownSeconds)){
            for(Player online:Bukkit.getOnlinePlayers())playConfigured(online,"countdown");
        }
        for(Player p:Bukkit.getOnlinePlayers()){
            updateBar(p);
            if(getConfig().getBoolean("countdown.actionbar"))p.sendActionBar(color(msg("countdown.actionbar").replace("%time%",format(Math.max(0,nextRun-now)))));
            long seconds=Math.max(0,(nextRun-now+999)/1000);
            if(running && getConfig().getBoolean("countdown.chat-enabled",false) && getConfig().getIntegerList("countdown.chat-seconds").contains((int)seconds) && soundMarks.add(1000L+seconds))broadcast(msg("countdown.chat"),Map.of("%time%",format(seconds*1000L)));
        }
        if(running && now>=nextRun)distribute();
        if(running && getConfig().getBoolean("announcements.enabled")){
            long left=Math.max(0,(nextRun-now)/1000);
            for(int sec:getConfig().getIntegerList("announcements.before-seconds"))
                if(left==sec)broadcast(msg("announcements.warning"),Map.of("%time%",format(left*1000L)));
        }
        if(now-lastSave>=Math.max(10,getConfig().getLong("storage.save-interval-seconds",30))*1000L){saveData();lastSave=now;}
    }
    private void distribute(){
        int paid=0,queued=0;
        for(Player p:Bukkit.getOnlinePlayers())if(eligible(p)){if(reward(p,amount(),key()))paid++;else if(getConfig().getStringList("distribution.commands").isEmpty())pending.merge(p.getUniqueId(),amount(),Integer::sum);}
        if(mode().equalsIgnoreCase("ONLINE_AND_OFFLINE")){
            for(OfflinePlayer p:Bukkit.getOfflinePlayers()){
                if(p.isOnline()||!p.hasPlayedBefore())continue;
                pending.merge(p.getUniqueId(),amount(),Integer::sum);queued++;
            }
        }
        broadcast(msg("announcements.distributed"),Map.of("%amount%",String.valueOf(amount()),"%key%",key(),"%online%",String.valueOf(paid),"%offline%",String.valueOf(queued)));
        for(Player p:Bukkit.getOnlinePlayers())playConfigured(p,"reward");
        soundMarks.clear();
        getLogger().info("KeyAll cycle: "+paid+" delivered online; "+queued+" queued offline.");
        nextRun=System.currentTimeMillis()+intervalMillis();saveData();
    }
    private boolean eligible(Player p){
        String perm=getConfig().getString("eligibility.permission","");
        if(!perm.isBlank()&&!p.hasPermission(perm))return false;
        if(getConfig().getBoolean("eligibility.require-not-afk",false)){
            long idle=System.currentTimeMillis()-activity.getOrDefault(p.getUniqueId(),System.currentTimeMillis());
            if(idle>=getConfig().getLong("eligibility.afk-timeout-seconds",300)*1000L)return false;
        }
        if(p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L<getConfig().getLong("eligibility.minimum-playtime-seconds",0))return false;
        return !getConfig().getStringList("eligibility.worlds-blacklist").contains(p.getWorld().getName());
    }
    private boolean reward(Player p,int count,String crate){
        List<String> commands=getConfig().getStringList("distribution.commands");
        if(commands.isEmpty())return false;
        boolean success=true;
        for(String raw:commands){
            String cmd=raw.replace("%player%",p.getName()).replace("%uuid%",p.getUniqueId().toString()).replace("%key%",crate).replace("%amount%",String.valueOf(count));
            try{success &= Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);}catch(Exception ex){success=false;getLogger().warning("Reward command failed: "+ex.getMessage());}
        }
        return success;
    }
    private void deliverPending(Player p){
        int owed=pending.getOrDefault(p.getUniqueId(),0);if(owed<=0||!eligible(p))return;
        if(reward(p,owed,key())){pending.remove(p.getUniqueId());saveData();}
    }
    private void updateBar(Player p){
        if(!getConfig().getBoolean("countdown.bossbar",true))return;
        BossBar bar=bars.computeIfAbsent(p.getUniqueId(),id->Bukkit.createBossBar("",parseColor(getConfig().getString("countdown.bossbar-color","BLUE")),parseStyle(getConfig().getString("countdown.bossbar-style","SOLID"))));
        long left=Math.max(0,nextRun-System.currentTimeMillis());
        bar.setTitle(color(getConfig().getString("countdown.bossbar-title","&bShadowXKeyAll &8» &f%time%").replace("%time%",format(left))));
        bar.setProgress(Math.max(0,Math.min(1,1.0-(double)left/intervalMillis())));
        if(!bar.getPlayers().contains(p))bar.addPlayer(p);
    }
    private BarColor parseColor(String v){try{return BarColor.valueOf(v.toUpperCase(Locale.ROOT));}catch(Exception e){return BarColor.BLUE;}}
    private BarStyle parseStyle(String v){try{return BarStyle.valueOf(v.toUpperCase(Locale.ROOT));}catch(Exception e){return BarStyle.SOLID;}}
    private void openGui(Player p){
        int size=getConfig().getInt("gui.size",27);if(size<9||size>54||size%9!=0)size=27;
        Inventory inv=Bukkit.createInventory(null,size,color(getConfig().getString("gui.title","&8ShadowXKeyAll")));
        Material material=Material.matchMaterial(getConfig().getString("gui.material","TRIPWIRE_HOOK"));if(material==null)material=Material.TRIPWIRE_HOOK;
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(color("&bNext Key Reward"));
        meta.setLore(List.of(color("&7Crate: &f"+key()),color("&7Amount: &f"+amount()),color("&7Next distribution: &f"+format(Math.max(0,nextRun-System.currentTimeMillis()))),color("&7Pending keys: &f"+pending.getOrDefault(p.getUniqueId(),0))));
        item.setItemMeta(meta);int slot=Math.max(0,Math.min(size-1,getConfig().getInt("gui.info-slot",13)));inv.setItem(slot,item);p.openInventory(inv);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){if(e.getView().getTitle().equals(color(getConfig().getString("gui.title","&8ShadowXKeyAll"))))e.setCancelled(true);}
    @EventHandler public void onJoin(PlayerJoinEvent e){Player p=e.getPlayer();activity.put(p.getUniqueId(),System.currentTimeMillis());deliverPending(p);updateBar(p);}
    @EventHandler public void onQuit(PlayerQuitEvent e){BossBar b=bars.remove(e.getPlayer().getUniqueId());if(b!=null)b.removeAll();}
    @EventHandler public void onMove(PlayerMoveEvent e){if(e.getTo()!=null&&(e.getFrom().getBlockX()!=e.getTo().getBlockX()||e.getFrom().getBlockY()!=e.getTo().getBlockY()||e.getFrom().getBlockZ()!=e.getTo().getBlockZ()))activity.put(e.getPlayer().getUniqueId(),System.currentTimeMillis());}
    @EventHandler public void onInteract(PlayerInteractEvent e){activity.put(e.getPlayer().getUniqueId(),System.currentTimeMillis());}
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e){activity.put(e.getPlayer().getUniqueId(),System.currentTimeMillis());}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==0){if(sender instanceof Player p)openGui(p);else send(sender,"&eTime remaining: &b"+format(Math.max(0,nextRun-System.currentTimeMillis())));return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("status")){send(sender,"&eTimer: &f"+(running?"running":"stopped")+" &7| &eNext: &b"+format(Math.max(0,nextRun-System.currentTimeMillis())));return true;}
        if(!sender.hasPermission("shadowxkeyall.admin")){send(sender,msg("no-permission"));return true;}
        try{
            switch(sub){
                case "reload"->{reloadConfig();loadTextFiles();bars.values().forEach(b->{b.setColor(parseColor(getConfig().getString("countdown.bossbar-color","BLUE")));b.setStyle(parseStyle(getConfig().getString("countdown.bossbar-style","SOLID")));});send(sender,msg("admin.reloaded"));}
                case "start"->{running=true;if(pausedRemaining>=0){nextRun=System.currentTimeMillis()+pausedRemaining;pausedRemaining=-1;}else if(nextRun<=System.currentTimeMillis())nextRun=System.currentTimeMillis()+intervalMillis();send(sender,"&aTimer started.");}
                case "stop"->{running=false;pausedRemaining=-1;send(sender,"&cTimer stopped.");}
                case "pause"->{if(running){pausedRemaining=Math.max(0,nextRun-System.currentTimeMillis());running=false;}send(sender,"&eTimer paused.");}
                case "resume"->{if(!running){running=true;if(pausedRemaining>=0){nextRun=System.currentTimeMillis()+pausedRemaining;pausedRemaining=-1;}}send(sender,"&aTimer resumed.");}
                case "set"->{if(args.length<2)throw new IllegalArgumentException("Usage: /keyall set <seconds>");nextRun=System.currentTimeMillis()+Math.max(1,Long.parseLong(args[1]))*1000L;running=true;send(sender,"&aTimer updated.");}
                case "give"->{if(args.length<3)throw new IllegalArgumentException("Usage: /keyall give <player> <amount>");Player target=Bukkit.getPlayerExact(args[1]);if(target==null)throw new IllegalArgumentException("Player must be online.");if(!reward(target,Math.max(1,Integer.parseInt(args[2])),key()))throw new IllegalStateException("Reward command failed; check distribution.commands.");send(sender,"&aReward issued.");}
                case "giveall"->{int n=args.length>1?Math.max(1,Integer.parseInt(args[1])):1;for(Player p:Bukkit.getOnlinePlayers())reward(p,n,key());send(sender,"&aRewards processed.");}
                case "preview"->send(sender,"&ePreview: &f"+amount()+"x "+key()+" in "+format(Math.max(0,nextRun-System.currentTimeMillis())));
                case "pending"->{if(args.length<2)throw new IllegalArgumentException("Usage: /keyall pending <player>");OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);send(sender,"&ePending: &f"+pending.getOrDefault(target.getUniqueId(),0));}
                case "clearpending"->{if(args.length<2)throw new IllegalArgumentException("Usage: /keyall clearpending <player>");OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);pending.remove(target.getUniqueId());send(sender,"&aPending rewards cleared.");}
                default->send(sender,msg("help"));
            }
        }catch(NumberFormatException ex){send(sender,"&cInvalid number.");}catch(IllegalArgumentException|IllegalStateException ex){send(sender,"&c"+ex.getMessage());}
        saveData();return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] args){
        if(args.length==1)return List.of("status","start","stop","pause","resume","reload","set","give","giveall","preview","pending","clearpending").stream().filter(v->v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&(args[0].equalsIgnoreCase("give")||args[0].equalsIgnoreCase("pending")||args[0].equalsIgnoreCase("clearpending")))return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }
    private void send(CommandSender s,String message){s.sendMessage(color(msg("prefix")+message));}
    private void broadcast(String message,Map<String,String> vars){if(message==null)return;for(var entry:vars.entrySet())message=message.replace(entry.getKey(),entry.getValue());Bukkit.broadcastMessage(color(message));}
    private String color(String text){return ChatColor.translateAlternateColorCodes('&',text==null?"":text);}
    private String format(long millis){long sec=Math.max(0,(millis+999)/1000);long h=sec/3600,m=(sec%3600)/60,s=sec%60;return h>0?String.format(Locale.ROOT,"%02d:%02d:%02d",h,m,s):String.format(Locale.ROOT,"%02d:%02d",m,s);}
    private File dataFile(){return new File(getDataFolder(),"data.yml");}
    private void loadData(){
        if(!getDataFolder().exists())getDataFolder().mkdirs();
        var data=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile());
        nextRun=data.getLong("next-run",0);running=data.getBoolean("running",getConfig().getBoolean("timer.start-on-enable",true));pausedRemaining=data.getLong("paused-remaining",-1);
        if(data.isConfigurationSection("pending"))for(String id:data.getConfigurationSection("pending").getKeys(false))try{pending.put(UUID.fromString(id),data.getInt("pending."+id));}catch(IllegalArgumentException ignored){}
    }
    private void saveData(){
        var data=new org.bukkit.configuration.file.YamlConfiguration();data.set("next-run",nextRun);data.set("running",running);data.set("paused-remaining",pausedRemaining);
        pending.forEach((uuid,count)->data.set("pending."+uuid,count));
        try{data.save(dataFile());}catch(IOException ex){getLogger().severe("Could not save data.yml: "+ex.getMessage());}
    }
}