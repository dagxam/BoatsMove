package ru.dagxam.boatsmove.ship;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;

/** Activates a ship by right-clicking the configured control block. */
public final class ShipActivationListener implements Listener {
    private final Material activationBlock;
    private final ShipActivationService activationService;
    private final ShipPassengerManager passengerManager;

    public ShipActivationListener(Material activationBlock, ShipActivationService activationService,
                                  ShipPassengerManager passengerManager) {
        this.activationBlock = activationBlock;
        this.activationService = activationService;
        this.passengerManager = passengerManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != activationBlock) return;

        Player player = event.getPlayer();
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        ShipActivationService.Result result = activationService.activate(player, event.getClickedBlock());
        player.sendMessage(result.success()
                ? org.bukkit.ChatColor.GREEN + result.message()
                : result.message());

        if (result.success() && result.ship() != null) {
            passengerManager.board(result.ship(), player);
            player.sendMessage(org.bukkit.ChatColor.AQUA +
                    "Вы заняли место управления. W/S — движение, A/D — поворот, Shift — выйти.");
        }
    }
}
