package com.tupai.dgtempo;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, persisted. All strength values are device units (0-200). */
public final class Settings {
    /**
     * Output-shaping dials of ONE channel (Coyote A/B electrodes, Opossum A/B motors). Trigger logic
     * (sensitivity, rate, tempo range, timer, any-music) stays per device and drives both channels.
     */
    public static final class ChannelCfg {
        public int min, max;            // strength at slow tempo / at fast tempo (+ hard cap for the Coyote)
        public int manual;              // Opossum: fixed strength when not following tempo
        public int intensity = 100;     // waveform intensity 0-100
        public int freq = 30;           // Coyote simple-pulse frequency byte 10-240
        public int burstMs;             // pulse length
        public String wave;             // waveform id (SIMPLE or a library id)
        public boolean continuous = false;
        public boolean randomLevel = false;
        public String gyroWave = "";    // waveform used in gyro mode; "" = same as the music waveform
        public String waveFor(boolean gyro) { return gyro && !gyroWave.isEmpty() ? gyroWave : wave; }

        ChannelCfg(int min, int max, int manual, int burstMs, String wave, boolean randomLevel) {
            this.min = min; this.max = max; this.manual = manual; this.burstMs = burstMs; this.wave = wave; this.randomLevel = randomLevel;
        }
        void copyFrom(ChannelCfg o) {
            min = o.min; max = o.max; manual = o.manual; intensity = o.intensity; freq = o.freq; burstMs = o.burstMs;
            wave = o.wave; continuous = o.continuous; randomLevel = o.randomLevel; gyroWave = o.gyroWave;
        }
        void load(SharedPreferences p, String sfx, ChannelCfg fallback) {
            min = p.getInt("coyoteMin" + sfx, fallback.min); max = p.getInt("coyoteMax" + sfx, fallback.max);
            manual = p.getInt("manual" + sfx, fallback.manual); intensity = p.getInt("intensity" + sfx, fallback.intensity);
            freq = p.getInt("freq" + sfx, fallback.freq); burstMs = p.getInt("burstMs" + sfx, fallback.burstMs);
            wave = p.getString("wave" + sfx, fallback.wave); continuous = p.getBoolean("continuous" + sfx, fallback.continuous);
            randomLevel = p.getBoolean("randomLevel" + sfx, fallback.randomLevel);
            gyroWave = p.getString("gyroWave" + sfx, fallback.gyroWave);
        }
        void save(SharedPreferences.Editor e, String sfx) {
            e.putInt("coyoteMin" + sfx, min).putInt("coyoteMax" + sfx, max).putInt("manual" + sfx, manual)
             .putInt("intensity" + sfx, intensity).putInt("freq" + sfx, freq).putInt("burstMs" + sfx, burstMs)
             .putString("wave" + sfx, wave).putBoolean("continuous" + sfx, continuous).putBoolean("randomLevel" + sfx, randomLevel)
             .putString("gyroWave" + sfx, gyroWave);
        }
    }

    // Coyote channels A / B, Opossum motors A / B. Linked (default) = B mirrors A.
    public final ChannelCfg cA = new ChannelCfg(1, 2, 0, 150, "PULSATING", true);
    public final ChannelCfg cB = new ChannelCfg(1, 2, 0, 150, "PULSATING", true);
    public final ChannelCfg oA = new ChannelCfg(200, 200, 200, 3000, "BEAT", false);
    public final ChannelCfg oB = new ChannelCfg(200, 200, 200, 3000, "BEAT", false);
    public boolean coyoteLink = true, vibLink = true;

    // ---- input mode: 0 = music / ambient sound, 1 = gyroscope (tilt + movement)
    public int mode = 0;
    public int tiltDeadDeg = 8, tiltMaxDeg = 60;          // tilt below dead = level (no tilt drive); at max = full
    public int moveFullX10 = 30;                            // linear acceleration (m/s^2 x10) that counts as full movement
    public int gyroFullX10 = 30;                            // rotation rate (rad/s x10) that counts as full movement
    // relations: MotionMap.IGNORE / MORE / LESS. Defaults: tilt -> both stronger; movement -> Coyote weaker, Opossum stronger
    public int coyoteTiltRel = MotionMap.MORE, coyoteMoveRel = MotionMap.LESS;
    public int vibTiltRel = MotionMap.MORE, vibMoveRel = MotionMap.MORE;
    public int vibGyroConst = 0;
    // music mode: movement (gyro) delays the Coyote. Keep moving -> no shock, up to moveDelayMaxS; then (optionally)
    // a max shock for moveDelayShockS seconds. Stopping resets the hold.
    public boolean moveDelayOn = false;
    public int moveDelayMaxS = 30;
    public boolean moveDelayFinal = true;
    public int moveDelayShockS = 3;
    public int moveDelayNeedPct = 25;
    // music mode: phone "going down" (a dip / bounce) as downbeat. 0 off, 1 votes for the downbeat, 2 fires devices
    public int dipMode = 0;
    public int dipThrX10 = 15;                              // m/s^2 x10 downward that counts as a dip
    public boolean dipCoyote = true, dipOpossum = false;    // which devices fire on dips (mode 2)
    // ---- Chalk Cage (mode 2): camera box
    public float cageL = 0.2f, cageT = 0.1f, cageR = 0.8f, cageB = 0.9f;
    public boolean cageLocked = false, cageFront = false, cagePaused = false, cageVoice = true, cageNotDetOut = true;
    public int cageShockLevel = -1;                        // -1 = Coyote MAX (channel A max), else absolute 0-200
    public int cageShockS = 30, cageWarnS = 3, cageShockMode = CageLogic.SHOCK_FULL, cageVib = CageLogic.VIB_INSIDE;
    public int cageOutsidePct = 20, cageMinAreaPct = 2;
    public int cageZoomX10 = 10;                            // camera zoom ratio x10 (10 = 1.0x; <10 = ultra-wide if available)                       // % of "full movement" that counts as moving                            // gyro mode Opossum: 0 follow tilt/movement, 1 always on, 2 always off
    public int tiltRel(String kind) { return "coyote".equals(kind) ? coyoteTiltRel : vibTiltRel; }
    public int moveRel(String kind) { return "coyote".equals(kind) ? coyoteMoveRel : vibMoveRel; }

    public boolean vibFollowTempo = true;
    public boolean autoStrength = true; // Coyote follows tempo; else base value
    public double bpmLo = 90, bpmHi = 150;
    public int latencyMs = 100;        // fire this early
    public boolean everyBeat = true;
    public boolean channelB = true;    // Coyote: drive channel B electrodes
    public double sensitivity = 2.0;   // derived from sensitivityLevel
    public int sensitivityLevel = 5;   // derived: max of the two device levels; drives the shared detector
    public int pulseRate = 1;          // legacy (kept for old prefs); per-device rates below are used
    public int coyoteSens = 5;         // 1 strict .. 10 eager: how solid the beat must be before the Coyote fires
    public int vibSens = 7;            // same for the Opossum (usually higher: vibration is harmless)
    public int coyotePulseRate = 1;    // 0 once per bar, 1 every beat, 2 twice, 3 four times per beat
    public int vibPulseRate = 1;
    public boolean pip = true;
    public boolean micFallback = false;
    public boolean screenMotion = false;        // phone-audio mode: also detect screen movement
    public int motionSens = 5;                  // 1 strict .. 10 eager
    public boolean motionCoyote = false, motionOpossum = true;   // which devices fire on screen movement
    public int coyoteMaxWaitS = 5;      // timer window X seconds
    public int coyoteTimerMode = 1;     // 0 off, 1 every X s at the BPM peak (accumulates up to 3), 2 random interval
    public int coyoteRandMinS = 10, coyoteRandMaxS = 60;
    public boolean vibBothMotors = true; // Opossum: drive motor B
    public boolean vibAnyMusic = true;  // Opossum: vibrate on every detected kick, no beat lock needed (default)
    public int coyoteBpmMin = 60, coyoteBpmMax = 220;   // Coyote fires only while the locked tempo is inside this range
    public int vibBpmMin = 60, vibBpmMax = 220;         // same for the Opossum
    public int freqBalance = 160, intensityBalance = 0;
    public boolean coyoteEnabled = true, opossumEnabled = true;   // per-device pulse on/off

    private static final String PREF = "dgtempo";

    /** Wipe everything back to the built-in defaults. */
    public static Settings reset(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        return load(c);
    }

    public static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Settings s = new Settings();
        // Channel A uses the legacy keys (older installs keep their values), B uses "…B" keys and falls back to A.
        ChannelCfg legacyC = new ChannelCfg(s.cA.min, s.cA.max, 0, s.cA.burstMs, s.cA.wave, s.cA.randomLevel);
        legacyC.intensity = p.getInt("intensity", 100); legacyC.freq = p.getInt("freq", 30);
        legacyC.burstMs = p.getInt("burstMs", legacyC.burstMs); legacyC.wave = p.getString("coyoteWave", legacyC.wave);
        legacyC.continuous = p.getBoolean("coyoteContinuous", false); legacyC.randomLevel = p.getBoolean("coyoteRandomLevel", legacyC.randomLevel);
        legacyC.min = p.getInt("coyoteMin", legacyC.min); legacyC.max = p.getInt("coyoteMax", legacyC.max);
        s.cA.load(p, "_cA", legacyC);
        s.cB.load(p, "_cB", s.cA);
        ChannelCfg legacyO = new ChannelCfg(s.oA.min, s.oA.max, s.oA.manual, s.oA.burstMs, s.oA.wave, false);
        legacyO.min = p.getInt("vibMin", legacyO.min); legacyO.max = p.getInt("vibMax", legacyO.max);
        legacyO.manual = p.getInt("vibManual", legacyO.manual); legacyO.intensity = p.getInt("vibIntensity", 100);
        legacyO.burstMs = p.getInt("vibBurstMs", legacyO.burstMs); legacyO.wave = p.getString("opossumWave", legacyO.wave);
        legacyO.continuous = p.getBoolean("opossumContinuous", false);
        s.oA.load(p, "_oA", legacyO);
        s.oB.load(p, "_oB", s.oA);
        s.coyoteLink = p.getBoolean("coyoteLink", true);
        s.mode = p.getInt("mode", 0);
        s.tiltDeadDeg = p.getInt("tiltDeadDeg", s.tiltDeadDeg); s.tiltMaxDeg = p.getInt("tiltMaxDeg", s.tiltMaxDeg);
        s.moveFullX10 = p.getInt("moveFullX10", s.moveFullX10); s.gyroFullX10 = p.getInt("gyroFullX10", s.gyroFullX10);
        s.coyoteTiltRel = p.getInt("coyoteTiltRel", s.coyoteTiltRel); s.coyoteMoveRel = p.getInt("coyoteMoveRel", s.coyoteMoveRel);
        s.vibTiltRel = p.getInt("vibTiltRel", s.vibTiltRel); s.vibMoveRel = p.getInt("vibMoveRel", s.vibMoveRel);
        s.vibGyroConst = p.getInt("vibGyroConst", 0);
        s.moveDelayOn = p.getBoolean("moveDelayOn", false); s.moveDelayMaxS = p.getInt("moveDelayMaxS", 30);
        s.moveDelayFinal = p.getBoolean("moveDelayFinal", true); s.moveDelayShockS = p.getInt("moveDelayShockS", 3);
        s.moveDelayNeedPct = p.getInt("moveDelayNeedPct", 25);
        s.dipMode = p.getInt("dipMode", 0); s.dipThrX10 = p.getInt("dipThrX10", 15);
        s.dipCoyote = p.getBoolean("dipCoyote", true); s.dipOpossum = p.getBoolean("dipOpossum", false);
        s.cageL = p.getFloat("cageL", 0.2f); s.cageT = p.getFloat("cageT", 0.1f); s.cageR = p.getFloat("cageR", 0.8f); s.cageB = p.getFloat("cageB", 0.9f);
        s.cageLocked = p.getBoolean("cageLocked", false); s.cageFront = p.getBoolean("cageFront", false);
        s.cageVoice = p.getBoolean("cageVoice", true); s.cageNotDetOut = p.getBoolean("cageNotDetOut", true);
        s.cageShockLevel = p.getInt("cageShockLevel", -1); s.cageShockS = p.getInt("cageShockS", 30); s.cageWarnS = p.getInt("cageWarnS", 3);
        s.cageShockMode = p.getInt("cageShockMode", CageLogic.SHOCK_FULL); s.cageVib = p.getInt("cageVib", CageLogic.VIB_INSIDE);
        s.cageOutsidePct = p.getInt("cageOutsidePct", 20); s.cageMinAreaPct = p.getInt("cageMinAreaPct", 2);
        s.cageZoomX10 = p.getInt("cageZoomX10", 10);
        s.vibLink = p.getBoolean("vibLink", true);

        s.vibFollowTempo = p.getBoolean("vibFollowTempo", s.vibFollowTempo);
        s.autoStrength = p.getBoolean("autoStrength", s.autoStrength);
        s.bpmLo = p.getFloat("bpmLo", (float) s.bpmLo);
        s.bpmHi = p.getFloat("bpmHi", (float) s.bpmHi);
        s.latencyMs = p.getInt("latencyMs", s.latencyMs);
        s.everyBeat = p.getBoolean("everyBeat", s.everyBeat);
        s.channelB = p.getBoolean("channelB", s.channelB);
        s.sensitivity = p.getFloat("sensitivity", (float) s.sensitivity);
        s.coyoteEnabled = p.getBoolean("coyoteEnabled", true);
        s.opossumEnabled = p.getBoolean("opossumEnabled", true);
        s.sensitivityLevel = p.getInt("sensitivityLevel", 5);
        s.pulseRate = p.getInt("pulseRate", s.everyBeat ? 1 : 0);
        s.coyoteSens = p.getInt("coyoteSens", 5);
        s.vibSens = p.getInt("vibSens", 7);
        s.coyotePulseRate = p.getInt("coyotePulseRate", s.pulseRate);
        s.vibPulseRate = p.getInt("vibPulseRate", s.pulseRate);
        s.vibAnyMusic = p.getBoolean("vibAnyMusic", true);
        s.vibBothMotors = p.getBoolean("vibBothMotors", true);
        s.pip = p.getBoolean("pip", true);
        s.micFallback = p.getBoolean("micFallback", false);
        s.screenMotion = p.getBoolean("screenMotion", false);
        s.motionSens = p.getInt("motionSens", 5);
        s.motionCoyote = p.getBoolean("motionCoyote", false);
        s.motionOpossum = p.getBoolean("motionOpossum", true);
        s.coyoteMaxWaitS = p.getInt("coyoteMaxWaitS", s.coyoteMaxWaitS);
        s.coyoteTimerMode = p.getInt("coyoteTimerMode", s.coyoteTimerMode);
        s.coyoteRandMinS = p.getInt("coyoteRandMinS", 10);
        s.coyoteRandMaxS = p.getInt("coyoteRandMaxS", 60);
        s.coyoteBpmMin = p.getInt("coyoteBpmMin", 60); s.coyoteBpmMax = p.getInt("coyoteBpmMax", 220);
        s.vibBpmMin = p.getInt("vibBpmMin", 60); s.vibBpmMax = p.getInt("vibBpmMax", 220);
        return s;
    }

    /** Effective dials for a device channel (0 = A, 1 = B). Linked -> B uses A's dials. */
    public ChannelCfg chan(String kind, int ch) {
        boolean coy = "coyote".equals(kind);
        if (ch == 0) return coy ? cA : oA;
        if (coy) return coyoteLink ? cA : cB;
        return vibLink ? oA : oB;
    }
    public boolean linked(String kind) { return "coyote".equals(kind) ? coyoteLink : vibLink; }
    public void setLinked(String kind, boolean v) {
        if ("coyote".equals(kind)) { coyoteLink = v; if (v) cB.copyFrom(cA); }
        else { vibLink = v; if (v) oB.copyFrom(oA); }
    }

    public int bpmMin(String kind) { return "coyote".equals(kind) ? coyoteBpmMin : vibBpmMin; }
    public int bpmMax(String kind) { return "coyote".equals(kind) ? coyoteBpmMax : vibBpmMax; }
    /** Is this tempo inside the device's trigger range? (60 and 220 are the "any" ends) */
    public boolean bpmAllowed(String kind, double bpm) { return bpm >= bpmMin(kind) - 0.5 && bpm <= bpmMax(kind) + 0.5; }
    /** Second channel on for this device? Coyote: channel B electrodes; Opossum: motor B. */
    public boolean secondChannel(String kind) { return "coyote".equals(kind) ? channelB : vibBothMotors; }
    public boolean motionFires(String kind) { return screenMotion && ("coyote".equals(kind) ? motionCoyote : motionOpossum); }
    public int sens(String kind) { return "coyote".equals(kind) ? coyoteSens : vibSens; }
    public int pulseRate(String kind) { return "coyote".equals(kind) ? coyotePulseRate : vibPulseRate; }
    /** Longest pulse of the device's active channels (for watchdogs / test pulse). */
    public int burstMs(String kind) {
        int a = chan(kind, 0).burstMs;
        return secondChannel(kind) ? Math.max(a, chan(kind, 1).burstMs) : a;
    }
    /** Subdivisions per beat for a pulse rate value. */
    public static int subdiv(int rate) { return rate <= 1 ? 1 : rate == 2 ? 2 : 4; }
    /** The shared detector runs at the more eager of the two device levels. */
    public int eagerLevel() { return Math.max(coyoteSens, vibSens); }
    /** Onset flux threshold in std-devs: 3.0 (strict) .. 1.2 (eager). */
    public static double fluxK(int level) { return 3.0 - (level - 1) * 0.2; }
    /** Noise-floor gate in dB: 14 (strict) .. 4 (eager). */
    public static double gateDb(int level) { return 14.0 - (level - 1) * 1.1; }
    /** Periodicity confidence needed before this level fires: 0.45 (strict) .. 0.29 (eager); random clicks reach ~0.28. */
    public static double confMin(int level) { return 0.45 - (level - 1) * 0.0178; }
    /** Aligned beats needed before this level fires: 8 (strict) .. 4 (eager). */
    public static int lockHits(int level) { return 8 - (int) Math.round((level - 1) * 0.45); }

    public boolean enabled(String kind) { return "coyote".equals(kind) ? coyoteEnabled : opossumEnabled; }
    public void setEnabled(String kind, boolean v) { if ("coyote".equals(kind)) coyoteEnabled = v; else opossumEnabled = v; }

    public void save(Context c) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        cA.save(e, "_cA"); cB.save(e, "_cB"); oA.save(e, "_oA"); oB.save(e, "_oB");
        e.putBoolean("coyoteLink", coyoteLink).putBoolean("vibLink", vibLink)
                .putInt("mode", mode).putInt("tiltDeadDeg", tiltDeadDeg).putInt("tiltMaxDeg", tiltMaxDeg)
                .putInt("moveFullX10", moveFullX10).putInt("gyroFullX10", gyroFullX10)
                .putInt("coyoteTiltRel", coyoteTiltRel).putInt("coyoteMoveRel", coyoteMoveRel)
                .putInt("vibTiltRel", vibTiltRel).putInt("vibMoveRel", vibMoveRel).putInt("vibGyroConst", vibGyroConst)
                .putBoolean("moveDelayOn", moveDelayOn).putInt("moveDelayMaxS", moveDelayMaxS)
                .putBoolean("moveDelayFinal", moveDelayFinal).putInt("moveDelayShockS", moveDelayShockS).putInt("moveDelayNeedPct", moveDelayNeedPct)
                .putInt("dipMode", dipMode).putInt("dipThrX10", dipThrX10).putBoolean("dipCoyote", dipCoyote).putBoolean("dipOpossum", dipOpossum)
                .putFloat("cageL", cageL).putFloat("cageT", cageT).putFloat("cageR", cageR).putFloat("cageB", cageB)
                .putBoolean("cageLocked", cageLocked).putBoolean("cageFront", cageFront).putBoolean("cageVoice", cageVoice).putBoolean("cageNotDetOut", cageNotDetOut)
                .putInt("cageShockLevel", cageShockLevel).putInt("cageShockS", cageShockS).putInt("cageWarnS", cageWarnS)
                .putInt("cageShockMode", cageShockMode).putInt("cageVib", cageVib).putInt("cageOutsidePct", cageOutsidePct).putInt("cageMinAreaPct", cageMinAreaPct).putInt("cageZoomX10", cageZoomX10)
                .putBoolean("vibFollowTempo", vibFollowTempo)
                .putBoolean("autoStrength", autoStrength)
                .putFloat("bpmLo", (float) bpmLo).putFloat("bpmHi", (float) bpmHi)
                .putInt("latencyMs", latencyMs).putBoolean("everyBeat", everyBeat)
                .putBoolean("channelB", channelB).putFloat("sensitivity", (float) sensitivity)
                .putBoolean("coyoteEnabled", coyoteEnabled).putBoolean("opossumEnabled", opossumEnabled)
                .putInt("sensitivityLevel", sensitivityLevel).putInt("pulseRate", pulseRate)
                .putInt("coyoteSens", coyoteSens).putInt("vibSens", vibSens)
                .putInt("coyotePulseRate", coyotePulseRate).putInt("vibPulseRate", vibPulseRate)
                .putBoolean("vibAnyMusic", vibAnyMusic).putBoolean("vibBothMotors", vibBothMotors).putBoolean("pip", pip).putBoolean("micFallback", micFallback)
                .putBoolean("screenMotion", screenMotion).putInt("motionSens", motionSens)
                .putBoolean("motionCoyote", motionCoyote).putBoolean("motionOpossum", motionOpossum).putInt("coyoteMaxWaitS", coyoteMaxWaitS).putInt("coyoteTimerMode", coyoteTimerMode)
                .putInt("coyoteRandMinS", coyoteRandMinS).putInt("coyoteRandMaxS", coyoteRandMaxS)
                .putInt("coyoteBpmMin", coyoteBpmMin).putInt("coyoteBpmMax", coyoteBpmMax)
                .putInt("vibBpmMin", vibBpmMin).putInt("vibBpmMax", vibBpmMax)
                .apply();
    }
}
