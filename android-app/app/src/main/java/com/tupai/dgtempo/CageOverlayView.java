package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draws the cage box over the camera preview and lets the user drag a new one (when unlocked).
 * Coordinates are normalised to the view (the preview uses FIT_CENTER, so the view maps 1:1 to the frame
 * once letterboxing is accounted for by the parent sizing).
 */
public final class CageOverlayView extends View {
    public interface Listener { void onBox(RectF normalized); }
    public RectF box = new RectF(0.2f, 0.1f, 0.8f, 0.9f);
    public boolean locked = false;
    public int state = CageLogic.IDLE;
    public boolean detected = false, inside = true;
    public double shockLeft = 0;
    public double motion = 0, motionMin = 0.15;           // camera-measured movement and the "dancing" threshold
    public boolean showMotion = false;
    public float cx = -1, cy = -1;
    public android.graphics.Bitmap mask;                  // debug: translucent person mask
    private final Paint pMask = new Paint(Paint.FILTER_BITMAP_FLAG);
    public Listener listener;
    public Runnable onTap;                                   // a tap (not a drag) anywhere, locked or not
    private float tapX, tapY; private long tapAt;
    private float sx, sy; private boolean dragging;
    private final Paint pBox = new Paint(Paint.ANTI_ALIAS_FLAG), pFill = new Paint(), pDot = new Paint(Paint.ANTI_ALIAS_FLAG), pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CageOverlayView(Context c) { super(c); init(); }
    public CageOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() {
        pBox.setStyle(Paint.Style.STROKE); pBox.setStrokeWidth(6f);
        pText.setTextSize(34f); pText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); pText.setColor(0xFFFFFFFF);
        pText.setShadowLayer(6f, 0, 0, 0xFF000000);
    }

    // drag modes
    private static final int NONE = 0, DRAW = 1, MOVE = 2, RESIZE = 3;
    private int mode = NONE;
    private boolean dragL, dragT, dragR, dragB;
    private RectF start;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { tapX = e.getX(); tapY = e.getY(); tapAt = System.currentTimeMillis(); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && onTap != null
                && Math.hypot(e.getX() - tapX, e.getY() - tapY) < 24 * getResources().getDisplayMetrics().density
                && System.currentTimeMillis() - tapAt < 400) { onTap.run(); if (locked) return true; }
        if (locked) return true;                          // consume so the tap above can be detected
        float x = e.getX() / getWidth(), y = e.getY() / getHeight();
        float grab = 28f * getResources().getDisplayMetrics().density;        // finger-sized handle zone
        float gx = grab / getWidth(), gy = grab / getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);      // keep the ScrollView out of it
                sx = x; sy = y; start = new RectF(box);
                dragL = Math.abs(x - box.left) < gx && y > box.top - gy && y < box.bottom + gy;
                dragR = Math.abs(x - box.right) < gx && y > box.top - gy && y < box.bottom + gy;
                dragT = Math.abs(y - box.top) < gy && x > box.left - gx && x < box.right + gx;
                dragB = Math.abs(y - box.bottom) < gy && x > box.left - gx && x < box.right + gx;
                if (dragL || dragR || dragT || dragB) mode = RESIZE;
                else if (box.contains(x, y)) mode = MOVE;
                else mode = DRAW;
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                float dx = x - sx, dy = y - sy;
                if (mode == DRAW) {
                    box = new RectF(clamp(Math.min(sx, x)), clamp(Math.min(sy, y)), clamp(Math.max(sx, x)), clamp(Math.max(sy, y)));
                } else if (mode == MOVE) {
                    float w = start.width(), h = start.height();
                    float l = Math.max(0, Math.min(1 - w, start.left + dx)), t = Math.max(0, Math.min(1 - h, start.top + dy));
                    box = new RectF(l, t, l + w, t + h);
                } else {
                    RectF r = new RectF(start);
                    if (dragL) r.left = clamp(start.left + dx);
                    if (dragR) r.right = clamp(start.right + dx);
                    if (dragT) r.top = clamp(start.top + dy);
                    if (dragB) r.bottom = clamp(start.bottom + dy);
                    if (r.right - r.left < 0.05f) { if (dragL) r.left = r.right - 0.05f; else r.right = r.left + 0.05f; }
                    if (r.bottom - r.top < 0.05f) { if (dragT) r.top = r.bottom - 0.05f; else r.bottom = r.top + 0.05f; }
                    box = r;
                }
                if (e.getActionMasked() != MotionEvent.ACTION_MOVE) {
                    dragging = false; mode = NONE;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (box.width() < 0.05f || box.height() < 0.05f) box = new RectF(0.2f, 0.1f, 0.8f, 0.9f);
                    if (listener != null) listener.onBox(box);
                }
                invalidate();
                return true;
        }
        return false;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        android.graphics.Bitmap m = mask;
        if (m != null) c.drawBitmap(m, null, new RectF(0, 0, w, h), pMask);
        // box colour = where the player IS (green inside / red outside / amber in the warning), label = what is happening
        int col = !locked ? 0xFF00E5FF : state == CageLogic.SHOCK ? 0xFFFF2A4A : state == CageLogic.WARNING ? 0xFFFFB300 : !detected ? 0xFF8B97AB : inside ? 0xFF4DFF88 : 0xFFFF2A4A;
        pBox.setColor(col);
        pFill.setColor((col & 0x00FFFFFF) | 0x22000000);
        RectF r = new RectF(box.left * w, box.top * h, box.right * w, box.bottom * h);
        c.drawRect(r, pFill);
        c.drawRect(r, pBox);
        if (!locked) {                                            // corner + edge handles while editing
            pDot.setColor(col);
            float hs = 12f;
            float[] hx = {r.left, r.right, r.left, r.right, r.centerX(), r.centerX(), r.left, r.right};
            float[] hy = {r.top, r.top, r.bottom, r.bottom, r.top, r.bottom, r.centerY(), r.centerY()};
            for (int i = 0; i < hx.length; i++) c.drawCircle(hx[i], hy[i], hs, pDot);
        }
        if (cx >= 0) {
            pDot.setColor(detected ? col : 0xFF8B97AB);
            c.drawCircle(cx * w, cy * h, 14f, pDot);
        }
        String where = !detected ? "NOT DETECTED" : inside ? "INSIDE" : "OUTSIDE";
        String t = !locked ? "DRAW THE CAGE" : state == CageLogic.SHOCK ? String.format("⚡ SHOCK %.0fs", shockLeft) : state == CageLogic.WARNING ? "⚠ " + where : where;
        c.drawText(t, 20, 46, pText);
        if (showMotion && detected) {
            // movement meter top-right: bar with the dancing threshold, badge MOVING / STILL
            boolean moving = motion >= motionMin;
            float bw = w * 0.32f, bh = 18f, bx = w - 20 - bw, by = 24;
            Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG);
            pb.setColor(0xAA000000); c.drawRoundRect(bx - 8, by - 8, bx + bw + 8, by + bh + 36, 10, 10, pb);
            pb.setColor(0xFF1C2231); c.drawRoundRect(bx, by, bx + bw, by + bh, 9, 9, pb);
            pb.setColor(moving ? 0xFF4DFF88 : 0xFFFFB300);
            float lv = (float) Math.min(1, motion / 0.6);                       // 60% silhouette change = full bar
            c.drawRoundRect(bx, by, bx + bw * Math.max(0.03f, lv), by + bh, 9, 9, pb);
            pb.setColor(0xFFFFFFFF);
            float tx = bx + bw * (float) Math.min(1, motionMin / 0.6);
            c.drawRect(tx - 2, by - 4, tx + 2, by + bh + 4, pb);
            pText.setTextSize(26f);
            pText.setColor(moving ? 0xFF4DFF88 : 0xFFFFB300);
            c.drawText((moving ? "● MOVING " : "○ STILL ") + String.format("%.0f%%", motion * 100), bx, by + bh + 26, pText);
            pText.setTextSize(34f); pText.setColor(0xFFFFFFFF);
        }
        if (state == CageLogic.SHOCK && inside && detected) {
            // the player is back: say so loudly on screen while the punishment runs out
            Paint ban = new Paint(Paint.ANTI_ALIAS_FLAG);
            ban.setColor(0xDD1B5E20);
            c.drawRoundRect(14, h - 86, w - 14, h - 14, 18, 18, ban);
            pText.setColor(0xFF4DFF88);
            c.drawText("✓ RETURNED · shock continues " + String.format("%.0fs", shockLeft), 30, h - 38, pText);
            pText.setColor(0xFFFFFFFF);
            postInvalidateDelayed(200);
        }
    }
}
