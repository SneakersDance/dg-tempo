package com.tupai.dgtempo;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;

public class PoseMatchTest {
    /** A stick figure facing the camera: shoulders at y=0.3, hips at 0.5, limbs given as (dx, dy) per segment. */
    static float[] figure(float lArmUpX, float lArmUpY, float lForeX, float lForeY, float rArmUpX, float rArmUpY, float rForeX, float rForeY) {
        float[] lm = new float[PoseMatch.N_LM * 3];
        set(lm, PoseMatch.L_SHOULDER, 0.6f, 0.3f); set(lm, PoseMatch.R_SHOULDER, 0.4f, 0.3f);
        set(lm, PoseMatch.L_HIP, 0.58f, 0.5f); set(lm, PoseMatch.R_HIP, 0.42f, 0.5f);
        set(lm, PoseMatch.L_ELBOW, 0.6f + lArmUpX, 0.3f + lArmUpY); set(lm, PoseMatch.L_WRIST, 0.6f + lArmUpX + lForeX, 0.3f + lArmUpY + lForeY);
        set(lm, PoseMatch.R_ELBOW, 0.4f + rArmUpX, 0.3f + rArmUpY); set(lm, PoseMatch.R_WRIST, 0.4f + rArmUpX + rForeX, 0.3f + rArmUpY + rForeY);
        set(lm, PoseMatch.L_KNEE, 0.58f, 0.7f); set(lm, PoseMatch.L_ANKLE, 0.58f, 0.9f);
        set(lm, PoseMatch.R_KNEE, 0.42f, 0.7f); set(lm, PoseMatch.R_ANKLE, 0.42f, 0.9f);
        return lm;
    }
    static void set(float[] lm, int i, float x, float y) { lm[i * 3] = x; lm[i * 3 + 1] = y; lm[i * 3 + 2] = 0.95f; }

    static final float[] ARMS_DOWN = figure(0.02f, 0.12f, 0.02f, 0.12f, -0.02f, 0.12f, -0.02f, 0.12f);
    static final float[] LEFT_UP = figure(0.02f, -0.12f, 0.02f, -0.12f, -0.02f, 0.12f, -0.02f, 0.12f);
    static final float[] RIGHT_UP = figure(0.02f, 0.12f, 0.02f, 0.12f, -0.02f, -0.12f, -0.02f, -0.12f);

    @Test public void samePoseMatchesDifferentPoseDoesNot() {
        float[] a = PoseMatch.features(ARMS_DOWN, 1f), b = PoseMatch.features(LEFT_UP, 1f);
        assertEquals(8, PoseMatch.validCount(a));
        assertEquals(0, PoseMatch.error(a, a, false), 1e-6);
        double e = PoseMatch.error(a, b, false);
        assertTrue("arm up vs down should differ a lot: " + e, e > 30);
        assertEquals(100, PoseMatch.matchPct(0)); assertEquals(0, PoseMatch.matchPct(90)); assertEquals(-1, PoseMatch.matchPct(Double.NaN));
    }

    @Test public void scaleShiftAndAspectDoNotMatter() {
        float[] big = new float[ARMS_DOWN.length];
        for (int i = 0; i < PoseMatch.N_LM; i++) { big[i * 3] = ARMS_DOWN[i * 3] * 2.5f + 3; big[i * 3 + 1] = ARMS_DOWN[i * 3 + 1] * 2.5f - 1; big[i * 3 + 2] = 0.9f; }
        assertEquals(0, PoseMatch.error(PoseMatch.features(ARMS_DOWN, 1f), PoseMatch.features(big, 1f), false), 1e-4);
        // a 16:9 frame stores x squeezed: with the aspect passed in, the angles come out the same
        float[] wide = new float[ARMS_DOWN.length];
        for (int i = 0; i < PoseMatch.N_LM; i++) { wide[i * 3] = ARMS_DOWN[i * 3] / 1.777f; wide[i * 3 + 1] = ARMS_DOWN[i * 3 + 1]; wide[i * 3 + 2] = 0.9f; }
        assertEquals(0, PoseMatch.error(PoseMatch.features(ARMS_DOWN, 1f), PoseMatch.features(wide, 1.777f), false), 0.05);
    }

    @Test public void mirrorSwapsSides() {
        float[] l = PoseMatch.features(LEFT_UP, 1f), r = PoseMatch.features(RIGHT_UP, 1f);
        assertTrue(PoseMatch.error(l, r, false) > 30);                 // straight: left-up vs right-up differ
        assertEquals(0, PoseMatch.error(l, r, true), 0.5);             // mirrored: they are the same move
    }

    @Test public void unsureJointsAreSkippedAndTooFewIsNoJudgement() {
        float[] lm = ARMS_DOWN.clone();
        lm[PoseMatch.L_WRIST * 3 + 2] = 0.1f;                           // left wrist hidden
        float[] f = PoseMatch.features(lm, 1f);
        assertEquals(7, PoseMatch.validCount(f));
        assertTrue(Float.isNaN(f[1]));
        for (int i : new int[]{PoseMatch.L_ELBOW, PoseMatch.R_ELBOW, PoseMatch.L_KNEE, PoseMatch.R_KNEE, PoseMatch.L_WRIST}) lm[i * 3 + 2] = 0.1f;
        assertTrue(Double.isNaN(PoseMatch.error(PoseMatch.features(lm, 1f), f, false)));
        assertTrue(Double.isNaN(PoseMatch.error(PoseMatch.features(new float[0], 1f), f, false)));
    }

    @Test public void trackFindsBestFrameInWindowAndRoundTrips() throws Exception {
        PoseTrack t = new PoseTrack();
        t.durationMs = 3000;
        for (int ms = 0; ms <= 3000; ms += 125) t.add(ms, PoseMatch.features(ms >= 1000 && ms < 2000 ? LEFT_UP : ARMS_DOWN, 1f), ms % 250 == 0 ? LEFT_UP : null);
        float[] live = PoseMatch.features(LEFT_UP, 1f);
        assertEquals(0, t.bestError(live, 1500, 500, false), 1e-4);     // inside the left-up second
        assertTrue(t.bestError(live, 2600, 500, false) > 30);            // well after it: no match in the window
        assertEquals(0, t.bestError(live, 2300, 500, false), 1e-4);     // 0.4 s late still counts (last left-up frame at 1875 ms)
        assertTrue(Double.isNaN(t.bestError(live, 9000, 500, false)));
        assertEquals(0, t.nearest(-50)); assertEquals(8, t.nearest(1000)); assertEquals(9, t.nearest(1130)); assertEquals(24, t.nearest(9000));
        float[] se = PoseMatch.segmentErrors(PoseMatch.features(LEFT_UP, 1f), PoseMatch.features(RIGHT_UP, 1f), true);
        for (float v : se) assertEquals(0, v, 0.5);                      // mirrored: every limb matches
        se = PoseMatch.segmentErrors(PoseMatch.features(LEFT_UP, 1f), PoseMatch.features(ARMS_DOWN, 1f), false);
        assertTrue(se[0] > 100 && se[1] > 100 && se[2] < 1 && se[4] < 1);   // only the left arm is off
        File f = File.createTempFile("pose", ".bin"); f.deleteOnExit();
        t.save(f);
        PoseTrack u = PoseTrack.load(f);
        assertEquals(t.size(), u.size()); assertEquals(t.durationMs, u.durationMs); assertEquals(t.judged(), u.judged());
        assertNotNull(u.lms.get(0)); assertNull(u.lms.get(1)); assertEquals(LEFT_UP[PoseMatch.L_WRIST * 3 + 1], u.lms.get(0)[PoseMatch.L_WRIST * 3 + 1], 1e-6);
        assertEquals(0, u.bestError(live, 1500, 500, false), 1e-4);
    }

    @Test public void followRuleShocksWhenOffPoseAndStopsWhenMatching() {
        CageLogic g = new CageLogic();
        CageLogic.Config c = new CageLogic.Config();
        c.locked = true; c.follow = true; c.followGraceS = 2; c.followMaxErr = 35;
        double t = 0; CageLogic.Out o = null;
        for (int i = 0; i < 15; i++) { o = g.step(t, c, true, 0.2, 0.0, 0.3, 10); t += 0.1; }    // matching
        assertFalse(o.danceShock); assertFalse(o.followShock);
        for (int i = 0; i < 15; i++) { o = g.step(t, c, true, 0.2, 0.0, 0.3, 60); t += 0.1; }    // off-pose 1.5 s: not yet
        assertFalse(o.followShock); assertTrue(o.offS > 1.0);
        String ann = null;
        for (int i = 0; i < 8; i++) { o = g.step(t, c, true, 0.2, 0.0, 0.3, 60); t += 0.1; if (o.announce != null) ann = o.announce; }
        assertTrue(o.followShock); assertTrue(o.danceShock); assertEquals("move", ann);
        o = g.step(t, c, true, 0.2, 0.0, 0.3, 20); t += 0.1;                                      // matches again: stops at once
        assertFalse(o.followShock); assertFalse(o.danceShock);
        for (int i = 0; i < 40; i++) { o = g.step(t, c, true, 0.2, 0.0, 0.3, Double.NaN); t += 0.1; }   // nothing to judge: never shocks
        assertFalse(o.followShock);
    }
}
