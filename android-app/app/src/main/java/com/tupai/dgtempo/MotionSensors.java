package com.tupai.dgtempo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Tilt + movement from the phone's accelerometer and gyroscope (~50 Hz).
 * tiltDeg: angle between the phone's screen normal and vertical: 0 = lying flat, 90 = upright.
 * accel:   magnitude of linear acceleration (gravity removed), smoothed with a short peak hold.
 * gyro:    magnitude of rotation rate (rad/s), smoothed the same way.
 */
public final class MotionSensors implements SensorEventListener {
    private final SensorManager sm;
    private final float[] g = new float[3];
    private boolean haveG = false;
    public volatile double tiltDeg = 0, accel = 0, gyro = 0;
    public volatile boolean running = false;
    public volatile long samples = 0;
    private double accelHold = 0, gyroHold = 0;
    private long accelHoldAt = 0, gyroHoldAt = 0;

    public MotionSensors(Context ctx) { sm = ctx.getSystemService(SensorManager.class); }

    public boolean start() {
        if (running || sm == null) return running;
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor r = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (a == null) return false;
        sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
        if (r != null) sm.registerListener(this, r, SensorManager.SENSOR_DELAY_GAME);
        running = true;
        return true;
    }

    public void stop() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false;
        haveG = false;
        tiltDeg = accel = gyro = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.nanoTime();
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            samples++;
            if (!haveG) { g[0] = e.values[0]; g[1] = e.values[1]; g[2] = e.values[2]; haveG = true; }
            float k = 0.12f;                                   // gravity low-pass (~0.2 s at 50 Hz)
            for (int i = 0; i < 3; i++) g[i] += (e.values[i] - g[i]) * k;
            double gm = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            if (gm > 0.5) tiltDeg = Math.toDegrees(Math.acos(Math.min(1, Math.abs(g[2]) / gm)));
            double lx = e.values[0] - g[0], ly = e.values[1] - g[1], lz = e.values[2] - g[2];
            double lin = Math.sqrt(lx * lx + ly * ly + lz * lz);
            accel = hold(lin, now, true);
        } else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            double w = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
            gyro = hold(w, now, false);
        }
    }

    /** Peak hold for 300 ms, then decay: movement reads as a level, not as 50 Hz spikes. */
    private double hold(double v, long now, boolean isAccel) {
        double h = isAccel ? accelHold : gyroHold;
        long at = isAccel ? accelHoldAt : gyroHoldAt;
        if (v >= h || now - at > 300_000_000L) { h = v; at = now; }
        else h += (v - h) * 0.08;
        if (isAccel) { accelHold = h; accelHoldAt = at; } else { gyroHold = h; gyroHoldAt = at; }
        return h;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
