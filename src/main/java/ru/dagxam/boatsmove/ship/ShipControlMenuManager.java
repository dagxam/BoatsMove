package ru.dagxam.boatsmove.ship;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Control panel and crafting recipe for the ship lectern controller. */
public final class ShipControlMenuManager implements Listener {
    private static final String TITLE = "§8Управление кораблём";
    private static final int SCANNER_SLOT = 2;
    private static final int INFO_SLOT = 4;
    private static final int EXIT_SLOT = 8;
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipActivationService activationService;
    private final ShipPassengerManager passengers;
    private final NamespacedKey controllerKey;
    private final Map<UUID, Location> pendingControls = new HashMap<>();
    private final Map<UUID, UUID> menuShips = new HashMap<>();

    public ShipControlMenuManager(JavaPlugin plugin, ShipRegistry registry, ShipActivationService activationService, ShipPassengerManager passengers) {
        this.plugin = plugin; this.registry = registry; this.activationService = activationService; this.passengers = passengers;
        this.controllerKey = new NamespacedKey(plugin, "ship_controller");
    }

    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "ship_controller"), controllerItem());
        recipe.shape("   ", "SSS", "PPP");
        recipe.setIngredient('S', Material.OAK_SLAB);
        recipe.setIngredient('P', Material.OAK_PLANKS);
        Bukkit.addRecipe(recipe);
    }

    public ItemStack controllerItem() {
        ItemStack item = new ItemStack(Material.LECTERN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6Кафедра управления кораблём");
        meta.setLore(java.util.List.of("§7ПКМ — открыть панель управления", "§8Сканер / запуск / отключение / информация"));
        meta.getPersistentDataContainer().set(controllerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onControllerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.LECTERN) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); event.setCancelled(true);
        Player player = event.getPlayer();
        ShipModel ship = findActiveShipAt(event.getClickedBlock().getLocation());
        if (ship != null) openMenu(player, ship, null);
        else openMenu(player, null, event.getClickedBlock().getLocation());
    }

    public void openMenu(Player player, ShipModel ship, Location control) {
        Inventory inventory = Bukkit.createInventory(null, 9, TITLE);
        boolean active = ship != null;
        inventory.setItem(SCANNER_SLOT, button(active ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                active ? "§cОтключить корабль" : "§aЗапустить сканер",
                active ? "§7Вернуть корабль в обычные блоки" : "§7Просканировать конструкцию и запустить"));
        inventory.setItem(INFO_SLOT, button(Material.BOOK, "§bИнформация о корабле",
                active ? "§7Размер, класс, прочность и затопление" : "§7Проверить контрольный блок"));
        inventory.setItem(EXIT_SLOT, button(Material.BARRIER, "§cВыход", "§7Закрыть меню"));
        UUID id = player.getUniqueId();
        if (ship != null) { menuShips.put(id, ship.id()); pendingControls.remove(id); }
        else { menuShips.remove(id); pendingControls.put(id, control == null ? null : control.clone()); }
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot == EXIT_SLOT) { player.closeInventory(); return; }
        if (slot == INFO_SLOT) {
            ShipModel ship = shipForMenu(player);
            if (ship == null) player.sendMessage("§bСканер готов. §7Нажмите зелёную кнопку запуска."); else sendInfo(player, ship);
            return;
        }
        if (slot != SCANNER_SLOT) return;
        ShipModel ship = shipForMenu(player);
        if (ship != null) {
            ShipActivationService.Result result = activationService.deactivate(ship);
            player.closeInventory();
            player.sendMessage(result.success() ? ChatColor.GREEN + result.message() : result.message());
            if (result.success()) player.sendMessage("§aКорабль восстановлен блоками. Теперь его можно достраивать и изменять.");
            return;
        }
        Location control = pendingControls.get(player.getUniqueId());
        if (control == null || control.getWorld() == null || control.getBlock().getType() != Material.LECTERN) {
            player.closeInventory(); player.sendMessage("§cКафедра управления не найдена на исходном месте."); return;
        }
        ShipActivationService.Result result = activationService.activate(player, control.getBlock());
        player.closeInventory();
        player.sendMessage(result.success() ? ChatColor.GREEN + result.message() : result.message());
        if (!result.success() || result.ship() == null) return;
        result.ship().originYaw(player.getYaw()); result.ship().yaw(player.getYaw());
        if (!passengers.board(result.ship(), player)) player.sendMessage("§eКорабль запущен, но место управления занять не удалось.");
        player.sendMessage("§bУправление: W — вперёд, S — назад, A — влево, D — вправо, Shift — выйти.");
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        UUID id = event.getPlayer().getUniqueId(); pendingControls.remove(id); menuShips.remove(id);
    }

    private ShipModel shipForMenu(Player player) {
        UUID shipId = menuShips.get(player.getUniqueId());
        if (shipId != null) {
            ShipModel ship = registry.get(shipId);
            if (ship != null && ship.state() == ShipState.ACTIVE) return ship;
        }
        Location control = pendingControls.get(player.getUniqueId());
        return control == null ? null : findActiveShipAt(control);
    }

    private ShipModel findActiveShipAt(Location location) {
        World world = location.getWorld(); if (world == null) return null;
        double best = 2.0; ShipModel nearest = null;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(world.getUID())) continue;
            ShipRuntimeState runtime = registry.runtime(ship.id()); if (runtime == null) continue;
            double distance = runtime.position().distanceSquared(location.clone().add(0.5, 0.5, 0.5));
            if (distance <= best) { best = distance; nearest = ship; }
        }
        return nearest;
    }

    private void sendInfo(Player player, ShipModel ship) {
        player.sendMessage("§6§lКорабль");
        player.sendMessage("§7Блоков: §f" + ship.blockCount());
        player.sendMessage("§7Класс: §f" + ship.shipClass().name());
        player.sendMessage("§7Прочность: §f" + String.format("%.0f/%.0f", ship.health(), ship.maxHealth()));
        player.sendMessage("§7Затопление: §f" + String.format("%.1f%%", ship.flooding() * 100.0));
        player.sendMessage("§7Статус: §aактивен");
    }

    private ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); meta.setLore(java.util.List.of(lore)); item.setItemMeta(meta); return item;
    }
}
