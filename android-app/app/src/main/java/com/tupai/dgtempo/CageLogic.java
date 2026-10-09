package com.tupai.dgtempo;

/**
 * Chalk Cage game rules, pure and unit-tested. Fed ~10x per second with what the camera saw;
 * decides warning / shock / vibration and which announcements to make.
 */
public final class CageLogic {
    public static final int IDLE = 0, INSIDE = 1, WARNING = 2, SHOCK = 3;
    public static final int SHOCK_FULL = 0, SHOCK_STOP_EARLY = 1, SHOCK_UNTIL_RETURN = 2;
    public static final int VIB_OFF = 0, VIB_ALWAYS = 1, VIB_INSIDE = 2, VIB_OUTSIDE = 3;

    public static final class Config {
        public double warnS = 3, shockS = 30;
        public int shockMode = SHOCK_FULL;
        public int vibMode = VIB_INSIDE;
        public double outsideShare = 0.20;       // body share outside the box that counts as outside
        public double minArea = 0.02;            // mask area share below which nobody is detected
        public boolean notDetectedIsOutside = true;
        public boolean paused = false;
        public boolean locked = false;           // box drawn and locked: the game is live
    }

    public static final class Out {
        public int state = IDLE;
        public boolean shock, vib, inside, detected;
        public double warnLeft, shockLeft;
        public String announce;                  // "outside" / "returned" / null
    }

    private int state = IDLE;
    private double warnStart = 0, shockUntil = 0;
    private final boolean[] ring = new boolean[10];   // last ~1 s of outside/inside votes
    private int ringN = 0, ringPos = 0;
    private boolean wasOutside = false;

    public void reset() { state = IDLE; ringN = ringPos = 0; wasOutside = false; }

    /**
     * @param area    person mask area as share of the frame (0..1)
     * @param outside share of the person mask outside the box (0..1), meaningless when area is tiny
     */
    public Out step(double now, Config c, boolean armed, double area, double outside) {
        Out o = new Out();
        o.detected = area >= c.minArea;
        boolean rawOutside = o.detected ? outside >= c.outsideShare : c.notDetectedIsOutside;
        ring[ringPos] = rawOutside; ringPos = (ringPos + 1) % ring.length; if (ringN < ring.length) ringN++;
        int votes = 0; for (int i = 0; i < ringN; i++) if (ring[i]) votes++;
        boolean isOutside = ringN >= 3 && votes * 2 > ringN;     // majority of the last second
        o.inside = !isOutside;

        if (!armed || !c.locked || c.paused) {
            if (state != IDLE && !isOutside && wasOutside) o.announce = "returned";
            state = IDLE; wasOutside = false;
            o.state = IDLE; o.shock = false; o.vib = false;
            return o;
        }

        switch (state) {
            case IDLE:
            case INSIDE:
                if (isOutside) { state = WARNING; warnStart = now; o.announce = "outside"; }
                else state = INSIDE;
                break;
            case WARNING:
                if (!isOutside) { state = INSIDE; o.announce = "returned"; }
                else if (now - warnStart >= c.warnS) { state = SHOCK; shockUntil = now + c.shockS; }
                break;
            case SHOCK:
                boolean over = now >= shockUntil;
                if (c.shockMode == SHOCK_STOP_EARLY && !isOutside) over = true;
                if (c.shockMode == SHOCK_UNTIL_RETURN && isOutside) over = false;
                if (over) {
                    if (isOutside) { state = WARNING; warnStart = now; o.announce = "outside"; }
                    else { state = INSIDE; o.announce = "returned"; }
                }
                break;
        }
        if (state != SHOCK && state != WARNING && wasOutside && !isOutside && o.announce == null) o.announce = "returned";
        wasOutside = isOutside;

        o.state = state;
        o.shock = state == SHOCK;
        o.warnLeft = state == WARNING ? Math.max(0, c.warnS - (now - warnStart)) : 0;
        o.shockLeft = state == SHOCK ? Math.max(0, shockUntil - now) : 0;
        switch (c.vibMode) {
            case VIB_ALWAYS: o.vib = true; break;
            case VIB_INSIDE: o.vib = !isOutside; break;
            case VIB_OUTSIDE: o.vib = isOutside; break;
            default: o.vib = false;
        }
        return o;
    }
}
