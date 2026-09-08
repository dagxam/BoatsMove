package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Handles ship-mounted cannons and keeps cooldown state per physical cannon. */
public final class ShipCannonManager {
    private final ShipRegistry registry;
    private final NamespacedKey cannonballKey;
    private final Material cannonMaterial;
    private final double projectileSpeed;
    private final long cooldownMillis;
    private final double damage;
    private final double explosionRadius;
    private final int maxAffectedBlocks;
    private final Material[] ammo;
    private final Map<CannonKey, Long> cooldowns = new HashMap<>();

    public ShipCannonManager(JavaPlugin plugin, ShipRegistry registry) {
        this.registry = registry;
        this.cannonballKey = new NamespacedKey(plugin, "cannonball");
        this.cannonMaterial = readMaterial(plugin, "cannons.material", Material.DISPENSER);
        this.projectileSpeed = Math.max(0.5, plugin.getConfig().getDouble("cannons.projectile-speed", 2.8));
        this.cooldownMillis = Math.max(100L, plugin.getConfig().getLong("cannons.cooldown-millis", 900L));
        this.damage = Math.max(0.1, plugin.getConfig().getDouble("cannons.damage", 18.0));
        this.explosionRadius = Math.max(0.0, plugin.getConfig().getDouble("cannons.explosion-radius", 1.25));
        this.maxAffectedBlocks = Math.max(1, plugin.getConfig().getInt("cannons.max-affected-blocks", 4));
        this.ammo = plugin.getConfig().getStringList("cannons.ammo").stream()
                .map(Material::matchMaterial)
                .filter(Objects::nonNull)
                .toArray(Material[]::new);
    }

    public boolean handle(Player player, VirtualBlockInteraction.VirtualHit hit) {
        if (player == null || hit == null || hit.ship().state() != ShipState.ACTIVE) return false;
        if (hit.block().blockData().getMaterial() != cannonMaterial) return false;

        ShipBlockState state = hit.block().state();
        ItemStack[] contents = state == null ? new ItemStack[0] : state.inventory();
        int slot = findAmmo(contents);
        if (slot < 0) {
            player.sendActionBar("§cПушка не заряжена");
            return true;
        }

        CannonKey key = new CannonKey(hit.ship().id(), hit.block().x(), hit.block().y(), hit.block().z());
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < cooldownMillis) {
                player.sendActionBar(String.format("§7Орудие перезаряжается: §f%.1fс", (cooldownMillis - elapsed) / 1000.0));
                return true;
            }
        }

        ItemStack ammoItem = contents[slot];
        ammoItem.setAmount(ammoItem.getAmount() - 1);
        if (ammoItem.getAmount() <= 0) contents[slot] = null;
        hit.block().replaceState(new ShipBlockState(
                state == null ? "" : state.stateType(),
                state == null ? null : state.blockState(),
                contents));

        Location origin = registry.position(hit.ship());
        ShipRuntimeState runtime = registry.runtime(hit.ship().id());
        Vector local = new Vector(hit.block().x() + 0.5, hit.block().y() + 0.5, hit.block().z() + 0.5);
        Location muzzle = origin.clone().add(transform(local, hit.ship(), runtime));

        Vector direction = player.getEyeLocation().getDirection();
        if (direction.lengthSquared() < 1.0E-8) direction = new Vector(0, 0, 1);
        direction.normalize();

        // Start the projectile outside the cannon block so a valid shot cannot immediately collide with its own mount.
        muzzle.add(direction.clone().multiply(0.85));
        Snowball projectile = player.getWorld().spawn(muzzle, Snowball.class);
        projectile.setShooter(player);
        projectile.setVelocity(direction.multiply(projectileSpeed));
        projectile.getPersistentDataContainer().set(cannonballKey, PersistentDataType.BYTE, (byte) 1);
        cooldowns.put(key, now);

        player.sendActionBar("§6Пушка: §fогонь! §7Боеприпасов: " + countAmmo(contents));
        return true;
    }

    public boolean isCannonball(org.bukkit.entity.Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(cannonballKey, PersistentDataType.BYTE);
    }

    public double damage() { return damage; }
    public double explosionRadius() { return explosionRadius; }
    public int maxAffectedBlocks() { return maxAffectedBlocks; }

    public void cleanup() {
        cooldowns.keySet().removeIf(key -> registry.get(key.shipId()) == null);
    }

    private int findAmmo(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir() && item.getAmount() > 0 && isAmmo(item.getType())) return i;
        }
        return -1;
    }

    private boolean isAmmo(Material material) {
        for (Material allowed : ammo) if (allowed == material) return true;
        return false;
    }

    private int countAmmo(ItemStack[] contents) {
        int total = 0;
        for (ItemStack item : contents) if (item != null && isAmmo(item.getType())) total += item.getAmount();
        return total;
    }

    private Material readMaterial(JavaPlugin plugin, String path, Material fallback) {
        String value = plugin.getConfig().getString(path);
        Material material = value == null ? null : Material.matchMaterial(value);
        if (material == null) {
            plugin.getLogger().warning("Invalid cannon material at " + path + ": " + value + "; using " + fallback);
            return fallback;
        }
        return material;
    }

    private Vector transform(Vector local, ShipModel ship, ShipRuntimeState runtime) {
        double yaw = Math.toRadians(registry.position(ship).getYaw() - ship.origin().getYaw());
        double pitch = Math.toRadians(runtime == null ? ship.pitch() : runtime.pitch());
        double roll = Math.toRadians(runtime == null ? 0.0f : runtime.roll());
        Vector v = rotateZ(local, roll);
        v = rotateX(v, pitch);
        return rotateY(v, yaw);
    }

    private Vector rotateY(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getZ() * s, v.getY(), v.getX() * s + v.getZ() * c);
    }

    private Vector rotateX(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX(), v.getY() * c - v.getZ() * s, v.getY() * s + v.getZ() * c);
    }

    private Vector rotateZ(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getY() * s, v.getX() * s + v.getY() * c, v.getZ());
    }

    private record CannonKey(UUID shipId, int x, int y, int z) {}
}
