package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;

/** Runtime-only state that belongs to the logical ship, not to display entities. */
public final class ShipRuntimeState {
    private Location position;
    private double speed;
    private double verticalSpeed;

    public ShipRuntimeState(Location position) {
        this.position = position.clone();
    }

    public Location position() {
        return position.clone();
    }

    public void position(Location position) {
        this.position = position.clone();
    }

    public double speed() {
        return speed;
    }

    public void speed(double speed) {
        this.speed = speed;
    }

    public double verticalSpeed() {
        return verticalSpeed;
    }

    public void verticalSpeed(double verticalSpeed) {
        this.verticalSpeed = verticalSpeed;
    }
}
