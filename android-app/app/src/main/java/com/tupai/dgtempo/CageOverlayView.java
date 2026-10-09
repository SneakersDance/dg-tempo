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
    public float cx = -1, cy = -1;
    public Listener listener;
    private float sx, sy; private boolean dragging;
    private final Paint pBox = new Paint(Paint.ANTI_ALIAS_FLAG), pFill = new Paint(), pDot = new Paint(Paint.ANTI_ALIAS_FLAG), pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CageOverlayView(Context c) { super(c); init(); }
    public CageOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() {
        pBox.setStyle(Paint.Style.STROKE); pBox.setStrokeWidth(6f);
        pText.setTextSize(34f); pText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); pText.setColor(0xFFFFFFFF);
        pText.setShadowLayer(6f, 0, 0, 0xFF000000);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (locked) return false;
        float x = e.getX() / getWidth(), y = e.getY() / getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: sx = x; sy = y; dragging = true; return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                box = new RectF(clamp(Math.min(sx, x)), clamp(Math.min(sy, y)), clamp(Math.max(sx, x)), clamp(Math.max(sy, y)));
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    dragging = false;
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
        int col = !locked ? 0xFF00E5FF : state == CageLogic.SHOCK ? 0xFFFF2A4A : state == CageLogic.WARNING ? 0xFFFFB300 : inside ? 0xFF4DFF88 : 0xFFFF2A4A;
        pBox.setColor(col);
        pFill.setColor((col & 0x00FFFFFF) | 0x22000000);
        RectF r = new RectF(box.left * w, box.top * h, box.right * w, box.bottom * h);
        c.drawRect(r, pFill);
        c.drawRect(r, pBox);
        if (cx >= 0) {
            pDot.setColor(detected ? col : 0xFF8B97AB);
            c.drawCircle(cx * w, cy * h, 14f, pDot);
        }
        String t = !locked ? "DRAW THE CAGE" : state == CageLogic.SHOCK ? "⚡ SHOCK" : state == CageLogic.WARNING ? "⚠ OUTSIDE" : detected ? (inside ? "INSIDE" : "OUTSIDE") : "NOT DETECTED";
        c.drawText(t, 20, 46, pText);
    }
}
