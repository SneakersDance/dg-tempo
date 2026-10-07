package com.tupai.dgtempo;

/**
 * Pure mapping from phone tilt + movement to a 0..1 output drive per device (unit-tested).
 *
 * tiltFactor: 0 at level (within the dead band), 1 at tiltMax degrees or more.
 * moveFactor: 0 at rest, 1 at the configured "full movement" (linear acceleration or rotation rate).
 * A relation turns each factor into a drive: MORE (factor), LESS (1 - factor) or IGNORE (1).
 * The device drive is the product, so a factor set to "no output" (level with MORE tilt, moving with
 * LESS movement) silences the device regardless of the other.
 */
public final class MotionMap {
    public static final int IGNORE = 0, MORE = 1, LESS = 2;
    private MotionMap() {}

    public static double tiltFactor(double tiltDeg, double deadDeg, double maxDeg) {
        if (maxDeg <= deadDeg) return tiltDeg > deadDeg ? 1 : 0;
        return clamp((tiltDeg - deadDeg) / (maxDeg - deadDeg));
    }

    /** Movement 0..1 from linear acceleration (m/s^2) and rotation rate (rad/s), each against its "full" value. */
    public static double moveFactor(double accel, double accelFull, double gyro, double gyroFull) {
        return clamp(Math.max(accel / Math.max(0.1, accelFull), gyro / Math.max(0.1, gyroFull)));
    }

    public static double relate(double factor, int relation) {
        switch (relation) {
            case MORE: return clamp(factor);
            case LESS: return clamp(1 - factor);
            default: return 1;
        }
    }

    /** Combined 0..1 drive for a device. */
    public static double drive(double tiltFactor, int tiltRel, double moveFactor, int moveRel) {
        if (tiltRel == IGNORE && moveRel == IGNORE) return 0;     // nothing selected: no output
        return relate(tiltFactor, tiltRel) * relate(moveFactor, moveRel);
    }

    /** Strength for a channel from the drive: below 5% = off, else min..max. */
    public static int strength(double drive, int min, int max) {
        if (drive < 0.05) return 0;
        return (int) Math.round(min + drive * (max - min));
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
