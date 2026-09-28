package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Scrolling strip of one device's output: each bar is a 25 ms slot, height = slot intensity x strength
 * (0..1 of the device cap). A thin line shows the current strength level. Newest on the right.
 */
public final class OutputView extends View {
    private float[] hist = new float[0];
    private int pos = 0;
    private float strengthNorm = 0;
    private String label = "";
    private String value = "";
    private int color = 0xFFFF7A00;
    private boolean enabled = false;
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint();

    public OutputView(Context c) { super(c); init(); }
    public OutputView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBg.setColor(0xFF202028);
        pLine.setStrokeWidth(3f);
        pText.setColor(0xFFDDDDDD);
        pText.setTextSize(30f);
        pText.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void setData(float[] hist, int pos, float strengthNorm, String label, String value, int color, boolean enabled) {
        this.hist = hist; this.pos = pos; this.strengthNorm = strengthNorm;
        this.label = label; this.value = value; this.color = color; this.enabled = enabled;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, pBg);
        float left = 150f;
        pText.setColor(enabled ? 0xFFDDDDDD : 0xFF777777);
        c.drawText(label, 12, h * 0.42f, pText);
        pText.setTextSize(24f);
        c.drawText(value, 12, h * 0.85f, pText);
        pText.setTextSize(30f);
        int n = hist.length;
        if (n == 0) return;
        float bw = (w - left) / n;
        pBar.setColor(enabled ? color : 0xFF555555);
        for (int i = 0; i < n; i++) {
            float v = hist[(pos + i) % n];
            if (v <= 0) continue;
            float x = left + i * bw;
            float top = h - v * (h - 4);
            c.drawRect(x, top, x + Math.max(1f, bw - 1f), h, pBar);
        }
        pLine.setColor(enabled ? (color & 0x00FFFFFF) | 0x99000000 : 0x66555555);
        float y = h - strengthNorm * (h - 4);
        c.drawLine(left, y, w, y, pLine);
        pLine.setColor(0xFF333340);
        c.drawLine(left, 0, left, h, pLine);
    }
}
