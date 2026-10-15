package me.jjonlinux.minecraftabilities;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftAbilities extends JavaPlugin implements Listener {
    private static final String MAIN = ChatColor.DARK_PURPLE + "Minecraft Abilities";
    private static final String XRAY = ChatColor.DARK_AQUA + "Xray Block Settings";
    private static final int DURATION = Integer.MAX_VALUE;
    private static final long REVEAL_MS = 3000L;
    private final Map<UUID,String> pendingLevel = new ConcurrentHashMap<>();
    private final Map<UUID,FileConfiguration> playerConfigs = new HashMap<>();
    private final Map<UUID,File> playerFiles = new HashMap<>();
    // Keys include world UUID so block coordinates from different worlds cannot collide.
    private final Map<UUID,Set<String>> hidden = new HashMap<>();
    private final Map<UUID,Map<String,Long>> revealed = new HashMap<>();
    private BukkitTask xrayTask;

    private enum Ability {
        SPEEDMINE("SpeedMine", Material.IRON_PICKAXE, PotionEffectType.HASTE, 255),
        JUMPBOOST("JumpBoost", Material.RABBIT_FOOT, PotionEffectType.JUMP_BOOST, 255),
        INVIS("Invis", Material.GLASS, PotionEffectType.INVISIBILITY, 1),
        SPEED("Speed", Material.SUGAR, PotionEffectType.SPEED, 255),
        STRENGTH("Strength", Material.BLAZE_POWDER, PotionEffectType.STRENGTH, 7),
        NIGHT_VISION("Night Vision", Material.GOLDEN_CARROT, PotionEffectType.NIGHT_VISION, 1),
        RESISTANCE("Resistance", Material.SHIELD, PotionEffectType.RESISTANCE, 7),
        FIRERES("FireRes", Material.MAGMA_CREAM, PotionEffectType.FIRE_RESISTANCE, 1),
        LEVITATION("Levitation", Material.SHULKER_SHELL, PotionEffectType.LEVITATION, 255),
        MAY_FLY("May Fly", Material.FEATHER, null, 1),
        XRAY("Xray", Material.SPYGLASS, null, 1);
        final String label; final Material icon; final PotionEffectType effect; final int max;
        Ability(String label, Material icon, PotionEffectType effect, int max) { this.label=label; this.icon=icon; this.effect=effect; this.max=max; }
        String key() { return name().toLowerCase(Locale.ROOT); }
        boolean potion() { return effect != null; }
        static Ability parse(String input) {
            String n=input.replace("_","").replace("-","").replace(" ","").toLowerCase(Locale.ROOT);
            for (Ability a:values()) if (a.key().replace("_","").equals(n) || a.label.replace(" ","").toLowerCase(Locale.ROOT).equals(n)) return a;
            if (n.equals("nightvision")) return NIGHT_VISION;
            if (n.equals("mayfly")) return MAY_FLY;
            return null;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        for (Ability a:Ability.values()) {
            getConfig().addDefault("abilities."+a.key()+".enabled", false);
            getConfig().addDefault("abilities."+a.key()+".level", 1);
        }
        getConfig().addDefault("xray.view-radius", 6);
        getConfig().addDefault("xray.safe-radius", 2);
        getConfig().addDefault("xray.update-period-ticks", 4);
        for (Material m: defaultBlocks()) getConfig().addDefault("xray.hidden-blocks."+m.name(), true);
        getConfig().options().copyDefaults(true);
        // These are defaults for new player files, not live shared player settings.
        getConfig().set("xray.view-radius", clamp(getConfig().getInt("xray.view-radius", 6), 1, 8));
        getConfig().set("xray.safe-radius", clamp(getConfig().getInt("xray.safe-radius", 2), 1, 3));
        saveConfig();
        getServer().getPluginManager().registerEvents(this,this);
        xrayTask=Bukkit.getScheduler().runTaskTimer(this,this::tickXray,1L,Math.max(2L,getConfig().getLong("xray.update-period-ticks",4L)));
        for (Player p:Bukkit.getOnlinePlayers()) { loadPlayerData(p); applyAll(p); }
    }
    @Override public void onDisable() {
        if (xrayTask!=null) xrayTask.cancel();
        for (Player p:Bukkit.getOnlinePlayers()) {
            restoreAll(p); clearEffects(p); savePlayerData(p);
            if (p.getGameMode()!=GameMode.CREATIVE && p.getGameMode()!=GameMode.SPECTATOR) { p.setFlying(false); p.setAllowFlight(false); }
        }
        saveConfig();
    }
    private List<Material> defaultBlocks() { return List.of(Material.STONE,Material.GRAVEL,Material.DIRT,Material.GRASS_BLOCK,Material.DIORITE,Material.GRANITE,Material.DEEPSLATE,Material.TUFF); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("This command can only be used by a player."); return true; }
        if (!p.hasPermission("minecraftabilities.use")) { p.sendMessage(ChatColor.RED+"You do not have permission."); return true; }
        if (command.getName().equalsIgnoreCase("abilities")) { openMain(p); return true; }
        if (!command.getName().equalsIgnoreCase("ability")) return false;
        if (args.length==0 || args[0].equalsIgnoreCase("help")) { help(p); return true; }
        Ability a=Ability.parse(args[0]);
        if (a==null) { p.sendMessage(ChatColor.RED+"Unknown ability. Use /ability help."); return true; }
        Integer level=null; String blockSettings=null;
        if (a==Ability.XRAY && args.length>1) {
            blockSettings=String.join(" ",Arrays.copyOfRange(args,1,args.length));
            if (!validateXray(blockSettings,p)) return true;
        } else if (args.length>1) {
            if (!a.potion() || args.length!=2) { p.sendMessage(ChatColor.RED+"Usage: /ability "+a.key()+" [level]"); return true; }
            try { level=Integer.parseInt(args[1]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED+"Level must be a whole number."); return true; }
            if (level<1 || level>a.max) { p.sendMessage(ChatColor.RED+"Level must be 1-"+a.max+" for "+a.label+"."); return true; }
        }
        FileConfiguration pc=playerConfig(p);
        if (level!=null) pc.set("abilities."+a.key()+".level",level);
        if (blockSettings!=null) applyXraySettings(p,blockSettings);
        boolean enabled=!pc.getBoolean("abilities."+a.key()+".enabled",false);
        pc.set("abilities."+a.key()+".enabled",enabled); savePlayerData(p); applyAbility(p,a);
        p.sendMessage((enabled?ChatColor.GREEN:ChatColor.YELLOW)+a.label+(enabled?" enabled":" disabled")+(level==null?"":" (level "+level+")")+".");
        return true;
    }
    private void help(Player p) {
        p.sendMessage(ChatColor.GOLD+"Minecraft Abilities");
        p.sendMessage(ChatColor.YELLOW+"/ability <name> [level] "+ChatColor.GRAY+"- toggle an ability");
        p.sendMessage(ChatColor.YELLOW+"/ability xray [STONE:true, GRANITE:false] "+ChatColor.GRAY+"- toggle Xray and update listed blocks");
        p.sendMessage(ChatColor.YELLOW+"/abilities "+ChatColor.GRAY+"- open GUI");
        p.sendMessage(ChatColor.GRAY+"Levels: 1-255 except Strength/Resistance 1-7. Single-level effects accept 1 only.");
    }

    private void openMain(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,MAIN); Ability[] as=Ability.values(); int[] slots={10,12,14,16,19,21,23,25,29,31,33};
        for(int i=0;i<as.length;i++) inv.setItem(slots[i],abilityItem(p,as[i]));
        inv.setItem(49,item(Material.BARRIER,ChatColor.RED+"Close",List.of())); p.openInventory(inv);
    }
    private void openXray(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,XRAY); FileConfiguration pc=playerConfig(p); List<Material> ms=configuredMaterials(p);
        for(int i=0;i<Math.min(45,ms.size());i++){ Material m=ms.get(i); inv.setItem(i,pane(m.name(),pc.getBoolean("xray.hidden-blocks."+m.name(),getConfig().getBoolean("xray.hidden-blocks."+m.name(),false)),List.of("Left-click to toggle whether this block is hidden"))); }
        int radius=clamp(pc.getInt("xray.view-radius",getConfig().getInt("xray.view-radius",6)),1,8);
        int safe=clamp(pc.getInt("xray.safe-radius",getConfig().getInt("xray.safe-radius",2)),1,3);
        inv.setItem(45,item(Material.REDSTONE_TORCH,ChatColor.AQUA+"Xray Radius: -",List.of(ChatColor.GRAY+"Current: "+radius,ChatColor.GRAY+"How far from you Xray scans",ChatColor.GRAY+"for configured blocks.",ChatColor.YELLOW+"Click to decrease",ChatColor.GRAY+"Minimum: 1 | Maximum: 8")));
        inv.setItem(46,item(Material.GLOWSTONE_DUST,ChatColor.AQUA+"Xray Radius: +",List.of(ChatColor.GRAY+"Current: "+radius,ChatColor.GRAY+"A larger radius reveals blocks",ChatColor.GRAY+"farther away but uses more",ChatColor.GRAY+"server processing.",ChatColor.YELLOW+"Click to increase",ChatColor.GRAY+"Minimum: 1 | Maximum: 8")));
        inv.setItem(47,item(Material.REDSTONE_TORCH,ChatColor.GOLD+"Safe Radius: -",List.of(ChatColor.GRAY+"Current: "+safe,ChatColor.GRAY+"Keeps nearby blocks visible",ChatColor.GRAY+"to help avoid client collision",ChatColor.GRAY+"prediction and rubber-banding.",ChatColor.YELLOW+"Click to decrease",ChatColor.GRAY+"Minimum: 1 | Maximum: 3")));
        inv.setItem(48,item(Material.GLOWSTONE_DUST,ChatColor.GOLD+"Safe Radius: +",List.of(ChatColor.GRAY+"Current: "+safe,ChatColor.GRAY+"A larger safe radius is safer",ChatColor.GRAY+"but makes nearby Xray less",ChatColor.GRAY+"effective.",ChatColor.YELLOW+"Click to increase",ChatColor.GRAY+"Minimum: 1 | Maximum: 3")));
        inv.setItem(49,item(Material.ARROW,ChatColor.YELLOW+"Back",List.of("Return to the abilities menu")));
        inv.setItem(50,item(Material.BOOK,ChatColor.WHITE+"Xray Radius Help",List.of(ChatColor.AQUA+"Xray radius",ChatColor.GRAY+"The area around you checked",ChatColor.GRAY+"for blocks selected below.",ChatColor.GOLD+"Safe radius",ChatColor.GRAY+"Nearby blocks stay visible to",ChatColor.GRAY+"reduce movement/collision issues.",ChatColor.GRAY+"Default safe radius: 2")));
        p.openInventory(inv);
    }
    private ItemStack abilityItem(Player p,Ability a) {
        FileConfiguration pc=playerConfig(p); boolean on=pc.getBoolean("abilities."+a.key()+".enabled",false); int level=pc.getInt("abilities."+a.key()+".level",1);
        return pane(a.label,on,List.of(ChatColor.GRAY+"Status: "+(on?ChatColor.GREEN+"ON":ChatColor.RED+"OFF"),ChatColor.GRAY+"Level: "+level,ChatColor.YELLOW+"Left-click: toggle",ChatColor.YELLOW+(a==Ability.XRAY?"Right-click: block settings":a.potion()&&a.max>1?"Right-click: change level":"Right-click: no level setting")));
    }
    private ItemStack pane(String title,boolean on,List<String> lore) { ItemStack it=item(on?Material.LIME_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,(on?ChatColor.GREEN:ChatColor.RED)+title,lore); if(on){ItemMeta m=it.getItemMeta();m.addEnchant(Enchantment.UNBREAKING,1,true);m.addItemFlags(ItemFlag.HIDE_ENCHANTS);it.setItemMeta(m);}return it; }
    private ItemStack item(Material mat,String title,List<String> lore){ItemStack it=new ItemStack(mat);ItemMeta m=it.getItemMeta();m.setDisplayName(title);m.setLore(lore);it.setItemMeta(m);return it;}

    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return; String title=e.getView().getTitle(); if(!title.equals(MAIN)&&!title.equals(XRAY))return;
        e.setCancelled(true); if(e.getClickedInventory()==null||e.getClickedInventory()!=e.getView().getTopInventory())return; int slot=e.getRawSlot();
        if(title.equals(MAIN)) {
            if(slot==49){p.closeInventory();return;} Ability a=abilityAt(slot);if(a==null)return;
            if(e.isRightClick()){if(a==Ability.XRAY)openXray(p);else if(a.potion()&&a.max>1){pendingLevel.put(p.getUniqueId(),a.key());p.closeInventory();p.sendMessage(ChatColor.YELLOW+"Type level 1-"+a.max+" in chat, or 'cancel'.");}return;}
            if(e.isLeftClick()){toggle(p,a,null);openMain(p);}
        } else {
            if(slot==49){openMain(p);return;}
            if(!e.isLeftClick())return;
            if(slot>=45&&slot<=48){
                FileConfiguration pc=playerConfig(p);
                int radius=clamp(pc.getInt("xray.view-radius",getConfig().getInt("xray.view-radius",6)),1,8);
                int safe=clamp(pc.getInt("xray.safe-radius",getConfig().getInt("xray.safe-radius",2)),1,3);
                if(slot==45)radius=Math.max(1,radius-1);
                if(slot==46)radius=Math.min(8,radius+1);
                if(slot==47)safe=Math.max(1,safe-1);
                if(slot==48)safe=Math.min(3,safe+1);
                pc.set("xray.view-radius",radius);pc.set("xray.safe-radius",safe);savePlayerData(p);openXray(p);refreshXray(p);return;
            }
            ItemStack it=e.getCurrentItem();if(it==null||!it.hasItemMeta())return;
            String name=ChatColor.stripColor(it.getItemMeta().getDisplayName());Material m=name==null?null:Material.matchMaterial(name);if(m==null||!m.isBlock())return;
            String path="xray.hidden-blocks."+m.name();FileConfiguration pc=playerConfig(p);pc.set(path,!pc.getBoolean(path,getConfig().getBoolean(path,false)));savePlayerData(p);openXray(p);refreshXray(p);
        }
    }
    private Ability abilityAt(int slot){int[] s={10,12,14,16,19,21,23,25,29,31,33};Ability[] a=Ability.values();for(int i=0;i<s.length;i++)if(s[i]==slot)return a[i];return null;}
    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        UUID id=e.getPlayer().getUniqueId();String key=pendingLevel.get(id);if(key==null)return;e.setCancelled(true);String msg=e.getMessage().trim();
        Bukkit.getScheduler().runTask(this,()->{Player p=e.getPlayer();if(!p.isOnline()){pendingLevel.remove(id);return;}if(msg.equalsIgnoreCase("cancel")){pendingLevel.remove(id);p.sendMessage(ChatColor.GRAY+"Cancelled.");openMain(p);return;}
            Ability a=Ability.parse(key);int n;try{n=Integer.parseInt(msg);}catch(NumberFormatException ex){p.sendMessage(ChatColor.RED+"Enter a whole number or 'cancel'.");return;}
            if(a==null||n<1||n>a.max){p.sendMessage(ChatColor.RED+"Level must be between 1 and "+(a==null?255:a.max)+".");return;}pendingLevel.remove(id);playerConfig(p).set("abilities."+a.key()+".level",n);savePlayerData(p);applyAbility(p,a);p.sendMessage(ChatColor.GREEN+a.label+" level set to "+n+".");openMain(p);
        });
    }
    @EventHandler public void onJoin(PlayerJoinEvent e){Bukkit.getScheduler().runTask(this,()->{loadPlayerData(e.getPlayer());applyAll(e.getPlayer());});}
    @EventHandler public void onQuit(PlayerQuitEvent e){Player p=e.getPlayer();savePlayerData(p);playerConfigs.remove(p.getUniqueId());playerFiles.remove(p.getUniqueId());pendingLevel.remove(p.getUniqueId());hidden.remove(p.getUniqueId());revealed.remove(p.getUniqueId());}
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent e){Player p=e.getPlayer();restoreAll(p);hidden.remove(p.getUniqueId());revealed.remove(p.getUniqueId());Bukkit.getScheduler().runTask(this,()->applyAll(p));}
    @EventHandler public void onBlockDamage(BlockDamageEvent e){reveal(e.getPlayer(),e.getBlock());}
    @EventHandler public void onBlockBreak(BlockBreakEvent e){Player p=e.getPlayer();String k=key(e.getBlock().getLocation());Set<String>s=hidden.get(p.getUniqueId());if(s!=null)s.remove(k);Map<String,Long>r=revealed.get(p.getUniqueId());if(r!=null)r.remove(k);Bukkit.getScheduler().runTask(this,()->refreshXray(p));}

    private void toggle(Player p,Ability a,Integer level){FileConfiguration pc=playerConfig(p);if(level!=null)pc.set("abilities."+a.key()+".level",level);boolean on=!pc.getBoolean("abilities."+a.key()+".enabled",false);pc.set("abilities."+a.key()+".enabled",on);savePlayerData(p);applyAbility(p,a);}
    private void applyAll(Player p){for(Ability a:Ability.values())applyAbility(p,a);}
    private void applyAbility(Player p,Ability a){FileConfiguration pc=playerConfig(p);boolean on=pc.getBoolean("abilities."+a.key()+".enabled",false);int level=pc.getInt("abilities."+a.key()+".level",1);
        if(a==Ability.MAY_FLY){if(on)p.setAllowFlight(true);else if(p.getGameMode()!=GameMode.CREATIVE&&p.getGameMode()!=GameMode.SPECTATOR){p.setFlying(false);p.setAllowFlight(false);}return;}
        if(a==Ability.XRAY){if(on)refreshXray(p);else restoreAll(p);return;} if(a.effect==null)return;
        if(!on){p.removePotionEffect(a.effect);return;}p.addPotionEffect(new PotionEffect(a.effect,DURATION,Math.max(0,level-1),false,false,true),true);
    }
    private void clearEffects(Player p){for(Ability a:Ability.values())if(a.effect!=null)p.removePotionEffect(a.effect);}
    private void tickXray(){for(Player p:Bukkit.getOnlinePlayers()){if(playerConfig(p).getBoolean("abilities.xray.enabled",false))refreshXray(p);else if(hidden.containsKey(p.getUniqueId()))restoreAll(p);}}
    private void refreshXray(Player p){if(!p.isOnline()||!playerConfig(p).getBoolean("abilities.xray.enabled",false))return;
        UUID id=p.getUniqueId();Set<String> was=hidden.computeIfAbsent(id,k->new HashSet<>());Map<String,Long> rev=revealed.computeIfAbsent(id,k->new HashMap<>());long now=System.currentTimeMillis();rev.entrySet().removeIf(x->x.getValue()<=now);
        FileConfiguration pc=playerConfig(p);World w=p.getWorld();Location l=p.getLocation();int px=l.getBlockX(),py=l.getBlockY(),pz=l.getBlockZ();int radius=clamp(pc.getInt("xray.view-radius",getConfig().getInt("xray.view-radius",6)),1,8),safe=clamp(pc.getInt("xray.safe-radius",getConfig().getInt("xray.safe-radius",2)),1,3);
        int minY=Math.max(w.getMinHeight(),py-radius),maxY=Math.min(w.getMaxHeight()-1,py+radius);Set<Material> types=configuredHidden(p);Set<String>want=new HashSet<>();
        for(int x=px-radius;x<=px+radius;x++)for(int z=pz-radius;z<=pz+radius;z++){if(!w.isChunkLoaded(x>>4,z>>4))continue;for(int y=minY;y<=maxY;y++){Block b=w.getBlockAt(x,y,z);if(!types.contains(b.getType())||safe(x,y,z,px,py,pz,safe))continue;String k=key(b.getLocation());if(!rev.containsKey(k))want.add(k);}}
        for(String k:new HashSet<>(was))if(!want.contains(k)){Location loc=parseKey(k);if(loc!=null&&loc.getWorld().equals(w))p.sendBlockChange(loc,w.getBlockAt(loc).getBlockData());was.remove(k);}
        for(String k:want)if(was.add(k)){Location loc=parseKey(k);if(loc!=null)p.sendBlockChange(loc,Material.AIR.createBlockData());}
    }
    private boolean safe(int x,int y,int z,int px,int py,int pz,int r){int dx=x-px,dz=z-pz;return dx*dx+dz*dz<=r*r&&y>=py-2&&y<=py+3;}
    private void reveal(Player p,Block b){if(!playerConfig(p).getBoolean("abilities.xray.enabled",false)||!configuredHidden(p).contains(b.getType()))return;String k=key(b.getLocation());Set<String>s=hidden.get(p.getUniqueId());if(s!=null)s.remove(k);revealed.computeIfAbsent(p.getUniqueId(),x->new HashMap<>()).put(k,System.currentTimeMillis()+REVEAL_MS);p.sendBlockChange(b.getLocation(),b.getBlockData());}
    private void restoreAll(Player p){Set<String>s=hidden.remove(p.getUniqueId());revealed.remove(p.getUniqueId());if(s==null)return;for(String k:s){Location loc=parseKey(k);if(loc!=null&&loc.getWorld()!=null&&loc.getWorld().isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4))p.sendBlockChange(loc,loc.getBlock().getBlockData());}}
    private Set<Material> configuredHidden(Player p){Set<Material> out=new HashSet<>();FileConfiguration pc=playerConfig(p);if(pc.getConfigurationSection("xray.hidden-blocks")==null)return out;for(String n:pc.getConfigurationSection("xray.hidden-blocks").getKeys(false))if(pc.getBoolean("xray.hidden-blocks."+n,getConfig().getBoolean("xray.hidden-blocks."+n,false))){Material m=Material.matchMaterial(n);if(m!=null&&m.isBlock())out.add(m);}return out;}
    private List<Material> configuredMaterials(Player p){List<Material> out=new ArrayList<>();FileConfiguration pc=playerConfig(p);if(pc.getConfigurationSection("xray.hidden-blocks")!=null)for(String n:pc.getConfigurationSection("xray.hidden-blocks").getKeys(false)){Material m=Material.matchMaterial(n);if(m!=null&&m.isBlock())out.add(m);}out.sort(Comparator.comparing(Enum::name));return out;}
    private boolean validateXray(String input,Player p){for(String entry:input.split(",")){String[] pair=entry.trim().split(":",2);if(pair.length!=2){p.sendMessage(ChatColor.RED+"Use /ability xray STONE:true, GRANITE:false");return false;}Material m=Material.matchMaterial(pair[0].trim().toUpperCase(Locale.ROOT));if(m==null||!m.isBlock()){p.sendMessage(ChatColor.RED+"Unknown block: "+pair[0].trim());return false;}String v=pair[1].trim();if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false")){p.sendMessage(ChatColor.RED+"Expected true or false for "+m.name());return false;}}return true;}
    private void applyXraySettings(Player p,String input){FileConfiguration pc=playerConfig(p);for(String entry:input.split(",")){String[] pair=entry.trim().split(":",2);Material m=Material.matchMaterial(pair[0].trim().toUpperCase(Locale.ROOT));pc.set("xray.hidden-blocks."+m.name(),Boolean.parseBoolean(pair[1].trim()));}savePlayerData(p);}
    private FileConfiguration playerConfig(Player p) {
        UUID id=p.getUniqueId();
        FileConfiguration cfg=playerConfigs.get(id);
        if(cfg==null){loadPlayerData(p);cfg=playerConfigs.get(id);}
        return cfg;
    }
    private void loadPlayerData(Player p) {
        UUID id=p.getUniqueId();
        File folder=new File(getDataFolder(),"players");
        if(!folder.exists()&&!folder.mkdirs())getLogger().warning("Could not create player settings directory: "+folder);
        String safeName=p.getName().replaceAll("[^A-Za-z0-9_-]","_");
        File target=new File(folder,id+"_"+safeName+".yml");
        File source=target;
        if(!target.exists()&&folder.exists()){
            File[] matches=folder.listFiles((dir,name)->name.startsWith(id.toString()+"_")&&name.endsWith(".yml"));
            if(matches!=null&&matches.length>0)source=matches[0];
        }
        FileConfiguration cfg=YamlConfiguration.loadConfiguration(source);
        for(Ability a:Ability.values()){
            String enabled="abilities."+a.key()+".enabled",level="abilities."+a.key()+".level";
            if(!cfg.contains(enabled))cfg.set(enabled,getConfig().getBoolean(enabled,false));
            if(!cfg.contains(level))cfg.set(level,getConfig().getInt(level,1));
        }
        if(!cfg.contains("xray.view-radius"))cfg.set("xray.view-radius",getConfig().getInt("xray.view-radius",6));
        if(!cfg.contains("xray.safe-radius"))cfg.set("xray.safe-radius",getConfig().getInt("xray.safe-radius",2));
        if(getConfig().getConfigurationSection("xray.hidden-blocks")!=null){
            for(String material:getConfig().getConfigurationSection("xray.hidden-blocks").getKeys(false)){
                String path="xray.hidden-blocks."+material;
                if(!cfg.contains(path))cfg.set(path,getConfig().getBoolean(path,true));
            }
        }
        cfg.set("xray.view-radius",clamp(cfg.getInt("xray.view-radius",6),1,8));
        cfg.set("xray.safe-radius",clamp(cfg.getInt("xray.safe-radius",2),1,3));
        playerConfigs.put(id,cfg);playerFiles.put(id,target);
        savePlayerData(p);
        // If the player's name changed, migrate the old UUID-matched file to the current filename.
        if(!source.equals(target)&&source.exists()&&!source.delete()){
            getLogger().warning("Settings were copied to "+target.getName()+" but the old file "+source.getName()+" could not be removed.");
        }
    }
    private void savePlayerData(Player p) {
        UUID id=p.getUniqueId();FileConfiguration cfg=playerConfigs.get(id);File file=playerFiles.get(id);
        if(cfg==null||file==null)return;
        try{File parent=file.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();cfg.save(file);}
        catch(java.io.IOException ex){getLogger().severe("Could not save settings for "+p.getName()+": "+ex.getMessage());}
    }
    private String key(Location l){return l.getWorld().getUID()+";"+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();}
    private Location parseKey(String k){try{String[] halves=k.split(";",2);World w=Bukkit.getWorld(UUID.fromString(halves[0]));if(w==null)return null;String[] c=halves[1].split(",");return new Location(w,Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]));}catch(Exception ex){return null;}}
    private int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
