package ru.dagxam.boatsmove;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dagxam.boatsmove.ship.ShipActivationListener;
import ru.dagxam.boatsmove.ship.ShipActivationService;
import ru.dagxam.boatsmove.ship.ShipDamageManager;
import ru.dagxam.boatsmove.ship.ShipDisplayManager;
import ru.dagxam.boatsmove.ship.ShipMovementController;
import ru.dagxam.boatsmove.ship.ShipPassengerManager;
import ru.dagxam.boatsmove.ship.ShipPersistenceManager;
import ru.dagxam.boatsmove.ship.ShipProtectionListener;
import ru.dagxam.boatsmove.ship.ShipRegistry;
import ru.dagxam.boatsmove.ship.VirtualBlockInteraction;
import ru.dagxam.boatsmove.ship.VirtualChestManager;

import java.util.HashSet;
import java.util.Set;

public final class BoatsMovePlugin extends JavaPlugin implements CommandExecutor {
    private ShipRegistry shipRegistry;
    private ShipDisplayManager displayManager;
    private ShipPassengerManager passengerManager;
    private ShipMovementController movementController;
    private ShipActivationService activationService;
    private ShipPersistenceManager persistence;
    private VirtualChestManager storage;
    private int autosaveTask = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.shipRegistry = new ShipRegistry();
        this.displayManager = new ShipDisplayManager(this, Math.max(0, getConfig().getInt("movement.interpolation-ticks", 2)));
        this.passengerManager = new ShipPassengerManager(this, shipRegistry);
        this.movementController = new ShipMovementController(this, shipRegistry, displayManager, passengerManager,
                getConfig().getDouble("movement.max-speed", 0.65), getConfig().getDouble("movement.acceleration", 0.035),
                getConfig().getDouble("movement.reverse-speed", 0.28), getConfig().getDouble("movement.turn-speed", 2.5),
                getConfig().getDouble("movement.drag", 0.90), getConfig().getBoolean("movement.water-only", true));
        this.activationService = createActivationService();
        this.persistence = new ShipPersistenceManager(this, shipRegistry);

        Material activationBlock = readMaterial("ships.activation-block", Material.OAK_BUTTON);
        getServer().getPluginManager().registerEvents(new ShipActivationListener(activationBlock, activationService, passengerManager), this);
        this.storage = new VirtualChestManager(shipRegistry);
        getServer().getPluginManager().registerEvents(storage, this);
        VirtualBlockInteraction interaction = new VirtualBlockInteraction(shipRegistry, storage);
        interaction.damageManager(new ShipDamageManager(shipRegistry, activationService));
        getServer().getPluginManager().registerEvents(interaction, this);
        getServer().getPluginManager().registerEvents(new ShipProtectionListener(shipRegistry), this);

        int loaded = persistence.loadAll();
        for (var ship : shipRegistry.all()) {
            try { displayManager.spawn(ship); }
            catch (RuntimeException ex) { ship.state(ru.dagxam.boatsmove.ship.ShipState.FAILED); getLogger().warning("Не удалось восстановить корабль " + ship.id() + ": " + ex.getMessage()); }
        }
        startAutosave();
        movementController.start();

        if (getCommand("boatsmove") != null) getCommand("boatsmove").setExecutor(this);
        getLogger().info("BoatsMove enabled. Restored active ships: " + loaded);
        getLogger().info("Activation block: " + activationBlock);
    }

    @Override
    public void onDisable() {
        if (autosaveTask != -1) getServer().getScheduler().cancelTask(autosaveTask);
        if (storage != null) storage.flushAll();
        if (persistence != null) persistence.saveAll();
        if (movementController != null) movementController.stop();
        if (passengerManager != null) passengerManager.clearAll();
        if (displayManager != null) displayManager.removeAll();
        if (shipRegistry != null) shipRegistry.clearRuntimeState();
        getLogger().info("BoatsMove disabled.");
    }

    private void startAutosave() {
        long seconds = Math.max(5, getConfig().getLong("storage.autosave-seconds", 30));
        autosaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (storage != null) storage.flushAll();
            if (persistence != null) persistence.saveAll();
        }, seconds * 20L, seconds * 20L).getTaskId();
    }

    private ShipActivationService createActivationService() {
        Set<Material> forbidden = new HashSet<>();
        for (String name : getConfig().getStringList("ships.forbidden-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null) forbidden.add(material); else getLogger().warning("Unknown forbidden block in config: " + name);
        }
        return new ShipActivationService(shipRegistry, displayManager,
                Math.max(1, getConfig().getInt("ships.min-blocks", 2)),
                Math.max(1, getConfig().getInt("ships.max-blocks", 5000)), forbidden,
                Math.max(1, getConfig().getInt("limits.max-active-ships", 50)));
    }

    private Material readMaterial(String path, Material fallback) {
        String value = getConfig().getString(path);
        Material material = value == null ? null : Material.matchMaterial(value);
        if (material == null) { getLogger().warning("Invalid material at " + path + ": " + value + "; using " + fallback); return fallback; }
        return material;
    }

    public ShipRegistry getShipRegistry() { return shipRegistry; }
    public ShipDisplayManager getDisplayManager() { return displayManager; }
    public ShipPassengerManager getPassengerManager() { return passengerManager; }
    public ShipMovementController getMovementController() { return movementController; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("boatsmove.admin")) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            int active = shipRegistry == null ? 0 : shipRegistry.size();
            sender.sendMessage(ChatColor.AQUA + "BoatsMove " + ChatColor.WHITE + "online; active ships: " + active); return true;
        }
        if (args[0].equalsIgnoreCase("reload")) { reloadConfig(); sender.sendMessage(ChatColor.GREEN + "BoatsMove config перезагружен."); return true; }
        if (args[0].equalsIgnoreCase("save")) { if (storage != null) storage.flushAll(); if (persistence != null) persistence.saveAll(); sender.sendMessage(ChatColor.GREEN + "Корабли сохранены."); return true; }
        if (args[0].equalsIgnoreCase("deactivate")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Команда требует игрока."); return true; }
            var nearest = nearestShip(player);
            if (nearest == null) { player.sendMessage(ChatColor.RED + "Рядом нет активного корабля."); return true; }
            var result = activationService.deactivate(nearest);
            player.sendMessage(result.success() ? ChatColor.GREEN + result.message() : result.message());
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Использование: /boatsmove <reload|status|save|deactivate>"); return true;
    }

    private ru.dagxam.boatsmove.ship.ShipModel nearestShip(Player player) {
        ru.dagxam.boatsmove.ship.ShipModel nearest = null;
        double best = 16.0 * 16.0;
        for (var ship : shipRegistry.all()) {
            if (ship.state() != ru.dagxam.boatsmove.ship.ShipState.ACTIVE || !ship.worldId().equals(player.getWorld().getUID())) continue;
            double d = shipRegistry.position(ship).distanceSquared(player.getLocation());
            if (d < best) { best = d; nearest = ship; }
        }
        return nearest;
    }
}
