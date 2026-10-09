package com.tupai.dgtempo;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that owns everything time-critical: the audio thread (mic or phone-audio capture),
 * the tempo tracker, the 100 ms BLE frame loop and the device links. Keeps running while the user is in
 * other apps or the screen is off.
 */
@SuppressLint("MissingPermission")
public final class BeatService extends Service implements BleDevice.Listener, androidx.lifecycle.LifecycleOwner {
    // CameraX needs a LifecycleOwner; the service is one (RESUMED while running)
    private final androidx.lifecycle.LifecycleRegistry lifecycle = new androidx.lifecycle.LifecycleRegistry(this);
    @Override public androidx.lifecycle.Lifecycle getLifecycle() { return lifecycle; }
    public static final String ACTION_START = "com.tupai.dgtempo.START";
    public static final String ACTION_STOP_OUTPUT = "com.tupai.dgtempo.STOP_OUTPUT";
    public static final String ACTION_QUIT = "com.tupai.dgtempo.QUIT";
    public static final String ACTION_PHONE_AUDIO = "com.tupai.dgtempo.PHONE_AUDIO";
    public static final String ACTION_MIC = "com.tupai.dgtempo.MIC";
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String CHANNEL = "dgtempo";
    private static final int NOTIF_ID = 1;
    private static final int SAMPLE_RATE = 48000;

    public interface UiListener {
        void onState();
        void onLog(String line);
        void onDevices();
    }

    public static final class Found {
        public final BluetoothDevice device; public final String name, kind; public int rssi;
        public Found(BluetoothDevice d, String name, String kind, int rssi) { device = d; this.name = name; this.kind = kind; this.rssi = rssi; }
    }

    public final class LocalBinder extends Binder { public BeatService get() { return BeatService.this; } }
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    public Settings settings;
    public final TempoTracker tracker = new TempoTracker();
    public final OnsetDetector detector = new OnsetDetector(SAMPLE_RATE);
    private volatile UiListener ui;
    public final ArrayDeque<String> logLines = new ArrayDeque<>();

    // state visible to the UI
    public volatile boolean armed = false;
    public volatile boolean audioRunning = false;
    public volatile String audioSource = "none";      // "mic" | "phone" | "none"
    public volatile boolean muted = false;             // input muted: detector fed silence, nothing fires
    public MotionSensors motionSensors;                // gyro mode input
    public volatile double tiltF = 0, moveF = 0;       // normalised 0..1 factors (gyro mode)
    public final double[] driveNow = {0, 0};           // per device drive 0..1 (coyote, opossum), smoothed
    private final double[] driveSmooth = {0, 0};
    public boolean gyroMode() { return settings.mode == 1; }
    public boolean cageMode() { return settings.mode == 2; }

    // ---- Chalk Cage -----------------------------------------------------------------------------
    public CageDetector cage;
    public final CageLogic cageLogic = new CageLogic();
    public volatile CageLogic.Out cageOut = new CageLogic.Out();
    public volatile double cageArea = 0, cageOutsideShare = 1, cageMotion = 0; public volatile float cageCx = -1, cageCy = -1;
    private android.media.MediaPlayer voice;             // bundled announcement clips (no TTS engine needed)
    private android.media.ToneGenerator tone;

    public void startCage() {
        if (cage == null) cage = new CageDetector(this, this);
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { log("cage: camera permission not granted"); return; }
        startForegroundCompat(projection != null);          // re-declare types incl. camera
        cage.box = new android.graphics.RectF(settings.cageL, settings.cageT, settings.cageR, settings.cageB);
        cage.listener = (area, share, cx, cy, motion) -> { cageArea = area; cageOutsideShare = share; cageCx = cx; cageCy = cy; cageMotion = motion; };
        cage.setZoom(settings.cageZoomX10 / 10f);
        cage.debugMask = settings.cageDebug;
        if (!cage.running) cage.start(settings.cageFront);
        if (tone == null) try { tone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90); } catch (Exception ignored) {}
        cageLogic.reset();
        log("Chalk Cage: camera on (" + (settings.cageFront ? "front" : "back") + ")");
    }

    public void stopCage() {
        if (cage != null) cage.stop();
        cageLogic.reset();
        cageOut = new CageLogic.Out();
    }

    public void setCageBox(float l, float t, float r, float b) {
        settings.cageL = l; settings.cageT = t; settings.cageR = r; settings.cageB = b;
        settings.save(this);
        if (cage != null) cage.box = new android.graphics.RectF(l, t, r, b);
    }

    private PowerManager.WakeLock screenLock;

    /** Chalk Cage: hold the screen on from the service too (belt and braces with the window flag). */
    @SuppressWarnings("deprecation")
    public void setScreenHold(boolean on) {
        try {
            if (on) {
                if (screenLock == null) {
                    PowerManager pm = getSystemService(PowerManager.class);
                    screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "dgtempo:cage-screen");
                }
                if (!screenLock.isHeld()) { screenLock.acquire(); log("screen: kept on for Chalk Cage"); }
            } else if (screenLock != null && screenLock.isHeld()) {
                screenLock.release();
                log("screen: normal timeout again");
            }
        } catch (Exception e) {
            log("screen hold: " + e.getMessage());
        }
    }

    public void setCageZoom(int x10) { settings.cageZoomX10 = x10; settings.save(this); if (cage != null) cage.setZoom(x10 / 10f); }

    public void setCageFront(boolean f) { settings.cageFront = f; settings.save(this); if (cage != null) cage.setFront(f); }

    /** Beep, then the bundled clip for the app language (en / zh / ja), at full media volume. */
    private void say(String what) {
        if (!settings.cageVoice) return;
        try { if (tone != null) tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 200); } catch (Exception ignored) {}
        String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String sfx = lang.startsWith("zh") ? "zh" : lang.startsWith("ja") ? "ja" : "en";
        int res = getResources().getIdentifier("say_" + what + "_" + sfx, "raw", getPackageName());
        if (res == 0) return;
        main.postDelayed(() -> {
            try {
                if (voice != null) { voice.release(); voice = null; }
                voice = android.media.MediaPlayer.create(this, res, new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)   // ducks music, audible over it
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(), 0);
                if (voice == null) return;
                voice.setVolume(1f, 1f);
                voice.setOnCompletionListener(mp -> { mp.release(); if (voice == mp) voice = null; });
                voice.start();
            } catch (Exception e) {
                log("voice: " + e.getMessage());
            }
        }, 250);
    }

    private CageLogic.Config cageConfig() {
        CageLogic.Config c = new CageLogic.Config();
        c.warnS = settings.cageWarnS; c.shockS = settings.cageShockS; c.shockMode = settings.cageShockMode; c.vibMode = settings.cageVib;
        c.outsideShare = settings.cageOutsidePct / 100.0; c.minArea = settings.cageMinAreaPct / 100.0;
        c.notDetectedIsOutside = settings.cageNotDetOut; c.paused = settings.cagePaused; c.locked = settings.cageLocked;
        c.dance = settings.cageDance; c.danceGraceS = settings.cageDanceGraceS; c.danceMoveMin = settings.cageDanceMovePct / 100.0;
        return c;
    }

    private void cageTick(double now) {
        boolean camOk = cage != null && cage.running;
        CageLogic.Out o = cageLogic.step(now, cageConfig(), armed && camOk, camOk ? cageArea : 0, cageOutsideShare, cageMotion);
        if (o.announce != null) { log("cage: " + o.announce); say(o.announce); }
        cageOut = o;
    }

    /** Strength a device gets in cage mode (Coyote: configured shock level; Opossum: its channel max). */
    private int cageStrength(String kind, Settings.ChannelCfg c) {
        if ("coyote".equals(kind)) {
            CageLogic.Out o = cageOut;
            int lvl = settings.cageShockLevel;
            if (o.danceShock && !o.shock) lvl = settings.cageDanceLevel < 0 ? settings.cageShockLevel : settings.cageDanceLevel;
            return Math.max(0, Math.min(c.max, lvl < 0 ? c.max : lvl));
        }
        return settings.vibFollowTempo ? c.max : c.manual;
    }

    /** Switch input mode: 0 = music, 1 = gyro. Sensors run only in gyro mode. */
    public void setMode(int mode) {
        settings.mode = mode;
        settings.save(this);
        ensureSensors();
        if (mode == 2) startCage(); else { stopCage(); setScreenHold(false); }
        log(mode == 1 ? "gyro mode: tilt + movement drive the output" : mode == 2 ? "Chalk Cage mode" : "music mode");
        UiListener l = ui;
        if (l != null) main.post(l::onDevices);
    }

    /** Gyro factors + per-device drives from the current sensor state. */
    private void motionTick() {
        MotionSensors m = motionSensors;
        if (m == null || !m.running) { tiltF = moveF = 0; driveNow[0] = driveNow[1] = 0; return; }
        tiltF = MotionMap.tiltFactor(m.tiltDeg, settings.tiltDeadDeg, settings.tiltMaxDeg);
        moveF = MotionMap.moveFactor(m.accel, settings.moveFullX10 / 10.0, m.gyro, settings.gyroFullX10 / 10.0);
        String[] kinds = {"coyote", "opossum"};
        for (int i = 0; i < 2; i++) {
            double d = MotionMap.drive(tiltF, settings.tiltRel(kinds[i]), moveF, settings.moveRel(kinds[i]));
            if (i == 1 && settings.vibGyroConst == 1) d = 1;          // Opossum: constantly on
            if (i == 1 && settings.vibGyroConst == 2) d = 0;          // Opossum: constantly off
            driveSmooth[i] += (d - driveSmooth[i]) * 0.25;        // ~0.4 s smoothing at 10 Hz
            driveNow[i] = driveSmooth[i];
        }
    }

    public double drive(String kind) { return driveNow["coyote".equals(kind) ? 0 : 1]; }

    // ---- music mode: movement delays the Coyote ----------------------------------------------
    public volatile double holdS = 0;                  // seconds of (near-)continuous movement accumulated
    public volatile double maxShockUntil = 0;          // forced max shock window end
    private double lastMoveAt = -1, lastDelayTick = 0;
    public volatile String delayNote = "";
    private double delayLimit = 0;                    // limit of the current hold (fixed, or drawn per hold when random)

    private double drawDelayLimit() {
        int max = Math.max(1, settings.moveDelayMaxS);
        return settings.moveDelayRandom ? 1 + rng.nextInt(max) : max;
    }

    public boolean needSensors() { return gyroMode() || settings.moveDelayOn || settings.dipMode != 0; }

    // ---- music mode: phone dips (going down) as downbeat -------------------------------------
    private final ArrayDeque<Double> recentDips = new ArrayDeque<>();
    public volatile double lastDipAt = -1;

    private void onDip(double t) {
        if (gyroMode() || settings.dipMode == 0) return;
        lastDipAt = t;
        if (settings.dipMode == 1) tracker.addEvidence(t, 1.5);          // a body dip votes strongly for that beat as "1"
        else synchronized (recentDips) {
            recentDips.addLast(t);
            while (recentDips.size() > 32) recentDips.pollFirst();
        }
    }

    public void setDipMode(int mode) {
        settings.dipMode = mode;
        settings.save(this);
        ensureSensors();
        log(mode == 0 ? "dips off" : mode == 1 ? "dips vote for the downbeat" : "dips fire the selected devices");
    }

    /** Start or stop the motion sensors according to what the current settings need. */
    private void ensureSensors() {
        if (needSensors()) {
            if (motionSensors == null) { motionSensors = new MotionSensors(this); }
            motionSensors.dipListener = this::onDip;
            motionSensors.dipThreshold = settings.dipThrX10 / 10.0;
            if (!motionSensors.running && !motionSensors.start()) log("gyro: no accelerometer on this phone");
        } else if (motionSensors != null) {
            motionSensors.stop();
        }
    }

    private boolean dipFires(String kind) {
        return !gyroMode() && settings.dipMode == 2 && ("coyote".equals(kind) ? settings.dipCoyote : settings.dipOpossum);
    }

    public void setMoveDelay(boolean on) {
        settings.moveDelayOn = on;
        settings.save(this);
        ensureSensors();
        if (on) log("movement delay ON: keep moving to hold the Coyote off (max " + settings.moveDelayMaxS + " s)");
        holdS = 0; maxShockUntil = 0; delayNote = "";
    }

    /** Is the Coyote currently held off by movement? (music mode only) */
    /** Live readout for the music-tab Coyote section: sensors, movement vs threshold, hold clock. */
    public String moveDelayReadout() {
        boolean run = motionSensors != null && motionSensors.running;
        if (!settings.moveDelayOn) return "";
        if (!run) return "sensors OFF";
        double limitShown = settings.moveDelayRandom && !settings.moveDelayShowLimit ? -1 : Math.max(1, delayLimit);
        if (!musicOn) return String.format("sensors on · movement %.0f%% · waiting for music (the game runs only while music plays)", moveF * 100);
        return String.format("sensors on · movement %.0f%% (need %d%%) · %s · hold %.1f%s s%s",
                moveF * 100, settings.moveDelayNeedPct, moveF >= settings.moveDelayNeedPct / 100.0 ? "MOVING" : "still",
                holdS, limitShown < 0 ? "" : String.format("/%.0f", limitShown),
                armed ? (coyoteHeld() ? " · Coyote HELD" : "") : " · arm to start");
    }

    public boolean coyoteHeld() {
        if (gyroMode() || !settings.moveDelayOn || !armed) return false;
        double now = System.nanoTime() / 1e9;
        if (now < maxShockUntil) return false;
        return holdS > 0 && holdS < Math.max(1, delayLimit);
    }

    private void moveDelayTick(double now) {
        if (gyroMode() || !settings.moveDelayOn || motionSensors == null || !motionSensors.running) { holdS = 0; delayNote = ""; return; }
        double dt = lastDelayTick == 0 ? 0 : Math.min(0.5, now - lastDelayTick);
        lastDelayTick = now;
        double mf = MotionMap.moveFactor(motionSensors.accel, settings.moveFullX10 / 10.0, motionSensors.gyro, settings.gyroFullX10 / 10.0);
        moveF = mf;
        boolean moving = mf >= settings.moveDelayNeedPct / 100.0;
        if (moving) lastMoveAt = now;
        if (!musicOn) {                                   // music mode rule: no music -> nothing, not even this game
            if (now < maxShockUntil) { maxShockUntil = 0; log("movement delay: music stopped -> MAX shock cut"); }
            holdS = 0; lastMoveAt = -1; delayNote = settings.moveDelayOn && armed ? "waiting for music" : "";
            return;
        }
        if (now < maxShockUntil) { delayNote = String.format("⚡MAX %.0fs", maxShockUntil - now); return; }
        if (!armed) { holdS = 0; delayNote = ""; return; }
        boolean recent = lastMoveAt > 0 && now - lastMoveAt < 1.0;      // 1 s grace: a short pause does not reset
        if (recent) {
            if (holdS == 0) { delayLimit = drawDelayLimit(); log(String.format("movement hold started (limit %.0f s%s)", delayLimit, settings.moveDelayRandom ? ", random" : "")); }
            holdS += dt;
            if (holdS >= delayLimit) {
                if (settings.moveDelayFinal) {
                    maxShockUntil = now + settings.moveDelayShockS;
                    log("movement delay: " + settings.moveDelayMaxS + " s reached -> MAX shock for " + settings.moveDelayShockS + " s");
                } else {
                    log("movement delay: " + settings.moveDelayMaxS + " s reached -> shocks resume");
                }
                holdS = 0; lastMoveAt = -1;
                delayNote = "";
                return;
            }
            delayNote = settings.moveDelayRandom && !settings.moveDelayShowLimit
                    ? String.format("⏳ %.0fs", holdS)                                   // random + hidden: elapsed only
                    : String.format("⏳ %.0f/%.0fs", holdS, delayLimit);                 // fixed, or random shown
        } else {
            if (holdS > 0) log("movement stopped: Coyote released");
            holdS = 0; delayNote = "";
        }
    }

    public void setMuted(boolean m) {
        muted = m;
        if (m) synchronized (tracker) { tracker.reset(); }
        log(m ? "input MUTED - beat detection paused" : "input unmuted");
        UiListener l = ui;
        if (l != null) main.post(l::onDevices);
    }
    public volatile int offset = 0;
    public volatile long bursts = 0;
    public volatile int lateTicks = 0;
    public volatile boolean scanning = false;
    public volatile double lastFlash = 0;
    public final Map<String, Found> found = new LinkedHashMap<>();       // by address
    public final Map<String, BleDevice> devices = new LinkedHashMap<>(); // by kind
    private final Map<String, String> seenAll = new LinkedHashMap<>();

    private AudioRecord record;
    private Thread audioThread;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCb;
    /** Set when the system ended the phone-audio capture; the activity re-asks for the share on its next resume. */
    public volatile boolean phoneAudioLost = false;
    private android.hardware.display.VirtualDisplay vDisplay;
    private android.media.ImageReader imageReader;
    private android.os.HandlerThread motionThread;
    private byte[] prevLuma;
    private long lastMotionFrameNs = 0;
    public final MotionDetector motion = new MotionDetector();
    private final ArrayDeque<Double> recentMotion = new ArrayDeque<>();
    public volatile double lastMotionAt = -1;
    public volatile boolean motionRunning = false;
    private volatile android.graphics.Rect selfRect = null;     // our own PiP window on screen (masked out)
    private volatile boolean selfFullscreen = false;            // our app fills the screen: nothing to watch
    private int screenW = 0, screenH = 0;

    /** Activity reports where it is: fullscreen (pause motion), a PiP rect (mask it), or null (not visible). */
    public void setSelfWindow(boolean fullscreen, android.graphics.Rect pipRect) {
        selfFullscreen = fullscreen;
        selfRect = pipRect;
        // Screen-movement capture costs CPU, so it only runs while the mini window is showing
        // (that is when the user is watching a video) and is torn down the moment it closes.
        boolean inPip = pipRect != null;
        if (inPip && settings.screenMotion && projection != null && !motionRunning) main.post(this::startMotion);
        if (!inPip && motionRunning) main.post(this::stopMotion);
    }
    private static final int MOTION_W = 80, MOTION_H = 80;
    private ScheduledExecutorService sched;
    private double nextTick = 0;
    private int tickCount = 0;
    private volatile double testBurstUntil = 0;
    private volatile double armedAt = 0;               // anchor for continuous-mode waveforms
    private volatile double lastCoyoteFire = 0;        // last frame in which the Coyote actually output something
    private volatile double coyoteForceUntil = 0;      // forced burst train: end time
    private volatile double coyoteForceFrom = 0;       // forced burst train: start time
    private volatile int coyoteForceCount = 0;         // pulses in the train
    private volatile boolean coyoteForceMax = false;   // train at max strength
    // timer engine state
    private double winStart = 0, winMaxBpm = 0, debtSince = 0, lastTimerTick = 0;
    private int owed = 0;
    private double nextRandomAt = 0;
    private int randLoUsed = -1, randHiUsed = -1;
    private final java.util.Random rng = new java.util.Random();
    public volatile String timerNote = "";
    // random level per Coyote pulse: drawn one pulse ahead so the preview can show it
    private final double[] randLevelX = {-1, -1};      // per Coyote channel (B follows A when linked)
    private final double[] nextRandDrawAt = {0, 0};

    /** Time-based redraw, independent of beats / bursts / tempo: a fresh level every pulse length + 0.25 s (0.5..3 s). */
    private void randLevelTick(double now) {
        for (int ch = 0; ch < 2; ch++) {
            Settings.ChannelCfg c = settings.chan("coyote", ch);
            if (!c.randomLevel) { nextRandDrawAt[ch] = 0; continue; }
            if (ch == 1 && settings.coyoteLink) { randLevelX[1] = randLevelX[0]; continue; }
            if (now >= nextRandDrawAt[ch]) {
                randLevelX[ch] = drawLevelX(ch);
                double hold = Math.max(0.5, Math.min(3.0, c.burstMs / 1000.0 + 0.25));
                nextRandDrawAt[ch] = now + hold;
            }
        }
    }

    /**
     * Spread over the whole base..max range, leaning low: 85% of draws are u^1.8 (median ~40% of the way up,
     * but reaching everywhere), 15% are uniform in the top quarter so the high side keeps showing up.
     * Never repeats within 8% of the previous level so consecutive pulses feel different.
     */
    private double drawLevelX(int ch) {
        for (int i = 0; i < 6; i++) {
            double x = rng.nextDouble() < 0.15 ? 0.75 + 0.25 * rng.nextDouble() : Math.pow(rng.nextDouble(), 1.8);
            if (randLevelX[ch] < 0 || Math.abs(x - randLevelX[ch]) > 0.08) return x;
        }
        return rng.nextDouble();
    }

    private double randLevelX(int ch) {
        if (ch == 1 && settings.coyoteLink) return randLevelX(0);
        if (randLevelX[ch] < 0) randLevelX[ch] = drawLevelX(ch);
        return randLevelX[ch];
    }

    /** Queue a train of `count` Coyote pulses, spaced by pulse length + 250 ms. */
    private void forceCoyote(int count, boolean atMax, String why) {
        double now = System.nanoTime() / 1e9;
        double blen = settings.chan("coyote", 0).burstMs / 1000.0;
        coyoteForceCount = count;
        coyoteForceMax = atMax;
        coyoteForceFrom = now + Protocol.FRAME_S;
        coyoteForceUntil = coyoteForceFrom + count * (blen + 0.25);
        lastCoyoteFire = now;
        log("Coyote timer: " + why + " -> " + count + " pulse(s)" + (atMax ? " at MAX" : ""));
    }

    /** Timer engine (Coyote): every-X-at-peak with accumulation, or random interval. */
    private void timerTick(double now) {
        BleDevice dc = devices.get("coyote");
        boolean ready = settings.coyoteTimerMode != 0 && armed && settings.coyoteEnabled && dc != null && dc.connected;
        if (!ready) { winStart = 0; winMaxBpm = 0; owed = 0; nextRandomAt = 0; timerNote = ""; return; }
        boolean sound = musicStable(now);                   // timers run only on real music
        double dt = lastTimerTick == 0 ? 0 : Math.min(0.5, now - lastTimerTick);
        lastTimerTick = now;
        if (!sound) {
            // no music: the timer is frozen - nothing counts, nothing is owed, nothing can fire
            if (nextRandomAt > 0) nextRandomAt += dt;
            if (winStart > 0) winStart += dt;
            owed = 0; winMaxBpm = 0;
            timerNote = "waiting for music";
            return;
        }
        if (settings.coyoteTimerMode == 2) {
            int lo = Math.min(settings.coyoteRandMinS, settings.coyoteRandMaxS), hi = Math.max(settings.coyoteRandMinS, settings.coyoteRandMaxS);
            if (nextRandomAt == 0 || lo != randLoUsed || hi != randHiUsed) {   // first time, or the range was changed: redraw
                nextRandomAt = now + lo + rng.nextDouble() * (hi - lo);
                randLoUsed = lo; randHiUsed = hi;
                log(String.format("Coyote random timer: next in %.0f s (%d-%d)", nextRandomAt - now, lo, hi));
            }
            timerNote = String.format("rnd %.0fs%s", Math.max(0, nextRandomAt - now), sound ? "" : " (waiting for music)");
            if (now >= nextRandomAt && sound && now >= coyoteForceUntil) {
                forceCoyote(1, false, "random interval");
                nextRandomAt = now + lo + rng.nextDouble() * (hi - lo);
                log(String.format("Coyote random timer: next in %.0f s", nextRandomAt - now));
            }
            return;
        }
        // mode 1: sliding window of X s, fire at the highest BPM seen in the window
        double x = Math.max(1, settings.coyoteMaxWaitS);
        if (winStart == 0) winStart = now;
        boolean locked = tracker.locked();
        double bpm = locked ? tracker.bpm() : 0;
        if (locked && bpm > winMaxBpm) winMaxBpm = bpm;
        if (now - winStart >= x) {                    // one more pulse owed per elapsed window (cap 3)
            if (owed == 0) debtSince = now;
            owed = Math.min(3, owed + 1);
            winStart = now;
        }
        timerNote = String.format("win %.0f/%.0fs owed %d peak %.0f", now - winStart, x, owed, winMaxBpm);
        if (owed <= 0 || !sound || now < coyoteForceUntil) return;
        // "visible highest point": the tempo is at the window's high (within 1 BPM). If no such moment shows up
        // for a whole extra window, fire anyway so "every X seconds" still roughly holds.
        boolean atPeak = locked && winMaxBpm > 0 && bpm >= winMaxBpm - 1.0;
        boolean overdue = now - debtSince >= x;
        if (!atPeak && !overdue) return;              // waiting for the high point: debt keeps accumulating
        String why = (atPeak ? "at peak " + Math.round(winMaxBpm) + " BPM" : "no peak seen, overdue") + ", " + owed + " owed";
        if (owed >= 3) forceCoyote(3, true, why); else forceCoyote(owed, false, why);
        owed = 0; winStart = now;
        winMaxBpm = locked ? bpm : 0;                 // next "high" must beat the current tempo (or any tempo if unlocked)
    }
    private PowerManager.WakeLock wakeLock;
    private BluetoothLeScanner scanner;
    private boolean wasLocked = false, wasBarKnown = false;
    private double silentSince = -1;
    private boolean silentWarned = false;
    private String lastNotifText = "";
    private boolean foregroundStarted = false;
    private boolean lastFgProjection = false;

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycle.setCurrentState(androidx.lifecycle.Lifecycle.State.RESUMED);
        settings = Settings.load(this);
        applySensitivity();
        main.post(this::ensureSensors);
        if (settings.mode == 2) main.post(this::startCage);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "DG Tempo", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_OUTPUT.equals(action)) {
            stopOutput("notification");
        } else if (ACTION_QUIT.equals(action)) {
            quit();
        } else if (ACTION_PHONE_AUDIO.equals(action)) {
            startForegroundCompat(true);
            startPhoneAudio(intent.getIntExtra(EXTRA_CODE, 0), intent.getParcelableExtra(EXTRA_DATA));
        } else if (ACTION_MIC.equals(action)) {
            stopAudio();
            stopProjection();                 // ends the screen/audio capture session (status-bar chip goes away)
            startForegroundCompat(false);
            startMic();
        } else {
            // (re)start from the activity: never drop the media-projection type while a capture is running,
            // otherwise Android 14+ kills the phone-audio capture every time the screen is recreated
            startForegroundCompat(projection != null);
            acquireWakeLock();
            if (!audioRunning && !"phone".equals(audioSource) && projection == null && sched == null) startMic();
            startScheduler();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    /** App swiped away from recents = exit: stop output, end screen capture, disconnect, stop. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        log("app closed: stopping everything");
        quit();
        super.onTaskRemoved(rootIntent);
    }
    public void setUi(UiListener l) { ui = l; }

    @Override
    public void onDestroy() {
        quitInternal();
        lifecycle.setCurrentState(androidx.lifecycle.Lifecycle.State.DESTROYED);
        if (voice != null) { try { voice.release(); } catch (Exception ignored) {} voice = null; }
        super.onDestroy();
    }

    private void startForegroundCompat(boolean withProjection) {
        Notification n = buildNotification();
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (Build.VERSION.SDK_INT >= 30) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (withProjection && Build.VERSION.SDK_INT >= 29) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (settings != null && settings.mode == 2 && Build.VERSION.SDK_INT >= 30
                && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, types);
            else startForeground(NOTIF_ID, n);
            if (foregroundStarted && withProjection != lastFgProjection) log("foreground types updated (projection " + (withProjection ? "on" : "off") + ")");
            lastFgProjection = withProjection;
            foregroundStarted = true;
        } catch (Exception e) {
            log("foreground service: " + e.getMessage());
            if (!foregroundStarted) startForeground(NOTIF_ID, n);
        }
    }

    private String notifText() {
        if (!armed) return getString(R.string.notif_stopped) + " · " + (gyroMode() ? "gyro" : audioLabel());
        StringBuilder sb = new StringBuilder();
        if (cageMode()) sb.append("cage: ").append(cageNote());
        else if (gyroMode()) sb.append(String.format("tilt %.0f° move %.0f%%", motionSensors == null ? 0 : motionSensors.tiltDeg, moveF * 100));
        else sb.append(tracker.locked() ? String.format("%.0f BPM ●", tracker.bpm()) : "… BPM");
        for (BleDevice d : devices.values()) if (d.connected)
            sb.append("  ").append(d.label.charAt(0)).append(':').append(Math.max(0, d.strength))
              .append(settings.secondChannel(d.kind) && !settings.linked(d.kind) ? "/" + Math.max(0, d.strengthB) : "");
        if (!gyroMode() && !cageMode()) sb.append("  ").append(audioLabel()).append(String.format(" %.0f dB", detector.levelDb));
        return sb.toString();
    }

    private String audioLabel() {
        return "phone".equals(audioSource) ? "phone audio" : "mic".equals(audioSource) ? "mic" : "no audio";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BeatService.class).setAction(ACTION_STOP_OUTPUT);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        lastNotifText = notifText();
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(lastNotifText)
                .setContentIntent(pOpen)
                .addAction(0, getString(R.string.notif_stop), pStop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification() {
        if (!foregroundStarted) return;
        if (notifText().equals(lastNotifText)) return;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dgtempo:beat");
        wakeLock.acquire();
    }

    public void quit() {
        quitInternal();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void quitInternal() {
        armed = false;
        for (BleDevice d : devices.values()) d.disconnect();
        stopScan();
        if (sched != null) { sched.shutdownNow(); sched = null; }
        if (motionSensors != null) motionSensors.stop();
        stopCage();
        setScreenHold(false);
        stopAudio();
        stopProjection();
        if (wakeLock != null) { wakeLock.release(); wakeLock = null; }
    }

    // ---- logging / UI ----------------------------------------------------------------------

    public void log(String msg) {
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > 40) logLines.pollFirst();
        }
        UiListener l = ui;
        if (l != null) main.post(() -> l.onLog(msg));
    }

    private void postDevices() {
        UiListener l = ui;
        if (l != null) main.post(l::onDevices);
    }

    // ---- audio: microphone or phone playback capture ------------------------------------------

    private void startMic() {
        if (audioRunning) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("mic: permission not granted");
            return;
        }
        AudioManager am = getSystemService(AudioManager.class);
        boolean unprocessed = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int source = unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioRecord rec;
        try {
            rec = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes());
        } catch (Exception e) {
            log("mic: " + e.getMessage());
            return;
        }
        String fx = "";
        try {
            int sid = rec.getAudioSessionId();
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(sid);
                if (ns != null) { ns.setEnabled(false); fx += " NS-off"; }
            }
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl agc = android.media.audiofx.AutomaticGainControl.create(sid);
                if (agc != null) { agc.setEnabled(false); fx += " AGC-off"; }
            }
        } catch (Exception ignored) {}
        runAudio(rec, "mic", "mic: " + (unprocessed ? "UNPROCESSED" : "VOICE_RECOGNITION") + " @48k" + fx);
    }

    /** Capture what the phone itself is playing (videos, music apps). Android 10+, needs the projection consent. */
    private void startPhoneAudio(int code, Intent data) {
        if (Build.VERSION.SDK_INT < 29) { log("phone audio needs Android 10+"); fallback(); return; }
        if (data == null || code == 0) { log("phone audio: not allowed"); fallback(); return; }
        stopAudio();
        stopProjection();
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            log("phone audio: " + e.getMessage());
            fallback();
            return;
        }
        if (projection == null) { log("phone audio: projection denied"); fallback(); return; }
        final MediaProjection mine = projection;
        projectionCb = new MediaProjection.Callback() {
            @Override public void onStop() {
                // stop() on a previous projection delivers its callback asynchronously, after a new capture
                // may already be running: only react if this is still the live projection.
                if (projection != mine) return;
                projection = null;
                projectionCb = null;
                stopAudio();
                // Android 15 QPR1+ ends every screen projection (and with it this audio capture) when the phone
                // locks. The activity keeps the screen awake while phone audio runs and re-asks for the share
                // the next time it is opened; this flag drives that.
                phoneAudioLost = true;
                String why = Build.VERSION.SDK_INT >= 35 ? " (Android 15 ends capture when the phone locks or the status-bar chip is tapped)" : "";
                if (settings.micFallback) {
                    log("phone audio capture stopped" + why + " - switching to microphone (fallback is ON)");
                    startMic();
                } else {
                    log("phone audio capture stopped" + why + " - NOT listening now; open the app to share again");
                    stopOutput("phone audio stopped");
                }
                postDevices();
            }
        };
        projection.registerCallback(projectionCb, main);
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioRecord rec;
        try {
            rec = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(bufferBytes())
                    .build();
        } catch (Exception e) {
            log("phone audio: " + e.getMessage());
            stopProjection();
            fallback();
            return;
        }
        runAudio(rec, "phone", "phone audio capture @48k (apps that block capture, e.g. DRM video, stay silent)");
        if (settings.screenMotion) log("screen movement: will start when the mini window opens");
    }

    // ---- screen motion (tiny virtual display of the shared screen, frame differencing) ----------

    public void setScreenMotion(boolean on) {
        settings.screenMotion = on;
        settings.save(this);
        if (on && projection != null && selfRect != null && !motionRunning) startMotion();
        if (!on) stopMotion();
    }

    private void startMotion() {
        if (Build.VERSION.SDK_INT < 29 || projection == null || motionRunning) return;
        try {
            motionThread = new android.os.HandlerThread("dgtempo-motion", android.os.Process.THREAD_PRIORITY_DISPLAY);
            motionThread.start();
            Handler h = new Handler(motionThread.getLooper());
            imageReader = android.media.ImageReader.newInstance(MOTION_W, MOTION_H, android.graphics.PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onMotionFrame, h);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            vDisplay = projection.createVirtualDisplay("dgtempo-motion", MOTION_W, MOTION_H, dpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, h);
            prevLuma = null;
            motionRunning = true;
            log("screen movement: on while mini window is showing (" + MOTION_W + "x" + MOTION_H + " @ <=30 fps)");
        } catch (Exception e) {
            log("screen motion failed: " + e.getMessage());
            stopMotion();
        }
    }

    private void stopMotion() {
        if (motionRunning) log("screen movement: off (mini window closed)");
        motionRunning = false;
        try { if (vDisplay != null) vDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        if (motionThread != null) motionThread.quitSafely();
        vDisplay = null; imageReader = null; motionThread = null; prevLuma = null;
    }

    private void onMotionFrame(android.media.ImageReader reader) {
        android.media.Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            long ns = System.nanoTime();
            if (ns - lastMotionFrameNs < 33_000_000L) return;      // <= 30 fps is plenty
            lastMotionFrameNs = ns;
            if (selfFullscreen) { prevLuma = null; return; }       // the shared screen is showing this app: ignore
            if (screenW == 0) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
                    screenW = b.width(); screenH = b.height();
                } else {
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    screenW = dm.widthPixels; screenH = dm.heightPixels;
                }
            }
            android.graphics.Rect sr = selfRect;
            int mx0 = -1, my0 = -1, mx1 = -1, my1 = -1;              // masked block (our PiP window) in grid cells
            if (sr != null && screenW > 0) {
                mx0 = Math.max(0, sr.left * MOTION_W / screenW - 2); mx1 = Math.min(MOTION_W, sr.right * MOTION_W / screenW + 2);
                my0 = Math.max(0, sr.top * MOTION_H / screenH - 2);  my1 = Math.min(MOTION_H, sr.bottom * MOTION_H / screenH + 2);
            }
            android.media.Image.Plane pl = img.getPlanes()[0];
            java.nio.ByteBuffer buf = pl.getBuffer();
            int ps = pl.getPixelStride(), rs = pl.getRowStride();
            int w = img.getWidth(), hgt = img.getHeight();
            byte[] luma = new byte[w * hgt];
            long diff = 0; int counted = 0;
            for (int y = 0; y < hgt; y++) {
                int row = y * rs;
                boolean yMasked = y >= my0 && y < my1;
                for (int x = 0; x < w; x++) {
                    int o = row + x * ps;
                    int r = buf.get(o) & 0xFF, g = buf.get(o + 1) & 0xFF, b = buf.get(o + 2) & 0xFF;
                    int yv = (r + (g << 1) + b) >> 2;
                    luma[y * w + x] = (byte) yv;
                    if (yMasked && x >= mx0 && x < mx1) continue;    // our own window: not motion
                    if (prevLuma != null) { diff += Math.abs(yv - (prevLuma[y * w + x] & 0xFF)); counted++; }
                }
            }
            boolean had = prevLuma != null;
            prevLuma = luma;
            if (!had || counted == 0) return;
            double m = diff / (double) counted;
            double t = ns / 1e9;
            if (motion.feed(t, m)) {
                lastMotionAt = t;
                synchronized (recentMotion) {
                    recentMotion.addLast(t);
                    while (recentMotion.size() > 32) recentMotion.pollFirst();
                }
                tracker.addEvidence(t, 1.0);                          // a dance move on the grid votes for that beat as "1"
            }
        } catch (Exception e) {
            log("motion frame: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    /** Motion onsets per second over the last 5 s. */
    public double motionRate() {
        double now = System.nanoTime() / 1e9;
        int n = 0;
        synchronized (recentMotion) { for (double t : recentMotion) if (now - t < 5) n++; }
        return n / 5.0;
    }

    /** Recent screen-motion onset for this device (within its pulse length)? */
    private boolean motionActive(String kind, double now) {
        return settings.motionFires(kind) && motionRunning && lastMotionAt > 0
                && now - lastMotionAt < settings.burstMs(kind) / 1000.0 + 0.2;
    }

    /** Phone audio unavailable: mic only if the user allowed it, otherwise stay silent and stop output. */
    private void fallback() {
        if (settings.micFallback) { startMic(); return; }
        if (!audioRunning) { stopOutput("no audio source"); postDevices(); }
        log("mic fallback is OFF - not listening. Tap MICROPHONE to use the mic on purpose.");
    }

    private int bufferBytes() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(minBuf, OnsetDetector.HOP * 2 * 4);
    }

    private void runAudio(AudioRecord rec, String source, String msg) {
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { log(source + ": init failed"); rec.release(); return; }
        record = rec;
        rec.startRecording();
        audioRunning = true;
        audioSource = source;
        silentSince = -1; silentWarned = false;
        synchronized (tracker) { tracker.reset(); }
        log(msg);
        audioThread = new Thread(this::audioLoop, "dgtempo-audio");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
        postDevices();
    }

    private void audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = record;
        short[] buf = new short[OnsetDetector.HOP];
        while (audioRunning && rec == record) {
            int n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
            if (n <= 0) { try { Thread.sleep(5); } catch (InterruptedException e) { return; } continue; }
            double t = System.nanoTime() / 1e9;
            if (muted) java.util.Arrays.fill(buf, 0, n, (short) 0);      // keep the clock running, hear nothing
            OnsetDetector.Onset o = detector.feed(buf, n, t);
            if (o != null) {
                onsetCount++;
                lastOnsetAt = t;
                synchronized (recentOnsets) {
                    recentOnsets.addLast(t);
                    while (recentOnsets.size() > 32) recentOnsets.pollFirst();
                }
                tracker.addOnset(o.t, o.weight);
                noteTransitions();
            }
        }
    }

    private void stopAudio() {
        audioRunning = false;
        AudioRecord rec = record;
        record = null;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }
        audioSource = "none";
    }

    private void stopProjection() {
        stopMotion();
        MediaProjection p = projection;
        MediaProjection.Callback cb = projectionCb;
        projection = null;
        projectionCb = null;
        if (p != null) {
            if (cb != null) try { p.unregisterCallback(cb); } catch (Exception ignored) {}
            try { p.stop(); } catch (Exception ignored) {}
            log("phone audio capture stopped");
        }
    }

    /** 0..1 how close this device is to firing (1 = firing now). Used by the picture-in-picture readout. */
    public double readiness(String kind) {
        if (!armed) return 0;
        if (deviceActive(kind)) return 1;
        if (gyroMode()) return Math.min(0.99, drive(kind));
        if (cageMode()) return cageOut.state == CageLogic.WARNING ? Math.min(0.99, 1 - cageOut.warnLeft / Math.max(1, settings.cageWarnS)) : 0;
        if ("coyote".equals(kind) && settings.coyoteTimerMode == 2 && nextRandomAt > 0) {
            double lo = Math.min(settings.coyoteRandMinS, settings.coyoteRandMaxS), hi = Math.max(settings.coyoteRandMinS, settings.coyoteRandMaxS);
            double span = Math.max(1, hi), left = Math.max(0, nextRandomAt - System.nanoTime() / 1e9);
            return Math.min(0.99, 1 - left / span);                       // bar fills as the random moment approaches
        }
        if ("coyote".equals(kind) && settings.coyoteTimerMode == 1) return Math.min(0.99, owed / 3.0 + 0.2 * Math.min(1, (System.nanoTime() / 1e9 - winStart) / Math.max(1, settings.coyoteMaxWaitS)));
        if (!musicOn) return 0;
        if ("opossum".equals(kind) && settings.vibAnyMusic) return 0.5;
        int lvl = settings.sens(kind);
        double c = tracker.confidence / Settings.confMin(lvl);
        double h = tracker.hits / (double) Settings.lockHits(lvl);
        double r = Math.min(1, Math.min(c, h));
        if (tracker.locked() && !settings.bpmAllowed(kind, tracker.bpm())) r = Math.min(r, 0.99);
        return Math.min(0.99, r);
    }

    /** Strength the NEXT pulse of this device would use right now (tempo-mapped, or max for a 3-owed timer train). */
    public int nextLevel(String kind) { return nextLevel(kind, 0); }

    public int nextLevel(String kind, int ch) {
        BleDevice d = devices.get(kind);
        if (d == null || !d.connected) return 0;
        boolean coy = "coyote".equals(kind);
        Settings.ChannelCfg c = settings.chan(kind, ch);
        double x;
        if (gyroMode()) x = drive(kind);
        else if (coy && settings.coyoteTimerMode == 1 && owed >= 3) x = 1;
        else if (coy && c.randomLevel) x = randLevelX(ch);
        else if (tracker.locked()) x = Math.max(0, Math.min(1, (tracker.bpm() - settings.bpmLo) / Math.max(1.0, settings.bpmHi - settings.bpmLo)));
        else x = 0;
        return d.targetStrength(x, coy ? offset : 0, settings, c);
    }

    public String cageNote() {
        CageLogic.Out o = cageOut;
        if (settings.cagePaused) return "paused";
        if (!settings.cageLocked) return "draw box";
        if (o.state == CageLogic.SHOCK) return String.format("⚡%.0fs", o.shockLeft);
        if (o.danceShock) return "⚡dance!";
        if (settings.cageDance && o.stillS > 0) return String.format("still %.0fs", o.stillS);
        if (o.state == CageLogic.WARNING) return String.format("⚠%.0fs", o.warnLeft);
        return o.detected ? (o.inside ? "inside" : "outside") : "no one";
    }

    /** Short reason text for the PiP view. */
    public String readinessNote(String kind) {
        if (!armed) return "off";
        if (deviceActive(kind)) return "▶";
        if (gyroMode()) return String.format("%.0f%%", drive(kind) * 100);
        if (cageMode()) return cageNote();
        if ("coyote".equals(kind) && !delayNote.isEmpty()) return delayNote;
        if ("coyote".equals(kind) && settings.coyoteTimerMode == 2 && nextRandomAt > 0)
            return String.format("⏱%.0fs", Math.max(0, nextRandomAt - System.nanoTime() / 1e9));
        if ("coyote".equals(kind) && settings.coyoteTimerMode == 1)
            return "⏱" + owed + "/3";
        if (muted) return "muted";
        if (!musicOn) return "quiet";
        if ("opossum".equals(kind) && settings.vibAnyMusic) return "sound";
        if (tracker.locked() && !settings.bpmAllowed(kind, tracker.bpm())) return settings.bpmMin(kind) + "-" + settings.bpmMax(kind);
        return "lock";
    }

    private void noteTransitions() {
        boolean l = tracker.locked(), b = tracker.barKnown();
        if (l != wasLocked) {
            wasLocked = l;
            log((l ? "LOCKED " : "UNLOCKED ") + String.format("%.1f BPM", tracker.bpm()));
        }
        if (b != wasBarKnown) {
            wasBarKnown = b;
            if (b) log("bar found: downbeat = beat " + (tracker.downbeatPhase() + 1) + " of 4");
        }
    }

    // ---- 100 ms frame loop -------------------------------------------------------------------

    private void startScheduler() {
        if (sched != null) return;
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dgtempo-frames");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        nextTick = 0;
        sched.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }

    private double tempoX() { return tempoX("opossum"); }

    private double tempoX(String kind) { return tempoX(kind, 0); }

    private double tempoX(String kind, int ch) {
        if (!deviceActive(kind)) return -1;
        if (gyroMode() && System.nanoTime() / 1e9 >= testBurstUntil) return drive(kind);
        if (cageMode() && System.nanoTime() / 1e9 >= testBurstUntil) return 1;   // cage: absolute level set in the tick
        if ("coyote".equals(kind) && System.nanoTime() / 1e9 < maxShockUntil) return 1;   // max shock window
        if ("coyote".equals(kind) && coyoteForceMax && System.nanoTime() / 1e9 < coyoteForceUntil) return 1;   // timer: 3 owed -> max
        if ("coyote".equals(kind) && settings.chan(kind, ch).randomLevel) return randLevelX(ch);                 // random level per pulse
        if (!tracker.locked()) return 0;          // no beat (any-music mode / test pulse): use the "slow tempo" strength
        double x = (tracker.bpm() - settings.bpmLo) / Math.max(1.0, settings.bpmHi - settings.bpmLo);
        return Math.max(0, Math.min(1, x));
    }

    private void tick() {
        try {
            double now = System.nanoTime() / 1e9;
            if (nextTick == 0) nextTick = now;
            double tick = nextTick;
            nextTick += Protocol.FRAME_S;
            if (now - tick > 0.03) lateTicks++;
            if (now - tick > 0.3) { tick = now; nextTick = now + Protocol.FRAME_S; }
            tickCount++;

            if (armed && audioRunning && !gyroMode() && !cageMode() && now - detector.lastAudio > 2.0) stopOutput("no audio for 2 s");
            soundGateTick(now);
            if (gyroMode()) motionTick(); else if (cageMode()) cageTick(now); else moveDelayTick(now);
            if (tracker.checkTimeout(now)) { wasLocked = false; log("UNLOCKED: beats stopped"); }
            else if (tickCount % 10 == 0) noteTransitions();
            if (audioRunning) {
                if (detector.levelDb < -85) {
                    if (silentSince < 0) silentSince = now;
                    else if (!silentWarned && now - silentSince > 3) {
                        silentWarned = true;
                        log("audio input is SILENT - " + ("phone".equals(audioSource)
                                ? "is anything playing? (DRM apps block capture)" : "mic muted by Android or very quiet room"));
                    }
                } else { silentSince = -1; silentWarned = false; }
            }

            boolean anyBurst = false;
            int[] silentF = {10, 10, 10, 10}, silentI = new int[4];
            if (!gyroMode() && !cageMode()) { timerTick(now); randLevelTick(now); } else { timerNote = ""; }
            for (BleDevice d : devices.values()) {
                if (!d.connected) continue;
                boolean coy = "coyote".equals(d.kind);
                boolean en = settings.enabled(d.kind);
                boolean active = deviceActive(d.kind);
                boolean second = settings.secondChannel(d.kind);
                int rate = settings.pulseRate(d.kind);
                int[] sa = new int[2];                       // strength per channel
                int[][] fq = new int[2][], in = new int[2][]; // slots per channel
                boolean anySet = false;
                for (int ch = 0; ch < 2; ch++) {
                    boolean chOn = en && (ch == 0 || second);
                    Settings.ChannelCfg c = settings.chan(d.kind, ch);
                    double x = tempoX(d.kind, ch);
                    int target = (x < 0 || !chOn) ? 0 : cageMode() && now >= testBurstUntil ? cageStrength(d.kind, c) : d.targetStrength(x, coy ? offset : 0, settings, c);
                    int prev = ch == 0 ? d.strength : d.strengthB;
                    if (target != prev && d.strengthChangeAllowed()) { anySet = true; }
                    sa[ch] = target;
                    // bursts for this channel (its own pulse length / waveform / mode)
                    String wid = c.waveFor(gyroMode());
                    Waveforms.Waveform w = coy ? Waveforms.find(Waveforms.COYOTE, wid, c.freq)
                                               : Waveforms.find(Waveforms.OPOSSUM, wid, c.freq);
                    boolean cont = c.continuous;
                    double blen = c.burstMs / 1000.0;
                    java.util.List<double[]> use;
                    if (cageMode()) {
                        // cage: shock / vibration loop continuously while the rules say so
                        use = new java.util.ArrayList<>();
                        if (chOn && active && now >= testBurstUntil) use.add(new double[]{armedAt, Double.POSITIVE_INFINITY});
                        if (testBurstUntil > tick) use.add(new double[]{testBurstUntil - blen, testBurstUntil});
                        cont = true;
                    } else if (coy && !gyroMode() && now < maxShockUntil) {
                        // movement-delay max shock: pattern loops at max for the configured seconds
                        use = new java.util.ArrayList<>();
                        if (chOn) use.add(new double[]{maxShockUntil - settings.moveDelayShockS, maxShockUntil});
                        cont = true;
                    } else if (gyroMode()) {
                        // gyro: the pattern loops while the device is driven; strength carries tilt/movement
                        use = new java.util.ArrayList<>();
                        if (chOn && active && now >= testBurstUntil) use.add(new double[]{armedAt, Double.POSITIVE_INFINITY});
                        if (testBurstUntil > tick) use.add(new double[]{testBurstUntil - blen, testBurstUntil});
                        cont = true;
                    } else if (cont) {
                        use = new java.util.ArrayList<>();
                        if (active) use.add(new double[]{armedAt, Double.POSITIVE_INFINITY});
                        if (testBurstUntil > tick) use.add(new double[]{testBurstUntil - blen, testBurstUntil});
                    } else if (coy && tick < coyoteForceUntil) {
                        use = new java.util.ArrayList<>();
                        for (int k = 0; k < coyoteForceCount; k++) {
                            double st = coyoteForceFrom + k * (blen + 0.25);
                            if (st + blen > tick && st < tick + Protocol.FRAME_S) use.add(new double[]{st, st + blen});
                        }
                    } else if (!coy && settings.vibAnyMusic) {
                        // any-music mode: one vibration burst per detected kick, starting when it was heard
                        use = new java.util.ArrayList<>();
                        if (active) synchronized (recentOnsets) {
                            for (double ot : recentOnsets)
                                if (ot + blen > tick && ot < tick + Protocol.FRAME_S) use.add(new double[]{ot, ot + blen});
                        }
                        if (testBurstUntil > tick) use.add(new double[]{testBurstUntil - blen, testBurstUntil});
                    } else {
                        boolean beatActive = active && !(coy && settings.coyoteTimerMode != 0) && musicOn;
                        use = Scheduler.burstsIn(tracker, beatActive, tick, tick + Protocol.FRAME_S,
                                settings.latencyMs / 1000.0, blen, rate >= 1, testBurstUntil, Settings.subdiv(rate));
                    }
                    // screen movement: one burst per motion onset, on the devices that opted in
                    if (settings.motionFires(d.kind) && motionRunning && armed && musicOn) synchronized (recentMotion) {
                        for (double mt : recentMotion)
                            if (mt + blen > tick && mt < tick + Protocol.FRAME_S) use.add(new double[]{mt, mt + blen});
                    }
                    // phone dips: one burst per dip, on the devices that opted in
                    if (dipFires(d.kind) && armed && musicOn) synchronized (recentDips) {
                        for (double dt2 : recentDips)
                            if (dt2 + blen > tick && dt2 < tick + Protocol.FRAME_S) use.add(new double[]{dt2, dt2 + blen});
                    }
                    if (!use.isEmpty() && chOn) anyBurst = true;
                    Scheduler.Slots sl = chOn ? Scheduler.renderSlots(use, tick, w, c.intensity,
                            cont || Waveforms.SIMPLE_ID.equals(w.id)) : null;
                    fq[ch] = sl == null ? silentF : sl.freq;
                    in[ch] = sl == null ? silentI : sl.inten;
                }
                boolean set = anySet && d.strengthChangeAllowed();
                if (set) log(d.label + " strength -> A " + sa[0] + (second ? " B " + sa[1] : ""));
                int strengthA = set ? sa[0] : Math.max(0, d.strength);
                int strengthB = set ? sa[1] : Math.max(0, d.strengthB);
                d.sendFrame(strengthA, strengthB, set, fq[0], in[0], fq[1], in[1], settings);
                recordOutput(d.kind, strengthA, coy ? settings.chan(d.kind, 0).max : 200, in[0]);
                if (coy && (strengthA > 0 || strengthB > 0) && (in[0][0] + in[0][1] + in[0][2] + in[0][3] + in[1][0] + in[1][1] + in[1][2] + in[1][3]) > 0) lastCoyoteFire = now;
            }
            for (String k : new String[]{"coyote", "opossum"}) {
                BleDevice d = devices.get(k);
                if (d == null || !d.connected) recordOutput(k, 0, 1, new int[4]);
            }
            histPos = (histPos + 4) % HIST_SLOTS;
            if (anyBurst) {
                if (now - lastFlash > 0.15) bursts++;
                lastFlash = now;
            }
            if (testArmed && now > testBurstUntil + 0.3) { testArmed = false; armed = false; log("test pulse done (still STOPPED)"); }
            UiListener l = ui;
            if (l != null) main.post(l::onState);
            if (tickCount % 10 == 0) main.post(this::updateNotification);
        } catch (Exception e) {
            log("tick error: " + e);
        }
    }

    // ---- control -----------------------------------------------------------------------------

    public void arm() {
        if (devices.values().stream().noneMatch(d -> d.connected)) { log("nothing connected"); return; }
        armed = true;
        armedAt = System.nanoTime() / 1e9;
        log(">> ARMED");
        updateNotification();
    }

    public void stopOutput(String reason) {
        if (!armed) return;
        armed = false;
        log("!! STOP: " + reason);
        updateNotification();
    }

    public void toggleArm() { if (armed) stopOutput("user"); else arm(); }

    /** Pipeline check without music: min strength on every enabled connected device, one burst. */
    private volatile boolean testArmed = false;   // armed only for the test pulse; disarm again afterwards

    public void testPulse() {
        if (devices.values().stream().noneMatch(d -> d.connected)) { log("test pulse: nothing connected"); return; }
        if (!armed) { armed = true; testArmed = true; armedAt = System.nanoTime() / 1e9; }
        testBurstUntil = System.nanoTime() / 1e9 + 2 * Protocol.FRAME_S + Math.max(settings.burstMs("coyote"), settings.burstMs("opossum")) / 1000.0;
        log("test pulse: " + devices.values().stream().filter(d -> d.connected && settings.enabled(d.kind))
                .map(d -> d.label).reduce((a, b) -> a + " + " + b).orElse("none enabled"));
    }

    public void settingsChanged() {
        settings.save(this);
        applySensitivity();
        if (motionSensors != null) motionSensors.dipThreshold = settings.dipThrX10 / 10.0;
        if (cage != null) cage.debugMask = settings.cageDebug;
    }

    private void applySensitivity() {
        applyMotionSensitivity();
        int eager = settings.eagerLevel();
        settings.sensitivityLevel = eager;
        settings.sensitivity = Settings.fluxK(eager);
        detector.sensitivity = Settings.fluxK(eager);
        detector.gateDb = Settings.gateDb(eager);
        tracker.confMin = Settings.confMin(eager);
        tracker.lockHits = Settings.lockHits(eager);
    }

    /** Is the beat solid enough for THIS device's sensitivity? (tracker itself runs at the eager level) */
    public boolean deviceActive(String kind) {
        if (!armed) return false;
        double now = System.nanoTime() / 1e9;
        if (now < testBurstUntil) return true;                 // test pulse: every connected device fires
        if (gyroMode()) return drive(kind) >= 0.05;            // gyro: tilt/movement decide, music ignored
        if (cageMode()) return "coyote".equals(kind) ? (cageOut.shock || cageOut.danceShock) : cageOut.vib;
        if ("coyote".equals(kind) && now < maxShockUntil && musicOn) return true;      // movement-delay max shock (music playing)
        if ("coyote".equals(kind) && coyoteHeld()) return false;             // movement holds the Coyote off
        if ("coyote".equals(kind) && now < coyoteForceUntil) return true;   // timer pulse train in progress
        if ("coyote".equals(kind) && settings.coyoteTimerMode != 0) return false;   // timer mode: ONLY the timer fires the Coyote
        if (musicOn && motionActive(kind, now)) return true;   // screen movement fires this device (while music plays)
        if (musicOn && dipFires(kind) && lastDipAt > 0 && now - lastDipAt < settings.burstMs(kind) / 1000.0 + 0.2) return true;   // a dip fires it
        if (!musicOn) return false;                            // needs >= 1 s of sound to start; stops at once when it ends
        if ("opossum".equals(kind) && settings.vibAnyMusic) return musicPresent(now);
        if (!tracker.locked()) return false;
        int lvl = settings.sens(kind);
        return tracker.confidence >= Settings.confMin(lvl) && tracker.hits >= Settings.lockHits(lvl)
                && settings.bpmAllowed(kind, tracker.bpm());
    }

    public volatile long onsetCount = 0;
    /** Delivery times of recent onsets (for the Opossum's any-music mode). */
    private final ArrayDeque<Double> recentOnsets = new ArrayDeque<>();
    public volatile double lastOnsetAt = -1;
    private static final double MUSIC_PRESENT_S = 2.0;

    public boolean musicPresent(double now) { return audioRunning && lastOnsetAt > 0 && now - lastOnsetAt < MUSIC_PRESENT_S; }

    // Sustained-sound gate: music-mode triggers may START only after >= 1 s of continuous sound above the
    // noise floor (a notification ping or a UI click must not fire anything); they STOP the instant sound stops.
    private double soundSince = -1;
    private static final double SOUND_SUSTAIN_S = 1.0;
    public volatile boolean musicOn = false;

    private double lockedSince = -1;
    private static final double LOCK_STABLE_S = 2.0;

    private void soundGateTick(double now) {
        if (detector.soundPresent()) { if (soundSince < 0) soundSince = now; }
        else soundSince = -1;
        musicOn = soundSince >= 0 && now - soundSince >= SOUND_SUSTAIN_S;
        if (tracker.locked()) { if (lockedSince < 0) lockedSince = now; } else lockedSince = -1;
    }

    /** Real music for the timers: sustained sound AND a beat lock that has held for 2 s (no momentary speech locks). */
    public boolean musicStable(double now) { return musicOn && lockedSince >= 0 && now - lockedSince >= LOCK_STABLE_S; }

    /** Output history per device kind, one value per 25 ms slot (0..1 of the device cap), 3 s ring. */
    public static final int HIST_SLOTS = 120;
    public final float[] histCoyote = new float[HIST_SLOTS], histOpossum = new float[HIST_SLOTS];
    public volatile int histPos = 0;
    public volatile float strengthNormCoyote = 0, strengthNormOpossum = 0;
    public volatile int slotNowCoyote = 0, slotNowOpossum = 0;

    private void recordOutput(String kind, int strength, int cap, int[] inten) {
        float[] h = "coyote".equals(kind) ? histCoyote : histOpossum;
        float sn = cap <= 0 ? 0 : Math.min(1f, strength / (float) cap);
        int p = histPos;
        for (int j = 0; j < 4; j++) h[(p + j) % HIST_SLOTS] = sn * inten[j] / 100f;
        if ("coyote".equals(kind)) { strengthNormCoyote = sn; slotNowCoyote = inten[3]; }
        else { strengthNormOpossum = sn; slotNowOpossum = inten[3]; }
    }

    public void applyMotionSensitivity() {
        motion.sensitivity = 3.0 - (settings.motionSens - 1) * 0.2;
        motion.gateDb = 10.0 - (settings.motionSens - 1) * 0.8;
    }

    /** Reset every setting to the defaults (caps are rewritten to the Coyote on the next frame). */
    public void resetSettings() {
        boolean wasGyro = gyroMode();
        settings = Settings.reset(this);
        applySensitivity();
        if (wasGyro != gyroMode()) setMode(settings.mode);
        ensureSensors();
        stopOutput("settings reset");
        log("all settings reset to defaults");
        UiListener l = ui;
        if (l != null) main.post(l::onDevices);
    }

    public void setDeviceEnabled(String kind, boolean on) {
        settings.setEnabled(kind, on);
        settings.save(this);
        log((("coyote".equals(kind)) ? "Coyote" : "Opossum") + " pulses " + (on ? "ON" : "OFF"));
        postDevices();
    }

    // ---- BLE scan / connect ------------------------------------------------------------------

    public void startScan() {
        if (scanning) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            log("scan: 'Nearby devices' permission not granted (Settings > Apps > DG Tempo > Permissions)");
            return;
        }
        if (Build.VERSION.SDK_INT < 31
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            log("scan: location permission not granted (needed for BLE scan on Android 11 and older)");
            return;
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { log("scan: no Bluetooth adapter"); return; }
        if (!ad.isEnabled()) { log("scan: Bluetooth is OFF - turn it on"); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { log("scan: BLE scanner unavailable"); return; }
        found.clear();
        seenAll.clear();
        postDevices();
        ScanSettings.Builder sb = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
        try {
            scanner.startScan(null, sb.build(), scanCb);
        } catch (SecurityException e) {
            log("scan: permission error: " + e.getMessage());
            return;
        } catch (Exception e) {
            log("scan: " + e);
            return;
        }
        scanning = true;
        log("scanning 10 s ...");
        main.postDelayed(this::stopScan, 10_000);
    }

    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        log("scan done: " + found.size() + " DG-Lab device(s), " + seenAll.size() + " BLE devices in total");
        if (found.isEmpty()) {
            if (seenAll.isEmpty()) {
                log("saw NOTHING at all: check 'Nearby devices' permission and that Location is ON");
            } else {
                StringBuilder sb = new StringBuilder("others seen: ");
                int n = 0;
                for (Map.Entry<String, String> e : seenAll.entrySet()) {
                    if (n++ >= 12) { sb.append("..."); break; }
                    sb.append(e.getValue() == null ? "?" : e.getValue()).append(", ");
                }
                log(sb.toString());
            }
        }
        postDevices();
    }

    private static String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED: return "already started";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED: return "app registration failed (Bluetooth restricted for this user profile?)";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR: return "internal error";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED: return "feature unsupported";
            case 5: return "out of hardware resources";
            case 6: return "scanning too frequently - wait 30 s";
            default: return "code " + code;
        }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult r) {
            ScanRecord rec = r.getScanRecord();
            String name = rec == null ? null : rec.getDeviceName();
            if (name == null) try { name = r.getDevice().getName(); } catch (SecurityException ignored) {}
            String addr = r.getDevice().getAddress();
            if (!seenAll.containsKey(addr) || (seenAll.get(addr) == null && name != null)) seenAll.put(addr, name);
            String kind = Protocol.deviceKind(name == null ? null : name.trim());
            if (kind == null && rec != null && rec.getServiceUuids() != null) {
                for (ParcelUuid u : rec.getServiceUuids())
                    if (Protocol.SERVICE.equals(u.getUuid())) { kind = "coyote"; break; }
            }
            if (kind == null) return;
            Found f = found.get(addr);
            if (f == null) {
                found.put(addr, new Found(r.getDevice(), name == null ? "(no name, svc 180C)" : name, kind, r.getRssi()));
                log("found " + kind + " " + (name == null ? addr : name) + " " + r.getRssi() + " dBm");
                postDevices();
            } else {
                f.rssi = r.getRssi();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            log("scan FAILED: " + scanError(errorCode));
            postDevices();
        }
    };

    public void connect(String address) {
        Found f = found.get(address);
        if (f == null) return;
        stopScan();                                   // connecting during a scan is the #1 cause of GATT 133
        BleDevice old = devices.get(f.kind);
        if (old != null) old.disconnect();
        BleDevice d = "coyote".equals(f.kind) ? new CoyoteDevice(f.device, this) : new OpossumDevice(f.device, this);
        d.name = f.name;
        devices.put(f.kind, d);
        main.postDelayed(() -> d.connect(this, settings), 300);
        postDevices();
    }

    public void disconnect(String kind) {
        BleDevice d = devices.get(kind);
        if (d != null) d.disconnect();
    }

    @Override public void onLog(String msg) { log(msg); }

    @Override
    public void onConnected(BleDevice d) {
        armed = false;
        log(">> " + d.label + " ready. Press ARM.");
        postDevices();
        main.post(this::updateNotification);
    }

    @Override
    public void onDisconnected(BleDevice d) {
        boolean wasArmed = armed;
        armed = false;
        if (devices.get(d.kind) == d) devices.remove(d.kind);
        if (wasArmed) log("!! output stopped: " + d.label + " link lost");
        postDevices();
        main.post(this::updateNotification);
    }
}
