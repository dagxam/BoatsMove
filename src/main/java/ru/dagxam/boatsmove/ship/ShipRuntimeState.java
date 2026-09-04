package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;

/** Runtime-only state that belongs to the logical ship, not to display entities. */
public final class ShipRuntimeState {
    private Location position;
    private double speed;
    private double verticalSpeed;
    private float pitch;
    private float roll;

    public ShipRuntimeState(Location position) {
        this.position = position.clone();
    }

    public Location position() { return position.clone(); }
    public void position(Location position) { this.position = position.clone(); }
    public double speed() { return speed; }
    public void speed(double speed) { this.speed = speed; }
    public double verticalSpeed() { return verticalSpeed; }
    public void verticalSpeed(double verticalSpeed) { this.verticalSpeed = verticalSpeed; }
    public float pitch() { return pitch; }
    public void pitch(float pitch) { this.pitch = pitch; }
    public float roll() { return roll; }
    public void roll(float roll) { this.roll = roll; }
}
