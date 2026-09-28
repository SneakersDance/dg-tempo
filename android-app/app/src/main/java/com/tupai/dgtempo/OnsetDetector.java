package com.tupai.dgtempo;

/**
 * Kick onset detector fed with raw PCM. A band-pass biquad (~45-180 Hz) isolates the kick, energy is
 * measured per hop (512 samples), and a rising edge of log-energy flux above an adaptive threshold is
 * an onset. Onset "weight" = peak band energy over the ~40 ms after the onset (used to find the downbeat).
 */
public final class OnsetDetector {
    public static final int HOP = 512;
    private static final int HIST = 96;

    public static final class Onset {
        public final double t, weight;
        Onset(double t, double weight) { this.t = t; this.weight = weight; }
    }

    private final double sampleRate;
    public volatile double sensitivity;
    private final double refractory;
    // biquads: "low" 45-180 Hz (kick fundamental, used for downbeat weight) and "wide" ~60-550 Hz
    // (kick punch + snare body: what a phone mic actually hears; used for onset detection)
    private final double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;
    private final double wb0, wb1, wb2, wa1, wa2;
    private double wx1, wx2, wy1, wy2;
    // hop accumulation
    private double accBand, accWide, accFull;
    private int accN;
    // sound presence: full-band peak (300 ms hold) vs full-band floor
    private double fullFloor = -1;
    private final double[] fullRing = new double[28];
    private int fullPos = 0;
    public volatile double musicDb = 0;
    public volatile double soundGateDb = 6.0;
    // detection state
    private final double[] hist = new double[HIST];
    private int histN, histPos;
    private double prevLog, prevFlux;
    private double lastOnset = -1;
    private double pendingT, pendingPeak, pendingLow;
    private int pendingLeft;
    public volatile double levelDb = -100, flux, thresh;
    public volatile double lastAudio;
    // Noise floor of the kick band: drops instantly to the quietest hop, rises slowly (~5 s).
    // An onset only counts if its peak energy is GATE_DB above the floor and above an absolute minimum,
    // so hiss / fan / crowd static cannot produce onsets even if their flux wobbles.
    private double floor = -1;
    public volatile double floorDb = -100, gateDb = 10.0;
    private static final double ABS_MIN = 1e-6;   // ~ -60 dBFS mean-square
    public volatile long rejected = 0;

    public OnsetDetector(double sampleRate, double sensitivity, double refractory) {
        this.sampleRate = sampleRate;
        this.sensitivity = sensitivity;
        this.refractory = refractory;
        // RBJ band-pass, f0 = 90 Hz, Q = 0.7 (constant 0 dB peak gain)
        double w0 = 2 * Math.PI * 90.0 / sampleRate, q = 0.7;
        double alpha = Math.sin(w0) / (2 * q), cosw = Math.cos(w0);
        double a0 = 1 + alpha;
        b0 = alpha / a0; b1 = 0; b2 = -alpha / a0;
        a1 = -2 * cosw / a0; a2 = (1 - alpha) / a0;
        double ww0 = 2 * Math.PI * 180.0 / sampleRate, wq = 0.45;
        double walpha = Math.sin(ww0) / (2 * wq), wcos = Math.cos(ww0);
        double wa0 = 1 + walpha;
        wb0 = walpha / wa0; wb1 = 0; wb2 = -walpha / wa0;
        wa1 = -2 * wcos / wa0; wa2 = (1 - walpha) / wa0;
    }

    public OnsetDetector(double sampleRate) { this(sampleRate, 2.0, 0.18); }

    /**
     * Feed PCM16 samples. tEndSeconds = onset-clock time of the LAST sample in buf (e.g. nanoTime at read
     * return). Returns an Onset when one completes, else null. At most one onset per call is returned;
     * call with <= HOP samples per call for full resolution.
     */
    public Onset feed(short[] buf, int n, double tEndSeconds) {
        Onset out = null;
        for (int i = 0; i < n; i++) {
            double x = buf[i] / 32768.0;
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            double wy = wb0 * x + wb1 * wx1 + wb2 * wx2 - wa1 * wy1 - wa2 * wy2;
            wx2 = wx1; wx1 = x; wy2 = wy1; wy1 = wy;
            accBand += y * y;
            accWide += wy * wy;
            accFull += x * x;
            if (++accN >= HOP) {
                double tHop = tEndSeconds - (n - 1 - i) / sampleRate;
                Onset o = hop(tHop, accWide / accN, accBand / accN, accFull / accN);
                if (o != null) out = o;
                accBand = accWide = accFull = 0; accN = 0;
            }
        }
        return out;
    }

    /** Is there sound above the noise floor right now (300 ms peak hold)? Drops within ~0.3 s of music stopping. */
    public boolean soundPresent() { return musicDb > soundGateDb; }

    /** One hop: wide-band (detection), low-band (weight) and full-band (level) mean-square energy at time t. */
    public Onset hop(double t, double wide, double low, double full) {
        lastAudio = t;
        levelDb = 10 * Math.log10(full + 1e-12);
        if (fullFloor < 0 || full < fullFloor) fullFloor = full; else fullFloor += (full - fullFloor) * 0.002;
        fullRing[fullPos] = full; fullPos = (fullPos + 1) % fullRing.length;
        double peak = 0;
        for (double v : fullRing) peak = Math.max(peak, v);
        musicDb = 10 * Math.log10((peak + 1e-12) / (fullFloor + 1e-12));
        double band = wide;
        if (floor < 0 || band < floor) floor = band; else floor += (band - floor) * 0.002;
        floorDb = 10 * Math.log10(floor + 1e-12);
        double lg = Math.log1p(band * 2e3);
        double fl = Math.max(0, lg - prevLog);
        prevLog = lg;

        double mean = 0, sq = 0;
        int cnt = Math.max(1, histN);
        for (int k = 0; k < histN; k++) { mean += hist[k]; sq += hist[k] * hist[k]; }
        mean /= cnt; sq /= cnt;
        double std = Math.sqrt(Math.max(0, sq - mean * mean));
        double th = mean + sensitivity * Math.max(std, 0.05) + 0.02;
        hist[histPos] = fl;
        histPos = (histPos + 1) % HIST;
        if (histN < HIST) histN++;
        flux = fl; thresh = th;

        Onset out = null;
        if (pendingLeft > 0) {
            pendingPeak = Math.max(pendingPeak, band);
            pendingLow = Math.max(pendingLow, low);
            if (--pendingLeft == 0) {
                boolean loud = pendingPeak >= ABS_MIN && pendingPeak >= floor * Math.pow(10, gateDb / 10);
                if (loud) out = new Onset(pendingT, pendingLow + 0.1 * pendingPeak); else rejected++;
            }
        } else if (fl > th && fl > prevFlux && t - lastOnset > refractory) {
            lastOnset = t;
            pendingT = t; pendingPeak = band; pendingLow = low; pendingLeft = 4;
        }
        prevFlux = fl;
        return out;
    }
}
