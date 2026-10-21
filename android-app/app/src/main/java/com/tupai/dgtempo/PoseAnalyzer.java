package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.List;

/**
 * Reads a video from the phone once and turns it into a PoseTrack (~8 poses per second), on a worker thread.
 * Decoding and pose detection both happen on the device; no frame leaves it.
 */
public final class PoseAnalyzer {
    public interface Progress {
        void onProgress(int pct, int frames, int judged);
        void onDone(PoseTrack track, String error);
    }
    private static final double SAMPLE_FPS = 8;
    public volatile boolean cancelled = false;

    /** ML Kit pose -> 33 x (x, y, conf) normalised to the image. */
    public static float[] landmarks(Pose pose, int w, int h) {
        float[] lm = new float[PoseMatch.N_LM * 3];
        List<PoseLandmark> all = pose.getAllPoseLandmarks();
        for (PoseLandmark p : all) {
            int i = p.getLandmarkType();
            if (i < 0 || i >= PoseMatch.N_LM) continue;
            lm[i * 3] = p.getPosition().x / w; lm[i * 3 + 1] = p.getPosition().y / h; lm[i * 3 + 2] = p.getInFrameLikelihood();
        }
        return lm;
    }

    public void run(Context ctx, Uri uri, Progress cb) {
        new Thread(() -> {
            PoseDetector det = PoseDetection.getClient(new PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build());
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            PoseTrack t = new PoseTrack();
            try {
                r.setDataSource(ctx, uri);
                long durMs = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                long frames = Build.VERSION.SDK_INT >= 28 ? parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)) : 0;
                if (durMs <= 0) throw new IllegalStateException("no duration");
                t.durationMs = durMs;
                int lastPct = -1, judged = 0;
                if (Build.VERSION.SDK_INT >= 28 && frames > 0) {
                    // sequential frame index access: the decoder runs once through the file
                    double fps = frames * 1000.0 / durMs;
                    int step = Math.max(1, (int) Math.round(fps / SAMPLE_FPS));
                    for (long i = 0; i < frames && !cancelled; i += step) {
                        Bitmap bm = r.getFrameAtIndex((int) i);
                        if (bm == null) continue;
                        long at = Math.round(i * 1000.0 / fps);
                        float[] f = analyse(det, bm);
                        if (PoseMatch.validCount(f) >= 4) judged++;
                        t.add(at, f);
                        int pct = (int) (i * 100 / frames);
                        if (pct != lastPct) { lastPct = pct; cb.onProgress(pct, t.size(), judged); }
                    }
                } else {
                    long stepMs = Math.round(1000 / SAMPLE_FPS);
                    for (long at = 0; at < durMs && !cancelled; at += stepMs) {
                        Bitmap bm = Build.VERSION.SDK_INT >= 27
                                ? r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 480, 480)
                                : r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (bm == null) continue;
                        float[] f = analyse(det, bm);
                        if (PoseMatch.validCount(f) >= 4) judged++;
                        t.add(at, f);
                        int pct = (int) (at * 100 / durMs);
                        if (pct != lastPct) { lastPct = pct; cb.onProgress(pct, t.size(), judged); }
                    }
                }
                if (cancelled) cb.onDone(null, "cancelled"); else cb.onDone(t, null);
            } catch (Exception e) {
                cb.onDone(null, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            } finally {
                try { r.release(); } catch (Exception ignored) {}
                det.close();
            }
        }, "pose-analyser").start();
    }

    private static float[] analyse(PoseDetector det, Bitmap bm) throws Exception {
        int w = bm.getWidth(), h = bm.getHeight();
        int max = Math.max(w, h);
        Bitmap use = bm;
        if (max > 512) {                                         // the pose model works at ~256 px; smaller = faster
            float s = 512f / max;
            use = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
        }
        try {
            Pose pose = Tasks.await(det.process(InputImage.fromBitmap(use, 0)));
            float[] lm = landmarks(pose, use.getWidth(), use.getHeight());
            return PoseMatch.features(lm, use.getWidth() / (float) use.getHeight());
        } finally {
            if (use != bm) use.recycle();
            bm.recycle();
        }
    }

    private static long parse(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
}
