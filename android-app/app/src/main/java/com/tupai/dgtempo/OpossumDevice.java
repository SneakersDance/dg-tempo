package com.tupai.dgtempo;

import android.bluetooth.BluetoothDevice;

/** Opossum 负鼠 vibration controller (47L127000): same B0 frame shape, B3 intensity, B2 display. */
public final class OpossumDevice extends BleDevice {
    private Settings lastSettings;

    public OpossumDevice(BluetoothDevice d, Listener l) { super("opossum", "Opossum", d, l); }

    @Override public int cap() {
        if (lastSettings == null) return 200;
        Settings.ChannelCfg c = lastSettings.chan("opossum", 0);
        return lastSettings.vibFollowTempo ? c.max : 200;
    }

    @Override protected void afterConnect(Settings s) { lastSettings = s; }

    @Override
    protected void onNotify(byte[] data) {
        int[] p = Protocol.parseOpossumB3(data);
        if (p == null) return;
        if ((p[0] != actualA || p[1] != actualB) && p[0] != strength) listener.onLog("Opossum button: A=" + p[0] + " B=" + p[1]);
        actualA = p[0]; actualB = p[1];
    }

    @Override
    public int targetStrength(double x, int offset, Settings s, Settings.ChannelCfg c) {
        int v = s.vibFollowTempo ? (int) Math.round(c.min + x * (c.max - c.min)) : c.manual;
        return Math.max(0, Math.min(200, v));
    }

    @Override
    public void sendFrame(int sa, int sb, boolean setStrength, int[] fa, int[] ia, int[] fb, int[] ib, Settings s) {
        if (!connected) return;
        lastSettings = s;
        if (setStrength || (s.vibBothMotors && actualB != sb && sb > 0)) {
            enqueue(Protocol.buildOpossumB3(sa, s.vibBothMotors ? sb : 0xFF));
            enqueue(Protocol.buildOpossumB2(sa, s.vibBothMotors ? sb : actualB));
            this.strength = sa; this.strengthB = sb;
            if (s.vibBothMotors) actualB = sb;
        }
        enqueue(Protocol.buildOpossumB0(ia, ib));
    }
}
