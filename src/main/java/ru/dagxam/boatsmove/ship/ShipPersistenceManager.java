package ru.dagxam.boatsmove.ship;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable storage for active logical ships. */
public final class ShipPersistenceManager {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final File file;
    private final File tempFile;

    public ShipPersistenceManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.file = new File(plugin.getDataFolder(), "ships.yml");
        this.tempFile = new File(plugin.getDataFolder(), "ships.yml.tmp");
    }

    public void saveAll() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().severe("Не удалось создать папку данных BoatsMove.");
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE) continue;
            saveShip(yaml, ship);
        }

        try {
            yaml.save(tempFile);
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().severe("Не удалось атомарно сохранить ships.yml: " + ex.getMessage());
            }
        }
    }

    private void saveShip(YamlConfiguration yaml, ShipModel ship) {
        String p = "ships." + ship.id();
        yaml.set(p + ".owner", ship.ownerId().toString());
        yaml.set(p + ".world", ship.worldId().toString());
        yaml.set(p + ".class", ship.shipClass().name());
        yaml.set(p + ".health", ship.health());
        yaml.set(p + ".max-health", ship.maxHealth());
        yaml.set(p + ".flooding", ship.flooding());
        yaml.set(p + ".origin-yaw", ship.origin().getYaw());
        yaml.set(p + ".yaw", ship.yaw());
        yaml.set(p + ".pitch", ship.pitch());

        ShipRuntimeState runtime = registry.runtime(ship.id());
        Location position = runtime == null ? ship.origin() : runtime.position();
        World positionWorld = position.getWorld();
        yaml.set(p + ".position.world", positionWorld == null ? ship.worldId().toString() : positionWorld.getUID().toString());
        yaml.set(p + ".position.x", position.getX());
        yaml.set(p + ".position.y", position.getY());
        yaml.set(p + ".position.z", position.getZ());

        if (runtime != null) {
            yaml.set(p + ".speed", runtime.speed());
            yaml.set(p + ".vertical-speed", runtime.verticalSpeed());
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
                if (registry.get(id) != null) {
                    plugin.getLogger().warning("Пропущен дубликат сохранённого корабля " + idText + ".");
                    continue;
                }

                String ownerText = section.getString(idText + ".owner");
                String worldText = section.getString(idText + ".world");
                if (ownerText == null || worldText == null) throw new IllegalArgumentException("отсутствует owner/world");

                UUID owner = UUID.fromString(ownerText);
                UUID worldId = UUID.fromString(worldText);
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    plugin.getLogger().warning("Мир корабля " + idText + " не найден; корабль пропущен.");
                    continue;
                }

                ConfigurationSection pos = section.getConfigurationSection(idText + ".position");
                if (pos == null) throw new IllegalArgumentException("отсутствует position");

                String positionWorldText = pos.getString("world", worldId.toString());
                UUID positionWorldId = UUID.fromString(positionWorldText);
                World positionWorld = Bukkit.getWorld(positionWorldId);
                if (positionWorld == null) {
                    plugin.getLogger().warning("Мир runtime-позиции корабля " + idText + " не найден; используется исходный мир.");
                    positionWorld = world;
                }

                float originYaw = (float) section.getDouble(idText + ".origin-yaw", pos.getDouble("yaw", 0.0));
                Location origin = new Location(world, pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"), originYaw, 0.0f);
                List<ShipBlock> blocks = loadBlocks(section, idText);
                if (blocks.isEmpty()) throw new IllegalArgumentException("нет блоков");

                ShipModel ship = new ShipModel(id, owner, world, origin, blocks);
                String className = section.getString(idText + ".class");
                if (className != null) {
                    try {
                        ship.shipClass(ShipClass.valueOf(className));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Неизвестный класс корабля " + className + " для " + idText + "; выбран класс по размеру.");
                    }
                }

                ship.maxHealth(section.getDouble(idText + ".max-health", ship.maxHealth()));
                ship.health(section.getDouble(idText + ".health", ship.maxHealth()));
                ship.flooding(section.getDouble(idText + ".flooding", 0.0));
                ship.originYaw(originYaw);
                ship.yaw((float) section.getDouble(idText + ".yaw", originYaw));
                ship.pitch((float) section.getDouble(idText + ".pitch", origin.getPitch()));
                ship.state(ShipState.ACTIVE);

                registry.register(ship);
                Location runtimePosition = new Location(positionWorld,
                        pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z"));
                ShipRuntimeState runtime = registry.runtime(id);
                if (runtime == null) throw new IllegalStateException("runtime state не создан");
                runtime.position(runtimePosition);
                runtime.speed(safeFinite(section.getDouble(idText + ".speed", 0.0)));
                runtime.verticalSpeed(safeFinite(section.getDouble(idText + ".vertical-speed", 0.0)));
                runtime.pitch((float) safeFinite(section.getDouble(idText + ".runtime-pitch", 0.0)));
                runtime.roll((float) safeFinite(section.getDouble(idText + ".runtime-roll", 0.0)));

                loaded++;
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Пропущен повреждённый сохранённый корабль " + idText + ": " + ex.getMessage());
            }
        }

        if (loaded > 0) plugin.getLogger().info("Загружено сохранённых кораблей: " + loaded);
        return loaded;
    }

    private List<ShipBlock> loadBlocks(ConfigurationSection section, String idText) {
        List<ShipBlock> blocks = new ArrayList<>();
        ConfigurationSection blockSection = section.getConfigurationSection(idText + ".blocks");
        if (blockSection == null) return blocks;

        for (String key : blockSection.getKeys(false)) {
            String data = blockSection.getString(key + ".data");
            if (data == null) continue;
            try {
                org.bukkit.block.data.BlockData blockData = Bukkit.createBlockData(data);
                String stateType = blockSection.getString(key + ".state-type", blockData.getMaterial().name());
                ItemStack[] inventory = readInventory(blockSection.getList(key + ".inventory"));
                blocks.add(new ShipBlock(
                        blockSection.getInt(key + ".x"),
                        blockSection.getInt(key + ".y"),
                        blockSection.getInt(key + ".z"),
                        blockData,
                        new ShipBlockState(stateType, null, inventory)
                ));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Пропущен повреждённый блок " + idText + "/" + key + ": " + ex.getMessage());
            }
        }
        return blocks;
    }

    private double safeFinite(double value) { return Double.isFinite(value) ? value : 0.0; }

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
                } catch (RuntimeException ignored) { result[i] = null; }
            }
        }
        return result;
    }
}
