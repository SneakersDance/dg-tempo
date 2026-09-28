package com.tupai.dgtempo;

import android.bluetooth.BluetoothDevice;

/** Opossum 负鼠 vibration controller (47L127000): same B0 frame shape, B3 intensity, B2 display. */
public final class OpossumDevice extends BleDevice {
    private Settings lastSettings;

    public OpossumDevice(BluetoothDevice d, Listener l) { super("opossum", "Opossum", d, l); }

    @Override public int cap() { return lastSettings == null ? 200 : (lastSettings.vibFollowTempo ? lastSettings.vibMax : 200); }

    @Override protected void afterConnect(Settings s) { lastSettings = s; }

    @Override
    protected void onNotify(byte[] data) {
        int[] p = Protocol.parseOpossumB3(data);
        if (p == null) return;
        if ((p[0] != actualA || p[1] != actualB) && p[0] != strength) listener.onLog("Opossum button: A=" + p[0] + " B=" + p[1]);
        actualA = p[0]; actualB = p[1];
    }

    @Override
    public int targetStrength(double x, int offset, Settings s) {
        int v = s.vibFollowTempo ? (int) Math.round(s.vibMin + x * (s.vibMax - s.vibMin)) : s.vibManual;
        return Math.max(0, Math.min(200, v));
    }

    @Override
    public void sendFrame(int strength, boolean setStrength, int[] fa, int[] ia, int[] fb, int[] ib, Settings s) {
        if (!connected) return;
        lastSettings = s;
        if (setStrength || (s.vibBothMotors && actualB != strength && strength > 0)) {
            int sb = s.vibBothMotors ? strength : 0xFF;
            enqueue(Protocol.buildOpossumB3(strength, sb));
            enqueue(Protocol.buildOpossumB2(strength, s.vibBothMotors ? strength : actualB));
            this.strength = strength;
            if (s.vibBothMotors) actualB = strength;
        }
        enqueue(Protocol.buildOpossumB0(ia, ib));
    }
}
