package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Live gyroscope readout: tilt and movement bars, then the resulting drive per device. */
public final class MotionReadoutView extends View {
    private double tilt, tiltF, move, moveF, dC, dO;
    private int sC, sO;
    private boolean running;
    private final Paint pBg = new Paint(), pBar = new Paint(Paint.ANTI_ALIAS_FLAG), pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MotionReadoutView(Context c) { super(c); init(); }
    public MotionReadoutView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() { pBg.setColor(0xFF0E1118); pText.setTypeface(android.graphics.Typeface.MONOSPACE); }

    public void set(boolean running, double tiltDeg, double tiltF, double move, double moveF, double dC, int sC, double dO, int sO) {
        this.running = running; this.tilt = tiltDeg; this.tiltF = tiltF; this.move = move; this.moveF = moveF;
        this.dC = dC; this.sC = sC; this.dO = dO; this.sO = sO;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRoundRect(0, 0, w, h, 24, 24, pBg);
        if (!running) {
            pText.setTextSize(h * 0.14f); pText.setColor(0xFF5A6577);
            c.drawText("sensors off", 24, h * 0.55f, pText);
            return;
        }
        row(c, 0, "TILT", String.format("%3.0f°", tilt), tiltF, 0xFF00E5FF, w, h);
        row(c, 1, "MOVE", String.format("%4.1f", move), moveF, 0xFF00E5FF, w, h);
        row(c, 2, "C", sC > 0 ? String.valueOf(sC) : "off", dC, 0xFFFF3D7F, w, h);
        row(c, 3, "O", sO > 0 ? String.valueOf(sO) : "off", dO, 0xFF00E5FF, w, h);
    }

    private void row(Canvas c, int i, String tag, String val, double f, int color, int w, int h) {
        float rowH = h / 4.6f, y = 14 + i * rowH, barH = rowH * 0.55f;
        pText.setTextSize(rowH * 0.5f);
        pText.setColor(0xFFE8EEF8);
        c.drawText(tag, 24, y + barH * 0.85f, pText);
        float x0 = 24 + pText.measureText("TILT ") + 8, x1 = w - 24 - pText.measureText("100°") - 12;
        pBar.setColor(0xFF1C2231);
        c.drawRoundRect(x0, y, x1, y + barH, barH / 2, barH / 2, pBar);
        pBar.setColor(f >= 0.05 ? color : 0xFF3A4150);
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * (float) Math.max(0.02, Math.min(1, f)), y + barH, barH / 2, barH / 2, pBar);
        pText.setColor(0xFF8B97AB);
        c.drawText(val, x1 + 12, y + barH * 0.85f, pText);
    }
}
