package com.tupai.dgtempo;

/**
 * Screen-motion onset detector. Input: one scalar per video frame = mean |luma difference| to the
 * previous frame (0..255). Same recipe as the audio onset detector: log-flux, adaptive threshold,
 * noise-floor gate, refractory. No lookahead, so an onset is reported on the frame it happens.
 */
public final class MotionDetector {
    private static final int HIST = 30;          // ~1 s at 30 fps
    public volatile double sensitivity = 2.0;    // threshold in std-devs
    public volatile double gateDb = 6.0;         // peak must exceed the motion floor by this much
    private final double[] hist = new double[HIST];
    private int histN, histPos;
    private double prevLog, prevFlux;
    private double lastOnset = -1;
    private double floor = -1;
    public volatile double level = 0, floorLevel = 0, flux, thresh;
    public volatile long onsets = 0;
    public volatile double lastFrame = 0;
    /** Motion value a frame must reach right now to count (floor gate). */
    public double triggerLevel() { return Math.max(1.0, (Math.max(0, floor) + 0.5) * Math.pow(10, gateDb / 10)); }

    /** Feed one frame's motion value m at time t (seconds). Returns true if this frame is a motion onset. */
    public boolean feed(double t, double m) {
        lastFrame = t;
        level = m;
        if (floor < 0 || m < floor) floor = m; else floor += (m - floor) * 0.01;
        floorLevel = floor;
        double lg = Math.log1p(m * 2.0);
        double fl = Math.max(0, lg - prevLog);
        prevLog = lg;
        double mean = 0, sq = 0;
        int cnt = Math.max(1, histN);
        for (int k = 0; k < histN; k++) { mean += hist[k]; sq += hist[k] * hist[k]; }
        mean /= cnt; sq /= cnt;
        double std = Math.sqrt(Math.max(0, sq - mean * mean));
        double th = mean + sensitivity * Math.max(std, 0.03) + 0.01;
        hist[histPos] = fl;
        histPos = (histPos + 1) % HIST;
        if (histN < HIST) histN++;
        flux = fl; thresh = th;
        boolean loud = m >= 1.0 && m >= (floor + 0.5) * Math.pow(10, gateDb / 10);
        boolean onset = fl > th && fl > prevFlux && loud && t - lastOnset > 0.15;
        prevFlux = fl;
        if (onset) { lastOnset = t; onsets++; }
        return onset;
    }
}
