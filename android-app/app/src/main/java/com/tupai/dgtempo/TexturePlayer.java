package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;

/**
 * Looping video player on a TextureView, so it composes like any other view and can sit ON TOP of the camera
 * preview (a VideoView is a SurfaceView and ends up behind it). Playback and audio continue while the view is
 * hidden or re-parented; only the picture pauses.
 */
public final class TexturePlayer extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared = false, wantPlay = false;
    public volatile int videoW = 0, videoH = 0;
    public Runnable onPrepared;
    public MediaPlayer.OnErrorListener onError;

    public TexturePlayer(Context c) { super(c); setSurfaceTextureListener(this); }

    public void setSource(Uri u) {
        release();
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setDataSource(getContext(), u);
            p.setLooping(true);
            p.setOnVideoSizeChangedListener((m, w, h) -> { videoW = w; videoH = h; post(this::requestLayout); });
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                prepared = true;
                videoW = m.getVideoWidth(); videoH = m.getVideoHeight();
                if (surface != null) m.setSurface(surface);
                if (wantPlay) m.start();
                requestLayout();
                if (onPrepared != null) onPrepared.run();
            });
            p.setOnErrorListener((m, what, extra) -> onError == null || onError.onError(m, what, extra));
            p.prepareAsync();
        } catch (Exception e) {
            if (onError != null) onError.onError(mp, -1, 0);
            release();
        }
    }

    public void start() { wantPlay = true; if (prepared && mp != null) try { mp.start(); } catch (Exception ignored) {} }
    public void pause() { wantPlay = false; if (prepared && mp != null) try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) {} }
    public boolean isPlaying() { try { return prepared && mp != null && mp.isPlaying(); } catch (Exception e) { return false; } }
    public int getCurrentPosition() { try { return prepared && mp != null ? mp.getCurrentPosition() : -1; } catch (Exception e) { return -1; } }
    public void stopPlayback() { release(); }
    public float aspect() { return videoW > 0 && videoH > 0 ? videoW / (float) videoH : 0; }

    private void release() {
        prepared = false;
        MediaPlayer p = mp; mp = null;
        if (p != null) try { p.stop(); } catch (Exception ignored) {} finally { try { p.release(); } catch (Exception ignored) {} }
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = getDefaultSize(videoW, ws), h = getDefaultSize(videoH, hs);
        if (videoW > 0 && videoH > 0) {                    // keep the picture's aspect inside what the parent allows
            int wm = MeasureSpec.getMode(ws), hm = MeasureSpec.getMode(hs);
            int ww = MeasureSpec.getSize(ws), hh = MeasureSpec.getSize(hs);
            if (wm == MeasureSpec.EXACTLY && hm == MeasureSpec.EXACTLY) { w = ww; h = hh; }
            else if (wm == MeasureSpec.EXACTLY) { w = ww; h = w * videoH / videoW; if (hm == MeasureSpec.AT_MOST && h > hh) { h = hh; w = h * videoW / videoH; } }
            else if (hm == MeasureSpec.EXACTLY) { h = hh; w = h * videoW / videoH; if (wm == MeasureSpec.AT_MOST && w > ww) { w = ww; h = w * videoH / videoW; } }
            else { w = videoW; h = videoH; if (wm == MeasureSpec.AT_MOST && w > ww) { w = ww; h = w * videoH / videoW; } if (hm == MeasureSpec.AT_MOST && h > hh) { h = hh; w = h * videoW / videoH; } }
        }
        setMeasuredDimension(w, h);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (mp != null && prepared) try { mp.setSurface(surface); } catch (Exception ignored) {}
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (mp != null) try { mp.setSurface(null); } catch (Exception ignored) {}     // sound and position go on
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
