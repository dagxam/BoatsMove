package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable storage for active logical ships. */
public final class ShipPersistenceManager {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final File file;

    public ShipPersistenceManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.file = new File(plugin.getDataFolder(), "ships.yml");
    }

    public void saveAll() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        for (ShipModel ship : registry.all()) saveShip(yaml, ship);
        try { yaml.save(file); }
        catch (IOException ex) { plugin.getLogger().severe("Не удалось сохранить ships.yml: " + ex.getMessage()); }
    }

    private void saveShip(YamlConfiguration yaml, ShipModel ship) {
        String p = "ships." + ship.id();
        yaml.set(p + ".owner", ship.ownerId().toString());
        yaml.set(p + ".world", ship.worldId().toString());
        yaml.set(p + ".class", ship.shipClass().name());
        yaml.set(p + ".health", ship.health());
        yaml.set(p + ".max-health", ship.maxHealth());
        yaml.set(p + ".flooding", ship.flooding());
        yaml.set(p + ".yaw", ship.yaw());
        yaml.set(p + ".pitch", ship.pitch());
        ShipRuntimeState runtime = registry.runtime(ship.id());
        Location position = runtime == null ? ship.origin() : runtime.position();
        yaml.set(p + ".position.world", position.getWorld() == null ? ship.worldId().toString() : position.getWorld().getUID().toString());
        yaml.set(p + ".position.x", position.getX());
        yaml.set(p + ".position.y", position.getY());
        yaml.set(p + ".position.z", position.getZ());
        if (runtime != null) {
            yaml.set(p + ".vertical-speed", runtime.verticalSpeed());
            yaml.set(p + ".speed", runtime.speed());
            yaml.set(p + ".runtime-pitch", runtime.pitch());
            yaml.set(p + ".runtime-roll", runtime.roll());
        }
        int i = 0;
        for (ShipBlock block : ship.blocks()) {
            String b = p + ".blocks." + i++;
            yaml.set(b + ".x", block.x());
            yaml.set(b + ".y", block.y());
            yaml.set(b + ".z", block.z());
            yaml.set(b + ".data", block.blockData().getAsString());
            ShipBlockState state = block.state();
            if (state != null) {
                yaml.set(b + ".state-type", state.stateType());
                List<ItemStack> items = new ArrayList<>();
                for (ItemStack item : state.inventory()) items.add(item == null ? null : item.clone());
                yaml.set(b + ".inventory", items);
            }
        }
    }

    public int loadAll() {
        if (!file.isFile()) return 0;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("ships");
        if (section == null) return 0;
        int loaded = 0;
        for (String idText : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(idText);
                String ownerText = section.getString(idText + ".owner");
                UUID owner = UUID.fromString(ownerText);
                UUID worldId = UUID.fromString(section.getString(idText + ".world"));
                World world = plugin.getServer().getWorld(worldId);
                if (world == null) continue;
                ConfigurationSection pos = section.getConfigurationSection(idText + ".position");
                if (pos == null) continue;
                Location origin = new Location(world, pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"));
                List<ShipBlock> blocks = new ArrayList<>();
                ConfigurationSection blockSection = section.getConfigurationSection(idText + ".blocks");
                if (blockSection == null) continue;
                for (String key : blockSection.getKeys(false)) {
                    String data = blockSection.getString(key + ".data");
                    if (data == null) continue;
                    org.bukkit.block.data.BlockData blockData = org.bukkit.Bukkit.createBlockData(data);
                    String stateType = blockSection.getString(key + ".state-type", blockData.getMaterial().name());
                    ItemStack[] inventory = readInventory(blockSection.getList(key + ".inventory"));
                    blocks.add(new ShipBlock(blockSection.getInt(key + ".x"), blockSection.getInt(key + ".y"),
                            blockSection.getInt(key + ".z"), blockData,
                            new ShipBlockState(stateType, null, inventory)));
                }
                if (blocks.isEmpty()) continue;
                ShipModel ship = new ShipModel(id, owner, world, origin, blocks);
                try { ship.shipClass(ShipClass.valueOf(section.getString(idText + ".class", ship.shipClass().name()))); } catch (IllegalArgumentException ignored) {}
                ship.state(ShipState.ACTIVE);
                registry.register(ship);
                Location runtimePosition = new Location(world, pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"));
                registry.position(ship, runtimePosition);
                ship.yaw((float) section.getDouble(idText + ".yaw", origin.getYaw()));
                ship.pitch((float) section.getDouble(idText + ".pitch", origin.getPitch()));
                ship.flooding(section.getDouble(idText + ".flooding", 0));
                loaded++;
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Пропущен повреждённый сохранённый корабль " + idText + ": " + ex.getMessage());
            }
        }
        return loaded;
    }

    private ItemStack[] readInventory(List<?> list) {
        if (list == null) return new ItemStack[0];
        ItemStack[] result = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (value instanceof ItemStack item) result[i] = item.clone();
            else if (value instanceof Map<?, ?> map) {
                try {
                    @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) map;
                    result[i] = ItemStack.deserialize(cast);
                } catch (RuntimeException ignored) { }
            }
        }
        return result;
    }
}
