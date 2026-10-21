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
    private static final int MAGIC = 0x50534531;              // "PSE1"
    public final List<Long> ms = new ArrayList<>();
    public final List<float[]> feats = new ArrayList<>();
    public long durationMs = 0;

    public void add(long atMs, float[] f) { ms.add(atMs); feats.add(f); }
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
            o.writeInt(MAGIC); o.writeLong(durationMs); o.writeInt(ms.size());
            for (int i = 0; i < ms.size(); i++) { o.writeLong(ms.get(i)); for (float v : feats.get(i)) o.writeFloat(v); }
        }
    }

    public static PoseTrack load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != MAGIC) throw new IOException("bad pose file");
            PoseTrack t = new PoseTrack();
            t.durationMs = in.readLong();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long at = in.readLong();
                float[] ft = new float[PoseMatch.N_FEAT];
                for (int k = 0; k < ft.length; k++) ft[k] = in.readFloat();
                t.add(at, ft);
            }
            return t;
        }
    }
}
