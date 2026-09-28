package com.tupai.dgtempo;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, persisted. All strength values are device units (0-200). */
public final class Settings {
    public int coyoteMin = 5;          // Coyote strength at bpmLo and below
    public int coyoteMax = 30;         // Coyote strength at bpmHi and above; written as BF soft cap
    public int vibMin = 40;            // Opossum intensity at bpmLo
    public int vibMax = 120;           // Opossum intensity at bpmHi; cap
    public boolean vibFollowTempo = true;
    public int vibManual = 80;         // Opossum intensity when not following tempo
    public boolean autoStrength = true; // Coyote follows tempo; else coyoteMin fixed
    public double bpmLo = 90, bpmHi = 150;
    public int intensity = 100;        // slot intensity 0-100
    public int freq = 30;              // Coyote waveform freq byte 10-240
    public int burstMs = 100;
    public int latencyMs = 100;        // fire this early
    public boolean everyBeat = true;
    public boolean channelB = false;
    public double sensitivity = 2.0;   // derived from sensitivityLevel
    public int sensitivityLevel = 5;   // derived: max of the two device levels; drives the shared detector
    public int pulseRate = 1;          // legacy (kept for old prefs); per-device rates below are used
    public int vibBurstMs = 250;       // Opossum pulse length (motors need >= 150 ms)
    public int coyoteSens = 5;         // 1 strict .. 10 eager: how solid the beat must be before the Coyote fires
    public int vibSens = 7;            // same for the Opossum (usually higher: vibration is harmless)
    public int coyotePulseRate = 1;    // 0 once per bar, 1 every beat, 2 twice, 3 four times per beat
    public int vibPulseRate = 1;
    public int vibIntensity = 100;     // Opossum slot intensity 0-100 (Coyote uses `intensity`)
    public boolean pip = true;
    public boolean micFallback = false;
    public boolean coyoteRandomLevel = false;   // each Coyote pulse at a random level between base and max
    public boolean screenMotion = false;        // phone-audio mode: also detect screen movement
    public int motionSens = 5;                  // 1 strict .. 10 eager
    public boolean motionCoyote = false, motionOpossum = true;   // which devices fire on screen movement
    public int coyoteMaxWaitS = 30;     // timer window X seconds
    public int coyoteTimerMode = 0;     // 0 off, 1 every X s at the BPM peak (accumulates up to 3), 2 random interval
    public int coyoteRandMinS = 10, coyoteRandMaxS = 60; // phone audio lost -> switch to the microphone? (off: stop listening)          // picture-in-picture readout when leaving the app
    public boolean vibBothMotors = true; // Opossum: drive motor B with the same pattern as A (default: both)
    public boolean vibAnyMusic = true;  // Opossum: vibrate on every detected kick, no beat lock needed (default)
    public int coyoteBpmMin = 60, coyoteBpmMax = 220;   // Coyote fires only while the locked tempo is inside this range
    public int vibBpmMin = 60, vibBpmMax = 220;         // same for the Opossum
    public int freqBalance = 160, intensityBalance = 0;
    public boolean coyoteEnabled = true, opossumEnabled = true;   // per-device pulse on/off
    public String coyoteWave = Waveforms.SIMPLE_ID, opossumWave = "BEAT";   // waveform ids
    public boolean coyoteContinuous = false, opossumContinuous = false;      // loop like the official app vs on-the-beat

    private static final String PREF = "dgtempo";

    public static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.coyoteMin = p.getInt("coyoteMin", s.coyoteMin);
        s.coyoteMax = p.getInt("coyoteMax", s.coyoteMax);
        s.vibMin = p.getInt("vibMin", s.vibMin);
        s.vibMax = p.getInt("vibMax", s.vibMax);
        s.vibFollowTempo = p.getBoolean("vibFollowTempo", s.vibFollowTempo);
        s.vibManual = p.getInt("vibManual", s.vibManual);
        s.autoStrength = p.getBoolean("autoStrength", s.autoStrength);
        s.bpmLo = p.getFloat("bpmLo", (float) s.bpmLo);
        s.bpmHi = p.getFloat("bpmHi", (float) s.bpmHi);
        s.intensity = p.getInt("intensity", s.intensity);
        s.freq = p.getInt("freq", s.freq);
        s.burstMs = p.getInt("burstMs", s.burstMs);
        s.latencyMs = p.getInt("latencyMs", s.latencyMs);
        s.everyBeat = p.getBoolean("everyBeat", s.everyBeat);
        s.channelB = p.getBoolean("channelB", s.channelB);
        s.sensitivity = p.getFloat("sensitivity", (float) s.sensitivity);
        s.coyoteEnabled = p.getBoolean("coyoteEnabled", true);
        s.opossumEnabled = p.getBoolean("opossumEnabled", true);
        s.coyoteWave = p.getString("coyoteWave", s.coyoteWave);
        s.opossumWave = p.getString("opossumWave", s.opossumWave);
        s.coyoteContinuous = p.getBoolean("coyoteContinuous", false);
        s.sensitivityLevel = p.getInt("sensitivityLevel", 5);
        s.pulseRate = p.getInt("pulseRate", s.everyBeat ? 1 : 0);
        s.vibBurstMs = p.getInt("vibBurstMs", 250);
        s.coyoteSens = p.getInt("coyoteSens", 5);
        s.vibSens = p.getInt("vibSens", 7);
        s.coyotePulseRate = p.getInt("coyotePulseRate", s.pulseRate);
        s.vibPulseRate = p.getInt("vibPulseRate", s.pulseRate);
        s.vibIntensity = p.getInt("vibIntensity", 100);
        s.vibAnyMusic = p.getBoolean("vibAnyMusic", true);
        s.vibBothMotors = p.getBoolean("vibBothMotors", true);
        s.pip = p.getBoolean("pip", true);
        s.micFallback = p.getBoolean("micFallback", false);
        s.screenMotion = p.getBoolean("screenMotion", false);
        s.coyoteRandomLevel = p.getBoolean("coyoteRandomLevel", false);
        s.motionSens = p.getInt("motionSens", 5);
        s.motionCoyote = p.getBoolean("motionCoyote", false);
        s.motionOpossum = p.getBoolean("motionOpossum", true);
        s.coyoteMaxWaitS = p.getInt("coyoteMaxWaitS", 30);
        s.coyoteTimerMode = p.getInt("coyoteTimerMode", 0);
        s.coyoteRandMinS = p.getInt("coyoteRandMinS", 10);
        s.coyoteRandMaxS = p.getInt("coyoteRandMaxS", 60);
        s.coyoteBpmMin = p.getInt("coyoteBpmMin", 60); s.coyoteBpmMax = p.getInt("coyoteBpmMax", 220);
        s.vibBpmMin = p.getInt("vibBpmMin", 60); s.vibBpmMax = p.getInt("vibBpmMax", 220);
        s.opossumContinuous = p.getBoolean("opossumContinuous", false);
        return s;
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
    public int intensity(String kind) { return "coyote".equals(kind) ? intensity : vibIntensity; }
    public int burstMs(String kind) { return "coyote".equals(kind) ? burstMs : vibBurstMs; }
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
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("coyoteMin", coyoteMin).putInt("coyoteMax", coyoteMax)
                .putInt("vibMin", vibMin).putInt("vibMax", vibMax)
                .putBoolean("vibFollowTempo", vibFollowTempo).putInt("vibManual", vibManual)
                .putBoolean("autoStrength", autoStrength)
                .putFloat("bpmLo", (float) bpmLo).putFloat("bpmHi", (float) bpmHi)
                .putInt("intensity", intensity).putInt("freq", freq).putInt("burstMs", burstMs)
                .putInt("latencyMs", latencyMs).putBoolean("everyBeat", everyBeat)
                .putBoolean("channelB", channelB).putFloat("sensitivity", (float) sensitivity)
                .putBoolean("coyoteEnabled", coyoteEnabled).putBoolean("opossumEnabled", opossumEnabled)
                .putString("coyoteWave", coyoteWave).putString("opossumWave", opossumWave)
                .putBoolean("coyoteContinuous", coyoteContinuous).putBoolean("opossumContinuous", opossumContinuous)
                .putInt("sensitivityLevel", sensitivityLevel).putInt("pulseRate", pulseRate).putInt("vibBurstMs", vibBurstMs)
                .putInt("coyoteSens", coyoteSens).putInt("vibSens", vibSens)
                .putInt("coyotePulseRate", coyotePulseRate).putInt("vibPulseRate", vibPulseRate)
                .putInt("vibIntensity", vibIntensity)
                .putBoolean("vibAnyMusic", vibAnyMusic).putBoolean("vibBothMotors", vibBothMotors).putBoolean("pip", pip).putBoolean("micFallback", micFallback)
                .putBoolean("screenMotion", screenMotion).putBoolean("coyoteRandomLevel", coyoteRandomLevel).putInt("motionSens", motionSens)
                .putBoolean("motionCoyote", motionCoyote).putBoolean("motionOpossum", motionOpossum).putInt("coyoteMaxWaitS", coyoteMaxWaitS).putInt("coyoteTimerMode", coyoteTimerMode)
                .putInt("coyoteRandMinS", coyoteRandMinS).putInt("coyoteRandMaxS", coyoteRandMaxS)
                .putInt("coyoteBpmMin", coyoteBpmMin).putInt("coyoteBpmMax", coyoteBpmMax)
                .putInt("vibBpmMin", vibBpmMin).putInt("vibBpmMax", vibBpmMax)
                .apply();
    }
}
