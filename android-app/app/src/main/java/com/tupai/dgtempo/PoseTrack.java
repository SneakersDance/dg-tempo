package com.tupai.dgtempo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The choreography of a video as a timeline of pose features (sampled ~8x per second), cached on disk. */
public final class PoseTrack {
    private static final int MAGIC = 0x50534532;              // "PSE2": v2 also stores the landmarks for the overlay
    public final List<Long> ms = new ArrayList<>();
    public final List<float[]> feats = new ArrayList<>();
    public final List<float[]> lms = new ArrayList<>();      // 33 x (x, y, conf) normalised to the video frame, or null
    public long durationMs = 0;
    public float aspect = 1f;                                // video frame width / height

    public void add(long atMs, float[] f) { add(atMs, f, null); }
    public void add(long atMs, float[] f, float[] lm) { ms.add(atMs); feats.add(f); lms.add(lm); }

    /** Index of the frame nearest to the playback position (-1 when empty). */
    public int nearest(long posMs) {
        if (ms.isEmpty()) return -1;
        int lo = 0, hi = ms.size() - 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (ms.get(mid) < posMs) lo = mid + 1; else hi = mid; }
        if (lo > 0 && Math.abs(ms.get(lo - 1) - posMs) < Math.abs(ms.get(lo) - posMs)) lo--;
        return lo;
    }
    public int size() { return ms.size(); }

    /** Frames where the dancer was clearly visible (at least 4 limb segments). */
    public int judged() { int n = 0; for (float[] f : feats) if (PoseMatch.validCount(f) >= 4) n++; return n; }

    /** Smallest error against any reference frame within +-windowMs of the playback position (NaN = nothing to judge). */
    public double bestError(float[] live, long posMs, long windowMs, boolean mirror) {
        if (live == null || ms.isEmpty()) return Double.NaN;
        int lo = 0, hi = ms.size() - 1;
        long from = posMs - windowMs;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (ms.get(mid) < from) lo = mid + 1; else hi = mid; }
        double best = Double.NaN;
        if (ms.get(lo) < from) return Double.NaN;                 // every frame is before the window
        for (int i = lo; i < ms.size() && ms.get(i) <= posMs + windowMs; i++) {
            double e = PoseMatch.error(live, feats.get(i), mirror);
            if (!Double.isNaN(e) && (Double.isNaN(best) || e < best)) best = e;
        }
        return best;
    }

    public void save(File f) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.writeInt(MAGIC); o.writeLong(durationMs); o.writeFloat(aspect); o.writeInt(ms.size());
            for (int i = 0; i < ms.size(); i++) {
                o.writeLong(ms.get(i));
                for (float v : feats.get(i)) o.writeFloat(v);
                float[] lm = lms.get(i);
                o.writeBoolean(lm != null);
                if (lm != null) for (int k = 0; k < PoseMatch.N_LM * 3; k++) o.writeFloat(lm[k]);
            }
        }
    }

    public static PoseTrack load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != MAGIC) throw new IOException("bad pose file");
            PoseTrack t = new PoseTrack();
            t.durationMs = in.readLong();
            t.aspect = in.readFloat();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long at = in.readLong();
                float[] ft = new float[PoseMatch.N_FEAT];
                for (int k = 0; k < ft.length; k++) ft[k] = in.readFloat();
                float[] lm = null;
                if (in.readBoolean()) { lm = new float[PoseMatch.N_LM * 3]; for (int k = 0; k < lm.length; k++) lm[k] = in.readFloat(); }
                t.add(at, ft, lm);
            }
            return t;
        }
    }
}
