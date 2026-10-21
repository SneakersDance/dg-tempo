package com.tupai.dgtempo;

/**
 * Pose comparison, pure and unit-tested. A pose is 33 ML Kit landmarks as (x, y, confidence) triples,
 * x and y normalised to the image (0..1) - the image aspect is passed separately so angles are true.
 * The comparison uses the orientation of eight limb segments relative to the torso axis, which ignores
 * body size, distance from the camera and where the dancer stands in the frame.
 */
public final class PoseMatch {
    public static final int N_LM = 33, N_FEAT = 8;
    // ML Kit landmark indices
    static final int L_SHOULDER = 11, R_SHOULDER = 12, L_ELBOW = 13, R_ELBOW = 14, L_WRIST = 15, R_WRIST = 16,
            L_HIP = 23, R_HIP = 24, L_KNEE = 25, R_KNEE = 26, L_ANKLE = 27, R_ANKLE = 28;
    /** segments: L upper arm, L forearm, L thigh, L shin, then the same on the right */
    static final int[][] SEG = {{L_SHOULDER, L_ELBOW}, {L_ELBOW, L_WRIST}, {L_HIP, L_KNEE}, {L_KNEE, L_ANKLE},
                                {R_SHOULDER, R_ELBOW}, {R_ELBOW, R_WRIST}, {R_HIP, R_KNEE}, {R_KNEE, R_ANKLE}};
    static final float MIN_CONF = 0.5f;

    private PoseMatch() {}

    /** Eight segment angles in degrees (signed, relative to the shoulders-to-hips axis); NaN where a joint is unsure. */
    public static float[] features(float[] lm, float aspect) {
        float[] f = new float[N_FEAT];
        java.util.Arrays.fill(f, Float.NaN);
        if (lm == null || lm.length < N_LM * 3) return f;
        if (conf(lm, L_SHOULDER) < MIN_CONF || conf(lm, R_SHOULDER) < MIN_CONF || conf(lm, L_HIP) < MIN_CONF || conf(lm, R_HIP) < MIN_CONF) return f;
        double tx = (x(lm, L_HIP, aspect) + x(lm, R_HIP, aspect)) / 2 - (x(lm, L_SHOULDER, aspect) + x(lm, R_SHOULDER, aspect)) / 2;
        double ty = (y(lm, L_HIP) + y(lm, R_HIP)) / 2 - (y(lm, L_SHOULDER) + y(lm, R_SHOULDER)) / 2;
        double tl = Math.hypot(tx, ty);
        if (tl < 1e-4) return f;
        tx /= tl; ty /= tl;
        for (int i = 0; i < N_FEAT; i++) {
            int a = SEG[i][0], b = SEG[i][1];
            if (conf(lm, a) < MIN_CONF || conf(lm, b) < MIN_CONF) continue;
            double vx = x(lm, b, aspect) - x(lm, a, aspect), vy = y(lm, b) - y(lm, a);
            double vl = Math.hypot(vx, vy);
            if (vl < 0.02 * tl) continue;                     // segment too short to have a direction
            double dot = (vx * tx + vy * ty) / vl, cross = (vx * ty - vy * tx) / vl;
            f[i] = (float) Math.toDegrees(Math.atan2(cross, dot));
        }
        return f;
    }

    /** Mean angular error in degrees between two feature sets (NaN if fewer than 4 segments are comparable). */
    public static double error(float[] a, float[] b, boolean mirror) {
        if (a == null || b == null) return Double.NaN;
        double sum = 0; int n = 0;
        for (int i = 0; i < N_FEAT; i++) {
            int j = mirror ? (i + 4) % N_FEAT : i;             // mirror: left <-> right, angle sign flips
            float fa = a[i], fb = b[j];
            if (Float.isNaN(fa) || Float.isNaN(fb)) continue;
            if (mirror) fb = -fb;
            double d = Math.abs(fa - fb) % 360;
            if (d > 180) d = 360 - d;
            sum += d; n++;
        }
        return n >= 4 ? sum / n : Double.NaN;
    }

    /** 0..100 "how well it matches" for display. */
    public static int matchPct(double errDeg) {
        if (Double.isNaN(errDeg)) return -1;
        return (int) Math.round(Math.max(0, Math.min(100, 100 * (1 - errDeg / 90.0))));
    }

    public static int validCount(float[] f) { int n = 0; for (float v : f) if (!Float.isNaN(v)) n++; return n; }

    private static float x(float[] lm, int i, float aspect) { return lm[i * 3] * aspect; }
    private static float y(float[] lm, int i) { return lm[i * 3 + 1]; }
    private static float conf(float[] lm, int i) { return lm[i * 3 + 2]; }
}
