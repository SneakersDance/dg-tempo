package com.tupai.dgtempo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Inter-onset-interval histogram for period, PLL-style phase lock, 4/4 downbeat by kick weight. */
public final class TempoTracker {
    public static final double MIN_BPM = 70, MAX_BPM = 180;
    public static final int BAR = 4;
    public static final int BAR_MIN_EVIDENCE = 8;
    /** Aligned beats needed to lock (set from the sensitivity slider). */
    public volatile int lockHits = 6;
    /** Share of inter-onset intervals that fall on the histogram peak. Random onsets give ~0.1-0.28,
     *  real music 0.4+. Below this the onsets are not periodic enough to count as a beat. */
    public volatile double confMin = 0.32;
    private static final double WINDOW_S = 5.0;

    private final ArrayDeque<double[]> onsets = new ArrayDeque<>();   // {t, weight}
    public Double period = null;
    public Double beat = null;
    public long anchorIndex = 0;
    public int hits = 0, misses = 0;
    public final double[] barWeight = new double[BAR];
    public int barEvidence = 0;
    public int manualRotate = 0;
    public int phase = 0;
    public int generation = 0;
    private double lastHit = 0;
    private Double pendingPeriod = null;
    private int pendingCount = 0;
    public double confidence = 0;     // periodicity of recent onsets, 0..1
    private double avgWeight = 0;     // typical weight of an aligned audio onset (for scaling outside evidence)
    public volatile long evidenceHits = 0;
    private double lastOnsetT = 0;

    public synchronized double bpm() { return period == null ? 0 : 60.0 / period; }
    public synchronized boolean locked() { return period != null && beat != null && hits >= lockHits && confidence >= confMin; }
    public synchronized double lastHitTime() { return lastHit; }

    /** Time-based unlock: beats stopped (music ended, noise only) -> forget everything. Returns true if it unlocked. */
    public synchronized boolean checkTimeout(double now) {
        if (period == null) return false;
        double gap = Math.max(2.0, 4 * period);
        if (now - lastHit > gap && now - lastOnsetT > gap) { boolean was = locked(); reset(); return was; }
        return false;
    }
    public synchronized boolean barKnown() { return locked() && barEvidence >= BAR_MIN_EVIDENCE; }
    public synchronized int downbeatPhase() { return (phase + manualRotate) % BAR; }
    public synchronized void rotateDownbeat() { manualRotate = (manualRotate + 1) % BAR; }
    public synchronized boolean isDownbeat(long index) { return Math.floorMod(index - downbeatPhase(), BAR) == 0; }

    public synchronized void reset() {
        onsets.clear(); period = null; beat = null; hits = misses = 0;
        java.util.Arrays.fill(barWeight, 0); barEvidence = 0; phase = 0; generation++;
        pendingPeriod = null; pendingCount = 0; confidence = 0;
    }

    private double fold(double ioi) {
        double lo = 60 / MAX_BPM, hi = 60 / MIN_BPM;
        while (ioi < lo) ioi *= 2;
        while (ioi > hi) ioi /= 2;
        return ioi;
    }

    private Double estimatePeriod() {
        double last = onsets.peekLast()[0];
        List<Double> ts = new ArrayList<>();
        for (double[] o : onsets) if (o[0] > last - WINDOW_S) ts.add(o[0]);
        if (ts.size() < 6) { confidence = 0; return null; }
        List<Double> iois = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            for (int j = i + 1; j < ts.size(); j++) {
                double d = ts.get(j) - ts.get(i);
                if (d > 2.5) break;
                if (d > 0.15) iois.add(fold(d));
            }
        }
        if (iois.size() < 8) { confidence = 0; return null; }
        double lo = 60 / MAX_BPM, hi = 60 / MIN_BPM;
        int nb = (int) Math.ceil((hi - lo) / 0.01);
        int[] h = new int[nb];
        for (double d : iois) h[Math.min(nb - 1, (int) Math.floor((d - lo) / 0.01))]++;
        int best = 0; double bestV = -1;
        for (int k = 0; k < nb; k++) {
            double v = (k > 0 ? h[k - 1] : 0) + 2 * h[k] + (k + 1 < nb ? h[k + 1] : 0);
            if (v > bestV) { bestV = v; best = k; }
        }
        double centre = lo + (best + 0.5) * 0.01;
        double sum = 0; int cnt = 0;
        for (double d : iois) if (Math.abs(d - centre) < 0.02) { sum += d; cnt++; }
        confidence = (double) cnt / iois.size();
        return cnt > 0 ? sum / cnt : centre;
    }

    /** Weighted count of recent onsets that sit on the grid (anchor, p). */
    private double phaseScore(double anchor, double p) {
        double score = 0;
        for (double[] o : onsets) {
            if (o[0] < lastOnsetT - 6) continue;
            double off = (o[0] - anchor) / p;
            double err = Math.abs(off - Math.round(off)) * p;
            if (err < 0.12 * p) score += 1 + o[1];       // count + weight: heavy kicks dominate light hats/stabs
        }
        return score;
    }

    /** The recent onset whose grid (with period p) collects the most weighted onsets. */
    private double[] bestAnchor(double p) {
        double bestT = lastOnsetT, best = -1;
        for (double[] o : onsets) {
            if (o[0] < lastOnsetT - 6) continue;
            double sc = phaseScore(o[0], p);
            if (sc > best) { best = sc; bestT = o[0]; }
        }
        return new double[]{bestT, best};
    }

    private void resetPhase(double t) {
        double anchor = t;
        if (period != null && onsets.size() >= 4) anchor = bestAnchor(period)[0];   // not just "the latest onset"
        beat = anchor; anchorIndex = 0; hits = 1; misses = 0; lastHit = t;
        java.util.Arrays.fill(barWeight, 0); barEvidence = 0; phase = 0; generation++;
        pendingPeriod = null; pendingCount = 0;
    }

    private void updatePhase() {
        int best = 0;
        for (int k = 1; k < BAR; k++) if (barWeight[k] > barWeight[best]) best = k;
        if (best != phase && barWeight[best] > 1.3 * barWeight[phase]) phase = best;
    }

    /** Returns the beat index this onset aligned to, or null if off-grid / not locked. */
    public synchronized Long addOnset(double t, double weight) {
        lastOnsetT = t;
        onsets.addLast(new double[]{t, weight});
        while (onsets.size() > 64) onsets.pollFirst();
        Double p = estimatePeriod();
        if (p == null) return null;
        if (period == null) { period = p; resetPhase(t); return null; }
        if (Math.abs(p - period) > 0.05 * period) {
            if (pendingPeriod != null && Math.abs(p - pendingPeriod) < 0.02) pendingCount++;
            else { pendingPeriod = p; pendingCount = 1; }
            if (pendingCount >= 3) { period = p; resetPhase(t); }
            return null;
        }
        pendingPeriod = null; pendingCount = 0;
        period = 0.9 * period + 0.1 * p;
        long n = Math.round((t - beat) / period);
        double predicted = beat + n * period;
        double err = t - predicted;
        if (Math.abs(err) < 0.12 * period) {
            beat = predicted + 0.15 * err;
            anchorIndex += n;
            hits++; misses = 0; lastHit = t;
            long idx = anchorIndex;
            for (int k = 0; k < BAR; k++) barWeight[k] *= 0.95;
            barWeight[(int) Math.floorMod(idx, BAR)] += weight;
            avgWeight = avgWeight == 0 ? weight : 0.9 * avgWeight + 0.1 * weight;
            barEvidence++;
            updatePhase();
            if (hits % 8 == 0) {
                // phase competition: if another grid offset collects clearly more (weighted) onsets, move to it
                double[] alt = bestAnchor(period);
                double cur = phaseScore(beat, period);
                double off = (alt[0] - beat) / period;
                boolean sameGrid = Math.abs(off - Math.round(off)) * period < 0.12 * period;
                if (!sameGrid && alt[1] > 1.5 * cur) {
                    beat = alt[0]; anchorIndex = 0; generation++;
                    java.util.Arrays.fill(barWeight, 0); barEvidence = 0; phase = 0;
                    return null;
                }
            }
            return idx;
        }
        misses++;
        if (t - lastHit > 4 * period) {
            // lost the grid for a whole bar: the tempo probably changed, measure it fresh
            double keep = t - 1.5;
            onsets.removeIf(o -> o[0] < keep);
            period = null; beat = null; hits = 0; confidence = 0; generation++;
            java.util.Arrays.fill(barWeight, 0); barEvidence = 0; phase = 0;
        }
        return null;
    }

    /**
     * Outside evidence for the downbeat (e.g. a dance move seen on screen at time t): if it lands on the beat
     * grid, weigh that beat-of-bar like `rel` typical kicks. Does not affect tempo or lock, only the "1".
     */
    public synchronized void addEvidence(double t, double rel) {
        if (!locked()) return;
        long n = Math.round((t - beat) / period);
        double err = t - (beat + n * period);
        if (Math.abs(err) > 0.15 * period) return;
        barWeight[(int) Math.floorMod(anchorIndex + n, BAR)] += rel * (avgWeight > 0 ? avgWeight : 1);
        evidenceHits++;
        updatePhase();
    }

    public synchronized double beatTime(long index) { return beat + (index - anchorIndex) * period; }

    /** {when, idx} of the next beat to fire on: downbeat if the bar is known, else any beat. null if unlocked. */
    public synchronized double[] nextFire(double after, boolean everyBeat) {
        if (!locked()) return null;
        long k = anchorIndex + (long) Math.ceil((after - beat) / period);
        if (barKnown() && !everyBeat) while (!isDownbeat(k)) k++;
        return new double[]{beatTime(k), k};
    }
}
