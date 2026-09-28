package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Picture-in-picture readout. Layout (never overlapping, text is clipped to its slot):
 *   row 1: BPM (big, left)  |  sound ♫ + movement meter (right)
 *   row 2: C  cap  [readiness bar: level being sent / →next + why]
 *   row 3: O  cap  [readiness bar]
 */
public final class PipView extends View {
    private String bpm = "—";
    private boolean locked = false, sound = false;
    private double rC = 0, rO = 0;
    private String nC = "", nO = "", lvC = "", lvO = "";
    private boolean mRun, mFlash; private double mLevel, mTrig, mRate;
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint();
    private static final int BG = 0xFF0A0C12, TRACK = 0xFF1C2231, TEXT = 0xFFE8EEF8, MUTED = 0xFF8B97AB,
            COYOTE = 0xFFFF3D7F, OPOSSUM = 0xFF00E5FF, GO = 0xFF4DFF88, DIM = 0xFF5A6577;

    public PipView(Context c) { super(c); init(); }
    public PipView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() { pBg.setColor(BG); pText.setTypeface(android.graphics.Typeface.MONOSPACE); }

    public void setMotion(boolean running, double level, double trigger, double rate, boolean flash) {
        mRun = running; mLevel = level; mTrig = trigger; mRate = rate; mFlash = flash;
    }

    public void set(String bpm, boolean locked, boolean sound, double rC, String nC, String lvC, double rO, String nO, String lvO) {
        this.bpm = bpm; this.locked = locked; this.sound = sound; this.rC = rC; this.nC = nC; this.lvC = lvC;
        this.rO = rO; this.nO = nO; this.lvO = lvO;
        invalidate();
    }

    /** Draw text clipped to maxW (with an ellipsis if it does not fit). */
    private void clipped(Canvas c, String t, float x, float y, float maxW) {
        if (pText.measureText(t) <= maxW) { c.drawText(t, x, y, pText); return; }
        String s = t;
        while (s.length() > 1 && pText.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        c.drawText(s + "…", x, y, pText);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, pBg);
        float pad = w * 0.035f;
        float row1 = h * 0.36f, rowH = h * 0.20f;
        float y2 = h * 0.45f, y3 = h * 0.72f;

        // row 1 left: BPM
        pText.setTextSize(h * 0.30f);
        pText.setColor(locked ? OPOSSUM : DIM);
        pText.setFakeBoldText(true);
        String b = bpm + (locked ? "●" : "");
        clipped(c, b, pad, row1, w * 0.5f);
        pText.setFakeBoldText(false);

        // row 1 right: ♫ and the movement meter
        float rx = w - pad;
        pText.setTextSize(h * 0.16f);
        pText.setColor(sound ? GO : DIM);
        float sw = pText.measureText("♫");
        c.drawText(sound ? "♫" : "·", rx - sw, row1 * 0.72f, pText);
        if (mRun) {
            float bx1 = rx - sw - pad, bx0 = Math.max(w * 0.52f, bx1 - w * 0.30f);
            float by0 = h * 0.08f, by1 = h * 0.17f;
            pBar.setColor(TRACK);
            c.drawRect(bx0, by0, bx1, by1, pBar);
            float lv = (float) Math.min(1, Math.log1p(Math.max(0, mLevel)) / Math.log1p(64.0));
            float tg = (float) Math.min(1, Math.log1p(Math.max(0, mTrig)) / Math.log1p(64.0));
            pBar.setColor(mFlash ? TEXT : mLevel >= mTrig ? GO : DIM);
            c.drawRect(bx0, by0, bx0 + (bx1 - bx0) * lv, by1, pBar);
            pBar.setColor(TEXT);
            c.drawRect(bx0 + (bx1 - bx0) * tg - 1, by0 - 2, bx0 + (bx1 - bx0) * tg + 1, by1 + 2, pBar);
            pText.setTextSize(h * 0.10f);
            pText.setColor(MUTED);
            c.drawText(String.format("▦%.1f/s", mRate), bx0, by1 + h * 0.11f, pText);
        }

        drawRow(c, "C", rC, nC, lvC, COYOTE, y2, w, rowH, pad);
        drawRow(c, "O", rO, nO, lvO, OPOSSUM, y3, w, rowH, pad);
    }

    private void drawRow(Canvas c, String tag, double r, String note, String cap, int color, float y, int w, float rowH, float pad) {
        float base = y + rowH * 0.74f;
        pText.setTextSize(rowH * 0.8f);
        pText.setFakeBoldText(true);
        pText.setColor(color);
        c.drawText(tag, pad, base, pText);
        pText.setFakeBoldText(false);
        float x = pad + pText.measureText("C") + pad * 0.6f;
        pText.setTextSize(rowH * 0.55f);
        pText.setColor(MUTED);
        float capW = pText.measureText("200");
        clipped(c, cap, x, base, capW);
        float x0 = x + capW + pad * 0.6f, x1 = w - pad;
        pBar.setColor(TRACK);
        c.drawRoundRect(x0, y, x1, y + rowH, rowH / 2, rowH / 2, pBar);
        pBar.setColor(r >= 1 ? color : (color & 0x00FFFFFF) | 0x66000000);
        float fill = (float) Math.max(0.03, Math.min(1, r));
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * fill, y + rowH, rowH / 2, rowH / 2, pBar);
        pText.setTextSize(rowH * 0.6f);
        pText.setColor(r >= 1 ? BG : TEXT);
        pText.setFakeBoldText(r >= 1);
        clipped(c, note, x0 + rowH * 0.45f, base, x1 - x0 - rowH * 0.9f);
        pText.setFakeBoldText(false);
    }
}
