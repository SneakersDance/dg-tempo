package com.tupai.dgtempo;

import java.util.ArrayList;
import java.util.List;

/** Lookahead slot scheduler (pure, unit-tested): which 25 ms slots of a frame carry a burst. */
public final class Scheduler {
    private Scheduler() {}

    /**
     * Output intervals [start,end) touching [t0,t1), from predicted beats/downbeats. Each burst starts
     * `latency` early so it is felt on the beat. testBurstUntil > t0 adds a one-off burst ending then.
     */
    public static List<double[]> burstsIn(TempoTracker tr, boolean active, double t0, double t1,
                                          double latency, double burstLen, boolean everyBeat, double testBurstUntil) {
        return burstsIn(tr, active, t0, t1, latency, burstLen, everyBeat, testBurstUntil, 1);
    }

    /** subdiv > 1 (every-beat mode only) adds extra bursts between beats: 2 = eighths, 4 = sixteenths. */
    public static List<double[]> burstsIn(TempoTracker tr, boolean active, double t0, double t1,
                                          double latency, double burstLen, boolean everyBeat, double testBurstUntil,
                                          int subdiv) {
        List<double[]> out = new ArrayList<>();
        if (testBurstUntil > t0) out.add(new double[]{testBurstUntil - burstLen, testBurstUntil});
        if (!active || !tr.locked()) return out;
        double period = tr.period == null ? 0.5 : tr.period;
        int sub = everyBeat ? Math.max(1, subdiv) : 1;
        double step = period / sub;
        // start one beat early so sub-bursts of the previous beat that spill into this frame are included
        double after = t0 + latency - burstLen - period;
        for (int i = 0; i < 8; i++) {
            double[] nf = tr.nextFire(after, everyBeat);
            if (nf == null) break;
            boolean past = false;
            for (int m = 0; m < sub; m++) {
                double start = nf[0] + m * step - latency;
                if (start >= t1) { past = true; break; }
                if (start + burstLen > t0) out.add(new double[]{start, start + burstLen});
            }
            if (past) break;
            after = nf[0] + 1e-3;
        }
        return out;
    }

    /** Per-slot (freq, intensity) for one frame. */
    public static final class Slots {
        public final int[] freq = {10, 10, 10, 10};
        public final int[] inten = new int[4];
        public boolean any() { return inten[0] + inten[1] + inten[2] + inten[3] > 0; }
    }

    /**
     * Render a waveform into the 4 slots of the frame at `tick`. Each burst [start,end) plays the
     * waveform from its row 0 at `start`; where bursts overlap the most recent start wins (a new beat
     * restarts the waveform). loop=true keeps cycling the waveform inside the burst, else it plays once.
     * intensityPct scales the waveform's own 0-100 intensities.
     */
    public static Slots renderSlots(List<double[]> bursts, double tick, Waveforms.Waveform w, int intensityPct, boolean loop) {
        Slots out = new Slots();
        int rows = w.rows();
        for (int j = 0; j < 4; j++) {
            double centre = tick + j * Protocol.SLOT_S + Protocol.SLOT_S / 2;
            double bestStart = Double.NEGATIVE_INFINITY;
            for (double[] b : bursts) if (b[0] <= centre && centre < b[1] && b[0] > bestStart) bestStart = b[0];
            if (bestStart == Double.NEGATIVE_INFINITY) continue;
            double off = centre - bestStart;
            int row = (int) Math.floor(off / Protocol.FRAME_S);
            if (row >= rows) { if (!loop) continue; row %= rows; }
            int slot = Math.min(3, (int) Math.floor((off - Math.floor(off / Protocol.FRAME_S) * Protocol.FRAME_S) / Protocol.SLOT_S));
            out.freq[j] = w.freq[row][slot];
            out.inten[j] = Math.max(0, Math.min(100, w.inten[row][slot] * intensityPct / 100));
        }
        return out;
    }

    public static int[] frameFor(TempoTracker tr, boolean active, double tick, double latency, double burstLen,
                                 boolean everyBeat, double testBurstUntil, int intensity) {
        List<double[]> b = burstsIn(tr, active, tick, tick + Protocol.FRAME_S, latency, burstLen, everyBeat, testBurstUntil);
        return Protocol.slotPattern(tick, b, Math.max(0, Math.min(100, intensity)));
    }
}
