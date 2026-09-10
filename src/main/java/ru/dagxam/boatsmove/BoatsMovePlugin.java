package ru.dagxam.boatsmove;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dagxam.boatsmove.ship.*;

import java.util.HashSet;
import java.util.Set;

public final class BoatsMovePlugin extends JavaPlugin implements CommandExecutor {
    private ShipRegistry shipRegistry; private ShipDisplayManager displayManager; private ShipPassengerManager passengerManager;
    private ShipMovementController movementController; private ShipActivationService activationService; private ShipPersistenceManager persistence;
    private VirtualChestManager storage; private ShipFloodingManager floodingManager; private ShipFloodVisualManager floodVisualManager;
    private ShipProjectileDamageManager projectileDamageManager; private ShipCannonManager cannonManager; private ShipSystemsManager systemsManager;
    private ShipControlMenuManager controlMenu;
    private int autosaveTask=-1, floodingTask=-1, projectileTask=-1;

    @Override public void onEnable() {
        saveDefaultConfig(); shipRegistry=new ShipRegistry();
        displayManager=new ShipDisplayManager(this,Math.max(0,getConfig().getInt("movement.interpolation-ticks",2)));
        double turnSpeed=getConfig().getDouble("movement.turn-speed",2.5);
        passengerManager=new ShipPassengerManager(this,shipRegistry,turnSpeed);
        getServer().getPluginManager().registerEvents(passengerManager,this);
        movementController=new ShipMovementController(this,shipRegistry,displayManager,passengerManager,
                getConfig().getDouble("movement.max-speed",.65),getConfig().getDouble("movement.acceleration",.035),
                getConfig().getDouble("movement.reverse-speed",.28),turnSpeed,getConfig().getDouble("movement.drag",.90),getConfig().getBoolean("movement.water-only",true));
        activationService=createActivationService(); persistence=new ShipPersistenceManager(this,shipRegistry);
        controlMenu=new ShipControlMenuManager(this,shipRegistry,activationService,passengerManager);
        getServer().getPluginManager().registerEvents(controlMenu,this); controlMenu.registerRecipe();
        storage=new VirtualChestManager(shipRegistry); getServer().getPluginManager().registerEvents(storage,this);
        VirtualBlockInteraction interaction=new VirtualBlockInteraction(shipRegistry,storage,passengerManager,controlMenu);
        ShipDamageManager damageManager=new ShipDamageManager(shipRegistry,activationService,displayManager); interaction.damageManager(damageManager);
        cannonManager=new ShipCannonManager(this,shipRegistry); interaction.cannonManager(cannonManager);
        getServer().getPluginManager().registerEvents(interaction,this);
        getServer().getPluginManager().registerEvents(new ShipProtectionListener(shipRegistry),this);
        floodingManager=new ShipFloodingManager(shipRegistry,displayManager); movementController.floodingManager(floodingManager);
        systemsManager=new ShipSystemsManager(ShipSystemsManager.parseMaterials(getConfig().getStringList("systems.engine-blocks")),ShipSystemsManager.parseMaterials(getConfig().getStringList("systems.steering-blocks"))); movementController.systemsManager(systemsManager);
        getServer().getPluginManager().registerEvents(new ShipRepairListener(shipRegistry,displayManager,4.0),this);
        floodVisualManager=new ShipFloodVisualManager(this,shipRegistry,floodingManager);
        projectileDamageManager=new ShipProjectileDamageManager(shipRegistry,damageManager,cannonManager);
        int loaded=persistence.loadAll();
        for(var ship:shipRegistry.all()) try{displayManager.spawn(ship);}catch(RuntimeException ex){ship.state(ShipState.FAILED);getLogger().warning("Не удалось восстановить корабль "+ship.id()+": "+ex.getMessage());}
        startAutosave(); startFloodingSimulation(); startProjectileSimulation(); movementController.start();
        if(getCommand("boatsmove")!=null)getCommand("boatsmove").setExecutor(this);
        getLogger().info("BoatsMove enabled. Restored active ships: "+loaded); getLogger().info("Control block: LECTERN");
    }
    @Override public void onDisable(){if(autosaveTask!=-1)getServer().getScheduler().cancelTask(autosaveTask);if(floodingTask!=-1)getServer().getScheduler().cancelTask(floodingTask);if(projectileTask!=-1)getServer().getScheduler().cancelTask(projectileTask);if(storage!=null)storage.flushAll();if(persistence!=null)persistence.saveAll();if(movementController!=null)movementController.stop();if(passengerManager!=null)passengerManager.clearAll();if(floodVisualManager!=null)floodVisualManager.clearAll();if(displayManager!=null)displayManager.removeAll();if(shipRegistry!=null)shipRegistry.clearRuntimeState();}
    private void startFloodingSimulation(){floodingTask=getServer().getScheduler().runTaskTimer(this,()->{if(floodingManager!=null)floodingManager.tick();if(floodVisualManager!=null)floodVisualManager.tick();},1L,1L).getTaskId();}
    private void startProjectileSimulation(){projectileTask=getServer().getScheduler().runTaskTimer(this,()->{if(projectileDamageManager!=null)projectileDamageManager.tick();},1L,1L).getTaskId();}
    private void startAutosave(){long seconds=Math.max(5,getConfig().getLong("storage.autosave-seconds",30));autosaveTask=getServer().getScheduler().runTaskTimer(this,()->{if(storage!=null)storage.flushAll();if(persistence!=null)persistence.saveAll();},seconds*20L,seconds*20L).getTaskId();}
    private ShipActivationService createActivationService(){Set<Material> forbidden=new HashSet<>();for(String name:getConfig().getStringList("ships.forbidden-blocks")){Material material=Material.matchMaterial(name);if(material!=null)forbidden.add(material);else getLogger().warning("Unknown forbidden block in config: "+name);}return new ShipActivationService(shipRegistry,displayManager,passengerManager,Math.max(1,getConfig().getInt("ships.min-blocks",2)),Math.max(1,getConfig().getInt("ships.max-blocks",5000)),forbidden,Math.max(1,getConfig().getInt("limits.max-active-ships",50)));}
    private Material readMaterial(String path,Material fallback){String value=getConfig().getString(path);Material material=value==null?null:Material.matchMaterial(value);if(material==null){getLogger().warning("Invalid material at "+path+": "+value+"; using "+fallback);return fallback;}return material;}
    public ShipRegistry getShipRegistry(){return shipRegistry;} public ShipDisplayManager getDisplayManager(){return displayManager;} public ShipPassengerManager getPassengerManager(){return passengerManager;} public ShipMovementController getMovementController(){return movementController;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!sender.hasPermission("boatsmove.admin")){sender.sendMessage(ChatColor.RED+"Нет прав.");return true;}if(args.length==0||args[0].equalsIgnoreCase("status")){sender.sendMessage(ChatColor.AQUA+"BoatsMove "+ChatColor.WHITE+"online; active ships: "+(shipRegistry==null?0:shipRegistry.size()));return true;}if(args[0].equalsIgnoreCase("reload")){reloadConfig();sender.sendMessage(ChatColor.GREEN+"BoatsMove config перезагружен.");return true;}if(args[0].equalsIgnoreCase("save")){if(storage!=null)storage.flushAll();if(persistence!=null)persistence.saveAll();sender.sendMessage(ChatColor.GREEN+"Корабли сохранены.");return true;}if(args[0].equalsIgnoreCase("deactivate")){if(!(sender instanceof Player player)){sender.sendMessage(ChatColor.RED+"Команда требует игрока.");return true;}var nearest=nearestShip(player);if(nearest==null){player.sendMessage(ChatColor.RED+"Рядом нет активного корабля.");return true;}var result=activationService.deactivate(nearest);player.sendMessage(result.success()?ChatColor.GREEN+result.message():result.message());return true;}sender.sendMessage(ChatColor.YELLOW+"Использование: /boatsmove <reload|status|save|deactivate>");return true;}
    private ShipModel nearestShip(Player player){ShipModel nearest=null;double best=256.0;for(var ship:shipRegistry.all()){if(ship.state()!=ShipState.ACTIVE||!ship.worldId().equals(player.getWorld().getUID()))continue;double d=shipRegistry.position(ship).distanceSquared(player.getLocation());if(d<best){best=d;nearest=ship;}}return nearest;}
}
