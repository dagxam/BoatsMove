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

/** Handles simple ship-mounted cannons using virtual dispenser blocks as weapon mounts. */
public final class ShipCannonManager {
    private final ShipRegistry registry;
    private final NamespacedKey cannonballKey;
    private final double projectileSpeed;
    private final long cooldownMillis;

    public ShipCannonManager(JavaPlugin plugin, ShipRegistry registry) {
        this.registry = registry;
        this.cannonballKey = new NamespacedKey(plugin, "cannonball");
        this.projectileSpeed = 2.8;
        this.cooldownMillis = 900L;
    }

    public boolean handle(Player player, VirtualBlockInteraction.VirtualHit hit) {
        if (hit == null || player == null) return false;
        if (hit.block().blockData().getMaterial() != Material.DISPENSER) return false;

        ShipBlockState state = hit.block().state();
        if (state == null || state.inventory().length == 0) {
            player.sendActionBar("§cОрудие не заряжено");
            return true;
        }

        long now = System.currentTimeMillis();
        Long last = player.getPersistentDataContainer().get(cooldownKey(player), PersistentDataType.LONG);
        if (last != null && now - last < cooldownMillis) {
            player.sendActionBar("§7Орудие перезаряжается: §f" + ((cooldownMillis - (now - last) + 99) / 100) / 10.0 + "с");
            return true;
        }

        int slot = findAmmo(state.inventory());
        if (slot < 0) {
            player.sendActionBar("§cЗарядите орудие порохом/боеприпасом");
            return true;
        }

        ItemStack[] contents = state.inventory();
        ItemStack ammo = contents[slot];
        if (ammo.getAmount() <= 0) return true;
        ammo.setAmount(ammo.getAmount() - 1);
        if (ammo.getAmount() <= 0) contents[slot] = null;
        hit.block().replaceState(new ShipBlockState(state.stateType(), state.blockState(), contents));

        Location origin = registry.position(hit.ship());
        ShipRuntimeState runtime = registry.runtime(hit.ship().id());
        Vector local = new Vector(hit.block().x() + 0.5, hit.block().y() + 0.5, hit.block().z() + 0.5);
        Location muzzle = origin.clone().add(transform(local, hit.ship(), runtime));
        Vector direction = player.getEyeLocation().getDirection().normalize();
        muzzle.add(direction.clone().multiply(0.75));

        Snowball projectile = player.getWorld().spawn(muzzle, Snowball.class);
        projectile.setShooter(player);
        projectile.setVelocity(direction.multiply(projectileSpeed));
        projectile.getPersistentDataContainer().set(cannonballKey, PersistentDataType.BYTE, (byte) 1);
        player.getPersistentDataContainer().set(cooldownKey(player), PersistentDataType.LONG, now);
        player.sendActionBar("§6Пушка: §fогонь! §7Боеприпасов: " + countAmmo(contents));
        return true;
    }

    public boolean isCannonball(org.bukkit.entity.Entity entity) {
        return entity.getPersistentDataContainer().has(cannonballKey, PersistentDataType.BYTE);
    }

    private NamespacedKey cooldownKey(Player player) {
        return new NamespacedKey(cannonballKey.getNamespace(), "cannon_cooldown_" + player.getUniqueId().toString().replace('-', '_'));
    }

    private int findAmmo(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir() && item.getAmount() > 0 && isAmmo(item.getType())) return i;
        }
        return -1;
    }

    private boolean isAmmo(Material material) {
        return material == Material.IRON_NUGGET || material == Material.IRON_INGOT || material == Material.FIRE_CHARGE
                || material == Material.TNT || material == Material.COBBLESTONE;
    }

    private int countAmmo(ItemStack[] contents) {
        int total = 0;
        for (ItemStack item : contents) if (item != null && isAmmo(item.getType())) total += item.getAmount();
        return total;
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
}
