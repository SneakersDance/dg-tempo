package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.RectF;
import android.media.Image;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera + on-device person segmentation. Each analysed frame yields: how much of the frame is
 * "person" and what share of that person lies outside the box. No frame is kept or sent anywhere.
 * The box is in normalised upright-image coordinates (0..1); the front camera is mirrored to match
 * the mirrored preview.
 */
public final class CageDetector {
    public interface Listener { void onFrame(double area, double outsideShare, float cx, float cy); }

    private final Context ctx;
    private final LifecycleOwner owner;
    private ProcessCameraProvider provider;
    private Preview preview;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Segmenter segmenter;
    public volatile boolean running = false, front = false;
    public volatile RectF box = new RectF(0.2f, 0.1f, 0.8f, 0.9f);
    public volatile Listener listener;
    public volatile long frames = 0;
    public volatile int maskW = 0, maskH = 0;
    private long lastAt = 0;
    private volatile Preview.SurfaceProvider pendingSurface;
    private androidx.camera.core.Camera camera;
    public volatile float zoomMin = 1f, zoomMax = 1f, zoom = 1f;
    private float wantedZoom = 1f;

    public CageDetector(Context ctx, LifecycleOwner owner) { this.ctx = ctx; this.owner = owner; }

    public void start(boolean frontCamera) {
        this.front = frontCamera;
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(ctx);
        f.addListener(() -> {
            try {
                provider = f.get();
                bind();
            } catch (Exception e) {
                android.util.Log.e("CageDetector", "camera init", e);
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx));
    }

    private void bind() {
        if (provider == null) return;
        provider.unbindAll();
        if (segmenter == null) {
            segmenter = Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build());
        }
        preview = new Preview.Builder().build();
        if (pendingSurface != null) preview.setSurfaceProvider(pendingSurface);
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(exec, this::analyze);
        CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            camera = provider.bindToLifecycle(owner, sel, preview, analysis);
            running = true;
            androidx.camera.core.ZoomState z = camera.getCameraInfo().getZoomState().getValue();
            if (z != null) { zoomMin = z.getMinZoomRatio(); zoomMax = z.getMaxZoomRatio(); }
            setZoom(wantedZoom);
        } catch (Exception e) {
            android.util.Log.e("CageDetector", "bind", e);
            running = false;
        }
    }

    /** The activity hands over its PreviewView surface when the tab is visible (null to detach). */
    public void attachPreview(Preview.SurfaceProvider sp) {
        pendingSurface = sp;
        Preview p = preview;
        if (p != null) androidx.core.content.ContextCompat.getMainExecutor(ctx).execute(() -> p.setSurfaceProvider(sp));
    }

    /** Zoom ratio; below 1.0 means the ultra-wide lens on phones that have one. Clamped to what the camera offers. */
    public void setZoom(float ratio) {
        wantedZoom = ratio;
        androidx.camera.core.Camera cam = camera;
        if (cam == null) return;
        float r = Math.max(zoomMin, Math.min(zoomMax, ratio));
        zoom = r;
        androidx.core.content.ContextCompat.getMainExecutor(ctx).execute(() -> cam.getCameraControl().setZoomRatio(r));
    }

    public void setFront(boolean f) {
        if (f == front) return;
        front = f;
        androidx.core.content.ContextCompat.getMainExecutor(ctx).execute(this::bind);
    }

    public void stop() {
        running = false;
        androidx.core.content.ContextCompat.getMainExecutor(ctx).execute(() -> { if (provider != null) provider.unbindAll(); });
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        long now = System.nanoTime();
        Image img = proxy.getImage();
        if (img == null || now - lastAt < 90_000_000L) { proxy.close(); return; }   // ~10 fps is plenty
        lastAt = now;
        InputImage in = InputImage.fromMediaImage(img, proxy.getImageInfo().getRotationDegrees());
        segmenter.process(in)
                .addOnSuccessListener(exec, this::onMask)
                .addOnCompleteListener(exec, t -> proxy.close());
    }

    private void onMask(SegmentationMask mask) {
        int w = mask.getWidth(), h = mask.getHeight();
        maskW = w; maskH = h;
        java.nio.ByteBuffer bb = mask.getBuffer();
        bb.rewind();
        FloatBuffer buf = bb.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();   // one float (0..1) per pixel
        RectF b = box;
        float bl = front ? 1 - b.right : b.left, br = front ? 1 - b.left : b.right;
        long person = 0, outside = 0;
        double sx = 0, sy = 0;
        int step = Math.max(1, w / 128);                      // sample ~128 columns: cheap and plenty
        for (int y = 0; y < h; y += step) {
            float fy = (y + 0.5f) / h;
            boolean yIn = fy >= b.top && fy <= b.bottom;
            for (int x = 0; x < w; x += step) {
                float conf = buf.get(y * w + x);
                if (conf < 0.6f) continue;
                person++;
                float fx = (x + 0.5f) / w;
                sx += fx; sy += fy;
                if (!(yIn && fx >= bl && fx <= br)) outside++;
            }
        }
        frames++;
        long cells = ((h + step - 1) / step) * (long) ((w + step - 1) / step);
        double area = person / (double) Math.max(1, cells);
        double share = person == 0 ? 1.0 : outside / (double) person;
        float cx = person == 0 ? -1 : (float) (sx / person), cy = person == 0 ? -1 : (float) (sy / person);
        if (front && cx >= 0) cx = 1 - cx;
        Listener l = listener;
        if (l != null) l.onFrame(area, share, cx, cy);
    }
}
