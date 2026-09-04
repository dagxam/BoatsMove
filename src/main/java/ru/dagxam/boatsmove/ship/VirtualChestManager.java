package ru.dagxam.boatsmove.ship;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Provides a real Bukkit inventory GUI backed by a virtual ship chest. */
public final class VirtualChestManager implements Listener {
    private static final String TITLE_PREFIX = "Корабельный сундук ";
    private final ShipRegistry registry;
    private final Map<UUID, OpenChest> open = new HashMap<>();

    public VirtualChestManager(ShipRegistry registry) {
        this.registry = registry;
    }

    public boolean open(Player player, VirtualBlockInteraction.VirtualHit hit) {
        Material type = hit.block().blockData().getMaterial();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return false;

        ShipBlockState state = hit.block().state();
        if (state == null || !state.hasInventory()) return false;

        Inventory inventory = Bukkit.createInventory(new VirtualChestHolder(hit.shipId(), hit.block().x(), hit.block().y(), hit.block().z()), 27,
                TITLE_PREFIX + hit.shipId().toString().substring(0, 8));
        ItemStack[] source = state.inventory();
        for (int i = 0; i < Math.min(source.length, inventory.getSize()); i++) {
            ItemStack item = source[i];
            if (item != null) inventory.setItem(i, item.clone());
        }

        open.put(player.getUniqueId(), new OpenChest(hit.shipId(), hit.block().x(), hit.block().y(), hit.block().z(), inventory));
        player.openInventory(inventory);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenChest chest = open.get(player.getUniqueId());
        if (chest == null || event.getView().getTopInventory() != chest.inventory()) return;
        if (!isStillActive(chest)) {
            event.setCancelled(true);
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenChest chest = open.get(player.getUniqueId());
        if (chest == null || event.getView().getTopInventory() != chest.inventory()) return;
        if (!isStillActive(chest)) {
            event.setCancelled(true);
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        OpenChest chest = open.remove(player.getUniqueId());
        if (chest == null || event.getInventory() != chest.inventory()) return;
        save(chest);
    }

    private boolean isStillActive(OpenChest chest) {
        ShipModel ship = registry.get(chest.shipId());
        return ship != null && ship.state() == ShipState.ACTIVE;
    }

    private void save(OpenChest chest) {
        ShipModel ship = registry.get(chest.shipId());
        if (ship == null || ship.state() != ShipState.ACTIVE) return;
        for (ShipBlock block : ship.blocks()) {
            if (block.x() != chest.x() || block.y() != chest.y() || block.z() != chest.z()) continue;
            ShipBlockState old = block.state();
            if (old == null) return;
            ItemStack[] contents = chest.inventory().getContents();
            block.replaceState(new ShipBlockState(old.stateType(), old.blockState(), contents));
            return;
        }
    }

    private record OpenChest(UUID shipId, int x, int y, int z, Inventory inventory) {}

    private static final class VirtualChestHolder implements InventoryHolder {
        private final UUID shipId;
        private final int x, y, z;

        private VirtualChestHolder(UUID shipId, int x, int y, int z) {
            this.shipId = shipId;
            this.x = x; this.y = y; this.z = z;
        }

        @Override public Inventory getInventory() { return null; }
        @SuppressWarnings("unused") public UUID shipId() { return shipId; }
        @SuppressWarnings("unused") public int x() { return x; }
        @SuppressWarnings("unused") public int y() { return y; }
        @SuppressWarnings("unused") public int z() { return z; }
    }
}
