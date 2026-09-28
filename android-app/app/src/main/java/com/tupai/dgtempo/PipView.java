package com.tupai.dgtempo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Compact picture-in-picture readout: BPM, and per device how close it is to firing. */
public final class PipView extends View {
    private String bpm = "—";
    private boolean locked = false, sound = false;
    private double rC = 0, rO = 0;
    private String nC = "", nO = "";
    private String lvC = "", lvO = "";
    private boolean mRun, mFlash; private double mLevel, mTrig, mRate;

    public void setMotion(boolean running, double level, double trigger, double rate, boolean flash) {
        mRun = running; mLevel = level; mTrig = trigger; mRate = rate; mFlash = flash;
    }
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint();

    public PipView(Context c) { super(c); init(); }
    public PipView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() { pBg.setColor(0xFF101014); pText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); }

    public void set(String bpm, boolean locked, boolean sound, double rC, String nC, String lvC, double rO, String nO, String lvO) {
        this.bpm = bpm; this.locked = locked; this.sound = sound; this.rC = rC; this.nC = nC; this.lvC = lvC;
        this.rO = rO; this.nO = nO; this.lvO = lvO;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, pBg);
        float pad = w * 0.04f;
        pText.setColor(locked ? 0xFFFF7A00 : 0xFF888888);
        pText.setTextSize(h * 0.36f);
        c.drawText(bpm, pad, h * 0.40f, pText);
        pText.setTextSize(h * 0.14f);
        pText.setColor(sound ? 0xFF8BC34A : 0xFF555555);
        c.drawText(sound ? "♫" : "·", w - pad - pText.measureText("♫"), h * 0.22f, pText);
        if (mRun) {   // movement meter top-right: bar (log scale) with trigger marker, rate text
            float bx1 = w - pad - pText.measureText("♫") - pad, bx0 = bx1 - w * 0.38f, by0 = h * 0.08f, by1 = h * 0.20f;
            pBar.setColor(0xFF2A2A34);
            c.drawRect(bx0, by0, bx1, by1, pBar);
            float lv = (float) Math.min(1, Math.log1p(Math.max(0, mLevel)) / Math.log1p(64.0));
            float tg = (float) Math.min(1, Math.log1p(Math.max(0, mTrig)) / Math.log1p(64.0));
            pBar.setColor(mFlash ? 0xFFFFFFFF : mLevel >= mTrig ? 0xFF8BC34A : 0xFF546E7A);
            c.drawRect(bx0, by0, bx0 + (bx1 - bx0) * lv, by1, pBar);
            pBar.setColor(0xFFFFFFFF);
            c.drawRect(bx0 + (bx1 - bx0) * tg - 1, by0 - 2, bx0 + (bx1 - bx0) * tg + 1, by1 + 2, pBar);
            pText.setTextSize(h * 0.10f);
            pText.setColor(0xFFBBBBBB);
            c.drawText(String.format("▦ %.1f/s", mRate), bx0, by1 + h * 0.11f, pText);
        }
        drawRow(c, "C", rC, nC, lvC, 0xFFFF7A00, h * 0.48f, w, h, pad);
        drawRow(c, "O", rO, nO, lvO, 0xFF4FC3F7, h * 0.75f, w, h, pad);
    }

    /** One device row: tag, "A nn B nn /cap" level text, then the readiness bar. */
    private void drawRow(Canvas c, String tag, double r, String note, String level, int color, float y, int w, int h, float pad) {
        float rowH = h * 0.18f;
        pText.setTextSize(rowH * 0.9f);
        pText.setColor(color);
        c.drawText(tag, pad, y + rowH * 0.8f, pText);
        pText.setTextSize(rowH * 0.62f);
        pText.setColor(0xFFDDDDDD);
        float lvW = pText.measureText("200");
        c.drawText(level, pad + rowH * 1.1f, y + rowH * 0.75f, pText);
        float x0 = pad + rowH * 1.1f + lvW + rowH * 0.3f, x1 = w - pad;
        pBar.setColor(0xFF2A2A34);
        c.drawRoundRect(x0, y, x1, y + rowH, rowH / 2, rowH / 2, pBar);
        pBar.setColor(r >= 1 ? color : (color & 0x00FFFFFF) | 0x88000000);
        float fill = (float) Math.max(0.02, Math.min(1, r));
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * fill, y + rowH, rowH / 2, rowH / 2, pBar);
        pText.setTextSize(rowH * 0.7f);
        pText.setColor(0xFFFFFFFF);
        c.drawText(note, x0 + rowH * 0.4f, y + rowH * 0.75f, pText);
    }
}
