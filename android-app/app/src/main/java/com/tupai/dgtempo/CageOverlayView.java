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
    public boolean danceMode = false, danceShock = false;  // dance rule on / currently shocking for stillness
    public double stillS = 0, graceS = 3;
    public float cx = -1, cy = -1;
    public android.graphics.Bitmap mask;                  // debug: translucent person mask
    // follow-the-video: expected skeleton (video frame) drawn on the player's body, limbs coloured by match
    public float[] refPose, livePose; public float refAspect = 1f, liveAspect = 1f;
    public float[] segErr; public double segTol = 35; public boolean mirror = true, front = false;
    private final Paint pSkel = new Paint(Paint.ANTI_ALIAS_FLAG), pSkelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        pSkel.setStyle(Paint.Style.STROKE); pSkel.setStrokeCap(Paint.Cap.ROUND);
        pSkelEdge.setStyle(Paint.Style.STROKE); pSkelEdge.setStrokeCap(Paint.Cap.ROUND); pSkelEdge.setColor(0xAA000000);
    }

    private static final float MIN_C = 0.5f;
    private static float lx(float[] lm, int i, float aspect) { return lm[i * 3] * aspect; }
    private static float ly(float[] lm, int i) { return lm[i * 3 + 1]; }
    private static float lc(float[] lm, int i) { return lm[i * 3 + 2]; }

    /**
     * The video dancer's skeleton, moved onto the player: same hip centre, scaled so the torsos are the same
     * length, mirrored when the player copies the screen like a mirror. Limbs: green = matches, red = off,
     * cyan = cannot judge. A thin white skeleton shows what the camera sees of the player.
     */
    private void drawSkeletons(Canvas c, int w, int h) {
        float[] ref = refPose, live = livePose;
        if (ref == null) return;
        float ra = refAspect, la = liveAspect <= 0 ? 1f : liveAspect;
        // torso anchors in "height units" (x already multiplied by the aspect)
        float rhx = (lx(ref, PoseMatch.L_HIP, ra) + lx(ref, PoseMatch.R_HIP, ra)) / 2, rhy = (ly(ref, PoseMatch.L_HIP) + ly(ref, PoseMatch.R_HIP)) / 2;
        float rsx = (lx(ref, PoseMatch.L_SHOULDER, ra) + lx(ref, PoseMatch.R_SHOULDER, ra)) / 2, rsy = (ly(ref, PoseMatch.L_SHOULDER) + ly(ref, PoseMatch.R_SHOULDER)) / 2;
        float rTorso = (float) Math.hypot(rsx - rhx, rsy - rhy);
        if (rTorso < 1e-3 || lc(ref, PoseMatch.L_HIP) < MIN_C || lc(ref, PoseMatch.R_HIP) < MIN_C) return;
        float phx = 0, phy = 0, pTorso = 0;
        boolean onPlayer = live != null && lc(live, PoseMatch.L_HIP) >= MIN_C && lc(live, PoseMatch.R_HIP) >= MIN_C
                && lc(live, PoseMatch.L_SHOULDER) >= MIN_C && lc(live, PoseMatch.R_SHOULDER) >= MIN_C;
        if (onPlayer) {
            phx = (lx(live, PoseMatch.L_HIP, la) + lx(live, PoseMatch.R_HIP, la)) / 2; phy = (ly(live, PoseMatch.L_HIP) + ly(live, PoseMatch.R_HIP)) / 2;
            float psx = (lx(live, PoseMatch.L_SHOULDER, la) + lx(live, PoseMatch.R_SHOULDER, la)) / 2, psy = (ly(live, PoseMatch.L_SHOULDER) + ly(live, PoseMatch.R_SHOULDER)) / 2;
            pTorso = (float) Math.hypot(psx - phx, psy - phy);
            if (pTorso < 1e-3) onPlayer = false;
        }
        if (!onPlayer) { phx = 0.5f * la; phy = 0.55f; pTorso = 0.2f; }      // nobody seen: show it mid-frame
        float s = pTorso / rTorso;
        float flip = mirror ? -1 : 1;
        // ref point -> view pixels
        float[] vx = new float[PoseMatch.N_LM], vy = new float[PoseMatch.N_LM];
        for (int i = 0; i < PoseMatch.N_LM; i++) {
            float qx = phx + flip * s * (lx(ref, i, ra) - rhx), qy = phy + s * (ly(ref, i) - rhy);
            float nx = qx / la; if (front) nx = 1 - nx;
            vx[i] = nx * w; vy[i] = qy * h;
        }
        float lw = Math.max(8f, h * 0.012f);
        pSkel.setStrokeWidth(lw); pSkelEdge.setStrokeWidth(lw + 5f);
        // torso + head in cyan
        int[][] body = {{PoseMatch.L_SHOULDER, PoseMatch.R_SHOULDER}, {PoseMatch.L_HIP, PoseMatch.R_HIP}, {PoseMatch.L_SHOULDER, PoseMatch.L_HIP}, {PoseMatch.R_SHOULDER, PoseMatch.R_HIP}};
        for (int[] b : body) if (lc(ref, b[0]) >= MIN_C && lc(ref, b[1]) >= MIN_C) {
            pSkel.setColor(0xFF00E5FF);
            c.drawLine(vx[b[0]], vy[b[0]], vx[b[1]], vy[b[1]], pSkelEdge); c.drawLine(vx[b[0]], vy[b[0]], vx[b[1]], vy[b[1]], pSkel);
        }
        if (lc(ref, 0) >= MIN_C) { pSkel.setColor(0xFF00E5FF); pSkel.setStyle(Paint.Style.FILL); c.drawCircle(vx[0], vy[0], lw * 1.6f, pSkel); pSkel.setStyle(Paint.Style.STROKE); }
        // limbs coloured by their own match
        float[] se = segErr;
        for (int i = 0; i < PoseMatch.SEG.length; i++) {
            int a = PoseMatch.SEG[i][0], b = PoseMatch.SEG[i][1];
            if (lc(ref, a) < MIN_C || lc(ref, b) < MIN_C) continue;
            // the ref segment shown on the player's left side is the one compared with the player's left limb (mirror handled in segErr)
            int li = mirror ? (i + 4) % PoseMatch.N_FEAT : i;
            float e = se == null || li >= se.length ? Float.NaN : se[li];
            pSkel.setColor(Float.isNaN(e) ? 0xFF00E5FF : e <= segTol ? 0xFF4DFF88 : 0xFFFF2A4A);
            c.drawLine(vx[a], vy[a], vx[b], vy[b], pSkelEdge); c.drawLine(vx[a], vy[a], vx[b], vy[b], pSkel);
            pSkel.setStyle(Paint.Style.FILL); c.drawCircle(vx[b], vy[b], lw * 0.7f, pSkel); pSkel.setStyle(Paint.Style.STROKE);
        }
        // the player's own skeleton, thin and white, so the two can be compared at a glance
        if (live != null) {
            pSkel.setStrokeWidth(Math.max(3f, lw * 0.35f)); pSkel.setColor(0xCCFFFFFF);
            int[][] all = {{11, 12}, {23, 24}, {11, 23}, {12, 24}, {11, 13}, {13, 15}, {12, 14}, {14, 16}, {23, 25}, {25, 27}, {24, 26}, {26, 28}};
            for (int[] p : all) if (lc(live, p[0]) >= MIN_C && lc(live, p[1]) >= MIN_C) {
                float x0 = live[p[0] * 3], y0 = live[p[0] * 3 + 1], x1 = live[p[1] * 3], y1 = live[p[1] * 3 + 1];
                if (front) { x0 = 1 - x0; x1 = 1 - x1; }
                c.drawLine(x0 * w, y0 * h, x1 * w, y1 * h, pSkel);
            }
        }
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

    /** Shrink the paint's text size until `text` fits in maxWidth (never above `maxSize`). */
    private static void fit(Paint p, String text, float maxWidth, float maxSize) {
        float size = maxSize;
        p.setTextSize(size);
        while (size > 10f && p.measureText(text) > maxWidth) { size -= 2f; p.setTextSize(size); }
    }

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
        drawSkeletons(c, w, h);
        String where = !detected ? "NOT DETECTED" : inside ? "INSIDE" : "OUTSIDE";
        String t = !locked ? "DRAW THE CAGE" : state == CageLogic.SHOCK ? String.format("⚡ SHOCK %.0fs", shockLeft) : state == CageLogic.WARNING ? "⚠ " + where : where;
        c.drawText(t, 20, 46, pText);
        if (showMotion && detected) {
            boolean moving = motion >= motionMin;
            // big dancer / keep-dancing cue, top centre: obvious from across the room
            Paint pc = new Paint(Paint.ANTI_ALIAS_FLAG);
            pc.setTextAlign(Paint.Align.CENTER);
            pc.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float bannerW = w * 0.84f, bx0 = (w - bannerW) / 2f, bx1 = bx0 + bannerW;
            if (danceShock) {
                String txt = getContext().getString(R.string.ov_not_dancing);
                pc.setColor(0xDDB3001E);
                c.drawRoundRect(bx0, h * 0.10f, bx1, h * 0.24f, 20, 20, pc);
                pc.setColor(0xFFFFFFFF); fit(pc, txt, bannerW - 32, h * 0.06f);
                c.drawText(txt, w / 2f, h * 0.19f, pc);
            } else if (moving) {
                String txt = getContext().getString(R.string.ov_dancing);
                pc.setTextSize(h * 0.13f);
                c.drawText(((System.currentTimeMillis() / 400) % 2 == 0) ? "💃" : "🕺", w / 2f, h * 0.22f, pc);
                pc.setColor(0xFF4DFF88); pc.setShadowLayer(6f, 0, 0, 0xFF000000); fit(pc, txt, bannerW, h * 0.045f);
                c.drawText(txt, w / 2f, h * 0.27f, pc);
                postInvalidateDelayed(200);
            } else if (danceMode) {
                String txt = getContext().getString(R.string.ov_keep_dancing, stillS, graceS);
                pc.setColor(0xDD7A4F00);
                c.drawRoundRect(bx0, h * 0.10f, bx1, h * 0.24f, 20, 20, pc);
                pc.setColor(0xFFFFE082); fit(pc, txt, bannerW - 32, h * 0.055f);
                c.drawText(txt, w / 2f, h * 0.19f, pc);
                postInvalidateDelayed(200);
            }
            // movement meter top-right: bar with the dancing threshold, badge MOVING / STILL
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
            String badge = getContext().getString(moving ? R.string.ov_moving : R.string.ov_still, motion * 100);
            fit(pText, badge, bw + 8, 26f);
            c.drawText(badge, bx, by + bh + 26, pText);
            pText.setTextSize(34f); pText.setColor(0xFFFFFFFF);
        }
        if (state == CageLogic.SHOCK && inside && detected) {
            // the player is back: say so loudly on screen while the punishment runs out
            Paint ban = new Paint(Paint.ANTI_ALIAS_FLAG);
            ban.setColor(0xDD1B5E20);
            c.drawRoundRect(14, h - 86, w - 14, h - 14, 18, 18, ban);
            pText.setColor(0xFF4DFF88);
            String rt = getContext().getString(R.string.ov_returned, shockLeft);
            fit(pText, rt, w - 60, 34f);
            c.drawText(rt, 30, h - 38, pText);
            pText.setTextSize(34f); pText.setColor(0xFFFFFFFF);
            postInvalidateDelayed(200);
        }
    }
}
