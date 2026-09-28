package com.tupai.dgtempo;

import android.bluetooth.BluetoothDevice;

/** Coyote 3.0 pulse host (47L121000): B0 waveform frames, BF soft cap, B1 strength replies. */
public final class CoyoteDevice extends BleDevice {
    private int seq = 0;
    private volatile int pendingSeq = -1;
    private volatile long pendingSince = 0;
    private int capWritten = -1;

    public CoyoteDevice(BluetoothDevice d, Listener l) { super("coyote", "Coyote", d, l); }

    @Override public int cap() { return capWritten < 0 ? 0 : capWritten; }

    @Override
    protected void afterConnect(Settings s) { writeCap(s); }

    /** BF soft cap; must be rewritten on every (re)connect and whenever the user changes the max. */
    public void writeCap(Settings s) {
        int capB = s.channelB ? s.coyoteMax : 0;
        enqueue(Protocol.buildBF(s.coyoteMax, capB, s.freqBalance, s.freqBalance, s.intensityBalance, s.intensityBalance));
        capWritten = s.coyoteMax;
        listener.onLog("Coyote: soft cap A=" + s.coyoteMax + " B=" + capB);
    }

    @Override
    protected void onNotify(byte[] data) {
        int[] p = Protocol.parseB1(data);
        if (p == null) return;
        if (p[0] != 0 && p[0] == pendingSeq) pendingSeq = -1;
        if (p[0] == 0 && (p[1] != actualA || p[2] != actualB)) listener.onLog("Coyote wheel: A=" + p[1] + " B=" + p[2]);
        actualA = p[1]; actualB = p[2];
    }

    @Override
    public boolean strengthChangeAllowed() {
        if (pendingSeq < 0) return true;
        if (System.nanoTime() - pendingSince > 500_000_000L) { pendingSeq = -1; return true; }
        return false;
    }

    @Override
    public int targetStrength(double x, int offset, Settings s) {
        int v = (s.autoStrength || s.coyoteRandomLevel) ? (int) Math.round(s.coyoteMin + x * (s.coyoteMax - s.coyoteMin)) : s.coyoteMin;
        v += offset;
        return Math.max(0, Math.min(s.coyoteMax, v));
    }

    @Override
    public void sendFrame(int strength, boolean setStrength, int[] fa, int[] ia, int[] fb, int[] ib, Settings s) {
        if (!connected) return;
        if (capWritten != s.coyoteMax) writeCap(s);
        int sq = 0, mode = 0;
        if (setStrength) {
            seq = seq % 15 + 1;
            sq = seq; mode = 0b1111;
            pendingSeq = sq; pendingSince = System.nanoTime();
            this.strength = strength;
        }
        int sb = s.channelB ? strength : 0;
        enqueue(Protocol.buildB0(sq, mode, strength, sb, fa, ia, fb, ib));
    }
}
