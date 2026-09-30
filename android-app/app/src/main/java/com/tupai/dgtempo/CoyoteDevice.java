package com.tupai.dgtempo;

import android.bluetooth.BluetoothDevice;

/** Coyote 3.0 pulse host (47L121000): B0 waveform frames, BF soft cap, B1 strength replies. */
public final class CoyoteDevice extends BleDevice {
    private int seq = 0;
    private volatile int pendingSeq = -1;
    private volatile long pendingSince = 0;
    private int capWrittenA = -1, capWrittenB = -1;

    public CoyoteDevice(BluetoothDevice d, Listener l) { super("coyote", "Coyote", d, l); }

    @Override public int cap() { return capWrittenA < 0 ? 0 : capWrittenA; }

    @Override
    protected void afterConnect(Settings s) { writeCap(s); }

    private int capB(Settings s) { return s.channelB ? s.chan("coyote", 1).max : 0; }

    /** BF soft caps (A and B separately); rewritten on every (re)connect and whenever a max changes. */
    public void writeCap(Settings s) {
        int capA = s.chan("coyote", 0).max, capB = capB(s);
        enqueue(Protocol.buildBF(capA, capB, s.freqBalance, s.freqBalance, s.intensityBalance, s.intensityBalance));
        capWrittenA = capA; capWrittenB = capB;
        listener.onLog("Coyote: soft cap A=" + capA + " B=" + capB);
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
    public int targetStrength(double x, int offset, Settings s, Settings.ChannelCfg c) {
        int v = (s.autoStrength || c.randomLevel) ? (int) Math.round(c.min + x * (c.max - c.min)) : c.min;
        v += offset;
        return Math.max(0, Math.min(c.max, v));
    }

    @Override
    public void sendFrame(int sa, int sb, boolean setStrength, int[] fa, int[] ia, int[] fb, int[] ib, Settings s) {
        if (!connected) return;
        if (capWrittenA != s.chan("coyote", 0).max || capWrittenB != capB(s)) writeCap(s);
        int sq = 0, mode = 0;
        if (setStrength) {
            seq = seq % 15 + 1;
            sq = seq; mode = 0b1111;
            pendingSeq = sq; pendingSince = System.nanoTime();
            this.strength = sa; this.strengthB = sb;
        }
        enqueue(Protocol.buildB0(sq, mode, sa, s.channelB ? sb : 0, fa, ia, fb, ib));
    }
}
