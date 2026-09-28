package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Screen-movement meter: current motion level as a bar (log scale), the trigger level as a marker,
 * a flash on each onset, and the onset rate. Tells the user what the movement trigger is doing.
 */
public final class MotionView extends View {
    private double level, trigger, rate;
    private long onsets;
    private boolean flash, running;
    private final Paint pBg = new Paint(), pBar = new Paint(Paint.ANTI_ALIAS_FLAG), pMark = new Paint(), pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MotionView(Context c) { super(c); init(); }
    public MotionView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() {
        pBg.setColor(0xFF202028); pMark.setColor(0xFFFFFFFF); pMark.setStrokeWidth(3f);
        pText.setColor(0xFFDDDDDD); pText.setTextSize(28f); pText.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void set(boolean running, double level, double trigger, long onsets, double rate, boolean flash) {
        this.running = running; this.level = level; this.trigger = trigger; this.onsets = onsets; this.rate = rate; this.flash = flash;
        invalidate();
    }

    private static float pos(double v) { return (float) Math.min(1, Math.log1p(Math.max(0, v)) / Math.log1p(64.0)); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, pBg);
        float left = 150f;
        pText.setColor(running ? 0xFFDDDDDD : 0xFF777777);
        c.drawText("▦ move", 12, h * 0.62f, pText);
        if (!running) { c.drawText("off", left + 12, h * 0.62f, pText); return; }
        float x1 = w - 12;
        pBar.setColor(flash ? 0xFFFFFFFF : level >= trigger ? 0xFF8BC34A : 0xFF546E7A);
        c.drawRect(left, h * 0.25f, left + (x1 - left) * pos(level), h * 0.75f, pBar);
        float tx = left + (x1 - left) * pos(trigger);
        c.drawLine(tx, h * 0.1f, tx, h * 0.9f, pMark);
        pText.setTextSize(24f);
        String txt = String.format("%.1f  trig %.1f  %d hits  %.1f/s", level, trigger, onsets, rate);
        c.drawText(txt, left + 8, h * 0.95f, pText);
        pText.setTextSize(28f);
    }
}
