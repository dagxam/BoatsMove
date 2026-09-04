package ru.dagxam.boatsmove;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dagxam.boatsmove.ship.ShipRegistry;

public final class BoatsMovePlugin extends JavaPlugin implements CommandExecutor {
    private ShipRegistry shipRegistry;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.shipRegistry = new ShipRegistry();

        if (getCommand("boatsmove") != null) {
            getCommand("boatsmove").setExecutor(this);
        }

        getLogger().info("BoatsMove enabled. Ship core initialized.");
        getLogger().info("Target: player-built structures become boat-behavior vehicles without losing their blocks or contents.");
    }

    @Override
    public void onDisable() {
        if (shipRegistry != null) {
            shipRegistry.clearRuntimeState();
        }
        getLogger().info("BoatsMove disabled.");
    }

    public ShipRegistry getShipRegistry() {
        return shipRegistry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("boatsmove.admin")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            int active = shipRegistry == null ? 0 : shipRegistry.size();
            sender.sendMessage(ChatColor.AQUA + "BoatsMove " + ChatColor.WHITE + "core online; active ships: " + active);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "BoatsMove config перезагружен.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Использование: /boatsmove <reload|status>");
        return true;
    }
}
