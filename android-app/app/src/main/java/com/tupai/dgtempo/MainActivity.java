package com.tupai.dgtempo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public final class MainActivity extends AppCompatActivity implements BeatService.UiListener {
    private static final int REQ_PERMS = 1, REQ_PROJECTION = 2;
    private BeatService svc;
    private TextView tvStatus, tvBpm, tvLog, tvSource;
    private ProgressBar meter;
    private OutputView outCoyote, outOpossum;
    private PipView pipView;
    private MotionView motionView;
    private View mainRoot;
    private boolean inPip = false;
    private Button btnArm, btnAdvanced;
    private LinearLayout llDevices, llCoyote, llOpossum, llTiming, llAudio, llRate;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((BeatService.LocalBinder) b).get();
            svc.setUi(MainActivity.this);
            try {
                String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                svc.log("DG Tempo v" + ver + " (wide-band detection, sound gate, PiP with levels)");
            } catch (Exception ignored) {}
            buildControls();
            refreshPipParams();
            reportSelfWindow();
            onDevices();
            onState();
            StringBuilder sb = new StringBuilder();
            synchronized (svc.logLines) { for (String l : svc.logLines) sb.append(l).append('\n'); }
            tvLog.setText(sb.toString());
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvStatus);
        tvBpm = findViewById(R.id.tvBpm);
        tvLog = findViewById(R.id.tvLog);
        tvSource = findViewById(R.id.tvSource);
        meter = findViewById(R.id.meter);
        outCoyote = findViewById(R.id.outCoyote);
        outOpossum = findViewById(R.id.outOpossum);
        pipView = findViewById(R.id.pipView);
        motionView = findViewById(R.id.motionView);
        mainRoot = findViewById(R.id.mainRoot);
        btnArm = findViewById(R.id.btnArm);
        btnAdvanced = findViewById(R.id.btnAdvanced);
        llDevices = findViewById(R.id.llDevices);
        llCoyote = findViewById(R.id.llCoyote);
        llOpossum = findViewById(R.id.llOpossum);
        llTiming = findViewById(R.id.llTiming);
        llAudio = findViewById(R.id.llAudio);
        llRate = findViewById(R.id.llRate);

        btnArm.setOnClickListener(v -> { if (svc != null) svc.toggleArm(); });
        findViewById(R.id.btnTest).setOnClickListener(v -> { if (svc != null) svc.testPulse(); });
        findViewById(R.id.btnPip).setOnClickListener(v -> enterPip(true));
        findViewById(R.id.btnScan).setOnClickListener(v -> { if (svc != null) svc.startScan(); });
        findViewById(R.id.btnPhoneAudio).setOnClickListener(v -> requestPhoneAudio());
        findViewById(R.id.btnMic).setOnClickListener(v ->
                ContextCompat.startForegroundService(this, new Intent(this, BeatService.class).setAction(BeatService.ACTION_MIC)));
        btnAdvanced.setOnClickListener(v -> {
            boolean show = llTiming.getVisibility() != View.VISIBLE;
            llTiming.setVisibility(show ? View.VISIBLE : View.GONE);
            btnAdvanced.setText(show ? R.string.btn_advanced_hide : R.string.btn_advanced_show);
        });
        setupLanguageSpinner();
        findViewById(R.id.btnQuit).setOnClickListener(v -> {
            startService(new Intent(this, BeatService.class).setAction(BeatService.ACTION_QUIT));
            finish();
        });
        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) { onLog(getString(R.string.battery_ok)); return; }
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        });
        requestPermissionsThenStart();
    }

    private void setupLanguageSpinner() {
        Spinner sp = findViewById(R.id.spLanguage);
        String[] tags = {"en", "zh", "ja"};
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.lang_en), getString(R.string.lang_zh), getString(R.string.lang_ja)});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        String cur = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (cur.isEmpty()) cur = getResources().getConfiguration().getLocales().get(0).getLanguage();
        int idx = cur.startsWith("zh") ? 1 : cur.startsWith("ja") ? 2 : 0;
        sp.setSelection(idx, false);
        final int current = idx;
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == current) return;            // initial callback (or re-pick of the same language): nothing to do
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[pos]));
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ---- picture-in-picture: small readout over other apps ------------------------------------------

    private boolean pipAllowed() {
        try {
            android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return true;
        }
    }

    private android.app.PictureInPictureParams pipParams(boolean autoEnter) {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(new android.util.Rational(5, 3));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(autoEnter);      // gesture navigation on Android 12+ enters PiP through this, not onUserLeaveHint
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Keep the auto-enter params registered whenever PiP is wanted (Android 12+ needs this for swipe-home). */
    private void refreshPipParams() {
        if (Build.VERSION.SDK_INT < 26 || svc == null) return;
        try { setPictureInPictureParams(pipParams(svc.settings.pip)); } catch (Exception ignored) {}
    }

    private void enterPip(boolean manual) {
        if (Build.VERSION.SDK_INT < 26 || inPip) return;
        if (!pipAllowed()) {
            onLog("PiP is disabled for this app in Android settings - opening the setting");
            if (manual) {
                try {
                    startActivity(new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    onLog("open PiP setting: " + e.getMessage());
                }
            }
            return;
        }
        try {
            boolean ok = enterPictureInPictureMode(pipParams(svc != null && svc.settings.pip));
            if (!ok) onLog("PiP refused by the system (check Settings > Apps > DG Tempo > Picture-in-picture)");
        } catch (Exception e) {
            onLog("PiP: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPipParams();
        reportSelfWindow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        reportSelfWindow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (svc != null) svc.setSelfWindow(false, null);
    }

    /** Tell the service where this app is on the shared screen so its own pixels are ignored. */
    private void reportSelfWindow() {
        if (svc == null) return;
        if (inPip) {
            View dv = getWindow().getDecorView();
            int[] loc = new int[2];
            dv.getLocationOnScreen(loc);
            svc.setSelfWindow(false, new android.graphics.Rect(loc[0], loc[1], loc[0] + dv.getWidth(), loc[1] + dv.getHeight()));
        } else {
            svc.setSelfWindow(hasWindowFocus() || !isFinishing() && !inPip && getWindow().getDecorView().isShown(), null);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (svc != null && svc.settings.pip) enterPip(false);
    }

    private final android.os.Handler pipTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pipTick = new Runnable() {
        @Override public void run() {
            if (!inPip) return;
            if (svc != null) { updatePip(); reportSelfWindow(); }
            pipTimer.postDelayed(this, 200);
        }
    };

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        inPip = isInPip;
        pipView.setVisibility(isInPip ? View.VISIBLE : View.GONE);
        mainRoot.setVisibility(isInPip ? View.GONE : View.VISIBLE);
        pipTimer.removeCallbacks(pipTick);
        if (isInPip) pipTimer.post(pipTick);        // own refresh loop: independent of service callbacks while paused
        reportSelfWindow();
    }

    private void updatePip() {
        TempoTracker tr = svc.tracker;
        String bpm = tr.locked() ? String.format("%.0f", tr.bpm()) : tr.bpm() > 0 ? String.format("~%.0f", tr.bpm()) : "—";
        BleDevice dc = svc.devices.get("coyote"), dop = svc.devices.get("opossum");
        Settings st = svc.settings;
        // left: just the cap. In the bar: while firing, the A/B level being sent; otherwise why not (silent / lock / range)
        String lvC = dc != null && dc.connected ? String.valueOf(st.coyoteMax) : "--";
        String lvO = dop != null && dop.connected ? String.valueOf(st.vibFollowTempo ? st.vibMax : st.vibManual) : "--";
        // firing: the A/B level being sent. Otherwise: the level the NEXT pulse would have, then why we wait / the countdown
        String nC = svc.readiness("coyote") >= 1 && dc != null
                ? String.format("A %d  B %d", Math.max(0, dc.strength), st.channelB ? Math.max(0, dc.strength) : 0)
                : (dc != null && dc.connected ? "→" + svc.nextLevel("coyote") + "  " : "") + svc.readinessNote("coyote");
        String nO = svc.readiness("opossum") >= 1 && dop != null
                ? String.format("A %d  B %d", Math.max(0, dop.strength), st.vibBothMotors ? Math.max(0, dop.strength) : dop.actualB)
                : (dop != null && dop.connected ? "→" + svc.nextLevel("opossum") + "  " : "") + svc.readinessNote("opossum");
        double nowP = java.lang.System.nanoTime() / 1e9;
        pipView.setMotion(svc.motionRunning, svc.motion.level, svc.motion.triggerLevel(), svc.motionRate(),
                svc.lastMotionAt > 0 && nowP - svc.lastMotionAt < 0.15);
        pipView.set(bpm, tr.locked(), svc.detector.soundPresent(),
                svc.readiness("coyote"), nC, lvC,
                svc.readiness("opossum"), nO, lvO);
    }

    private void requestPhoneAudio() {
        if (Build.VERSION.SDK_INT < 29) { onLog("phone audio needs Android 10+"); return; }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PROJECTION) return;
        if (result != Activity.RESULT_OK || data == null) { onLog(getString(R.string.phone_audio_denied)); return; }
        Intent i = new Intent(this, BeatService.class).setAction(BeatService.ACTION_PHONE_AUDIO)
                .putExtra(BeatService.EXTRA_CODE, result).putExtra(BeatService.EXTRA_DATA, data);
        ContextCompat.startForegroundService(this, i);
    }

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private void requestPermissionsThenStart() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions())
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) startAndBind();
        else ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAndBind();
        } else {
            tvStatus.setText(R.string.perm_mic_needed);
        }
    }

    private void startAndBind() {
        Intent i = new Intent(this, BeatService.class).setAction(BeatService.ACTION_START);
        ContextCompat.startForegroundService(this, i);
        bindService(new Intent(this, BeatService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        boolean exiting = isFinishing();          // back out of the app / PiP window closed (not a rotation or language change)
        if (svc != null) { svc.setUi(null); unbindService(conn); svc = null; }
        if (exiting) startService(new Intent(this, BeatService.class).setAction(BeatService.ACTION_QUIT));
        super.onDestroy();
    }

    // ---- controls ---------------------------------------------------------------------------------

    private void buildControls() {
        Settings s = svc.settings;
        llCoyote.removeAllViews(); llOpossum.removeAllViews(); llTiming.removeAllViews();
        llAudio.removeAllViews(); llRate.removeAllViews();
        addSwitch(llAudio, R.string.mic_fallback, s.micFallback, v -> { s.micFallback = v; changed(); });
        addSwitch(llAudio, R.string.motion_switch, s.screenMotion, v -> { svc.setScreenMotion(v); buildControls(); });
        if (s.screenMotion) {
            addSeek(llAudio, R.string.motion_sens, 1, 10, s.motionSens, v -> v + " / 10", v -> { s.motionSens = v; changed(); });
            addSwitch(llAudio, R.string.motion_coyote, s.motionCoyote, v -> { s.motionCoyote = v; changed(); });
            addSwitch(llAudio, R.string.motion_opossum, s.motionOpossum, v -> { s.motionOpossum = v; changed(); });
        }

        // Coyote: strict by default; Opossum: eager by default. Each device decides on its own when the beat is solid enough.
        addSeek(llCoyote, R.string.sens_level, 1, 10, s.coyoteSens, v -> v + " / 10", v -> { s.coyoteSens = v; changed(); });
        addRate(llCoyote, s.coyotePulseRate, v -> { s.coyotePulseRate = v; changed(); });
        addSeek(llCoyote, R.string.bpm_min, 60, 220, s.coyoteBpmMin, v -> v <= 60 ? getString(R.string.bpm_any) : v + " BPM",
                v -> { s.coyoteBpmMin = v; if (s.coyoteBpmMax < v) s.coyoteBpmMax = v; changed(); });
        TextView tvTimer = new TextView(this);
        tvTimer.setTextColor(0xFFDDDDDD);
        tvTimer.setPadding(0, 16, 0, 0);
        tvTimer.setText(R.string.coy_timer);
        Spinner spTimer = new Spinner(this);
        ArrayAdapter<String> adTimer = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.timer_off), getString(R.string.timer_peak), getString(R.string.timer_random)});
        adTimer.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTimer.setAdapter(adTimer);
        spTimer.setSelection(s.coyoteTimerMode, false);
        spTimer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == s.coyoteTimerMode) return;
                s.coyoteTimerMode = pos; changed(); buildControls();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        llCoyote.addView(tvTimer);
        llCoyote.addView(spTimer);
        if (s.coyoteTimerMode == 1)
            addSeek(llCoyote, R.string.coy_max_wait, 5, 300, s.coyoteMaxWaitS, v -> v + " s", v -> { s.coyoteMaxWaitS = v; changed(); });
        if (s.coyoteTimerMode == 2) {
            addSeek(llCoyote, R.string.timer_rand_min, 1, 600, s.coyoteRandMinS, v -> v + " s",
                    v -> { s.coyoteRandMinS = v; if (s.coyoteRandMaxS < v) s.coyoteRandMaxS = v; changed(); });
            addSeek(llCoyote, R.string.timer_rand_max, 1, 600, s.coyoteRandMaxS, v -> v + " s",
                    v -> { s.coyoteRandMaxS = v; if (s.coyoteRandMinS > v) s.coyoteRandMinS = v; changed(); });
        }
        addSeek(llCoyote, R.string.bpm_max, 60, 220, s.coyoteBpmMax, v -> v >= 220 ? getString(R.string.bpm_any) : v + " BPM",
                v -> { s.coyoteBpmMax = v; if (s.coyoteBpmMin > v) s.coyoteBpmMin = v; changed(); });

        addWave(llCoyote, Waveforms.COYOTE, s.coyoteWave, id -> { s.coyoteWave = id; changed(); });
        addSwitch(llCoyote, s.coyoteContinuous ? R.string.wave_mode_cont : R.string.wave_mode_beat, s.coyoteContinuous,
                v -> { s.coyoteContinuous = v; changed(); buildControls(); });
        addSeek(llCoyote, R.string.coy_max, 0, 200, s.coyoteMax, v -> v + " / 200",
                v -> { s.coyoteMax = v; if (s.coyoteMin > v) s.coyoteMin = v; changed(); });
        addSeek(llCoyote, R.string.coy_base, 0, 200, s.coyoteMin, String::valueOf,
                v -> { s.coyoteMin = Math.min(v, s.coyoteMax); changed(); });
        addSwitch(llCoyote, R.string.coy_auto, s.autoStrength, v -> { s.autoStrength = v; changed(); });
        addSwitch(llCoyote, R.string.coy_random_level, s.coyoteRandomLevel, v -> { s.coyoteRandomLevel = v; changed(); });
        addSeek(llCoyote, R.string.coy_intensity, 0, 100, s.intensity, v -> v + " %", v -> { s.intensity = v; changed(); });
        addSeek(llCoyote, R.string.coy_freq, 10, 240, s.freq, String::valueOf, v -> { s.freq = v; changed(); });
        addSwitch(llCoyote, R.string.coy_channel_b, s.channelB, v -> { s.channelB = v; changed(); });
        addSwitch(llTiming, R.string.pip_switch, s.pip, v -> { s.pip = v; changed(); refreshPipParams(); });
        Button pipBtn = new Button(this);
        pipBtn.setText(R.string.btn_pip_now);
        pipBtn.setOnClickListener(v -> enterPip(true));
        llTiming.addView(pipBtn);

        addSwitch(llOpossum, R.string.vib_both_motors, s.vibBothMotors, v -> { s.vibBothMotors = v; changed(); });
        addSwitch(llOpossum, R.string.vib_any_music, s.vibAnyMusic, v -> { s.vibAnyMusic = v; changed(); buildControls(); });
        if (!s.vibAnyMusic) {
            addSeek(llOpossum, R.string.sens_level, 1, 10, s.vibSens, v -> v + " / 10", v -> { s.vibSens = v; changed(); });
            addRate(llOpossum, s.vibPulseRate, v -> { s.vibPulseRate = v; changed(); });
            addSeek(llOpossum, R.string.bpm_min, 60, 220, s.vibBpmMin, v -> v <= 60 ? getString(R.string.bpm_any) : v + " BPM",
                    v -> { s.vibBpmMin = v; if (s.vibBpmMax < v) s.vibBpmMax = v; changed(); });
            addSeek(llOpossum, R.string.bpm_max, 60, 220, s.vibBpmMax, v -> v >= 220 ? getString(R.string.bpm_any) : v + " BPM",
                    v -> { s.vibBpmMax = v; if (s.vibBpmMin > v) s.vibBpmMin = v; changed(); });
        }
        addSeek(llOpossum, R.string.vib_intensity, 0, 100, s.vibIntensity, v -> v + " %", v -> { s.vibIntensity = v; changed(); });
        addWave(llOpossum, Waveforms.OPOSSUM, s.opossumWave, id -> { s.opossumWave = id; changed(); });
        addSwitch(llOpossum, s.opossumContinuous ? R.string.wave_mode_cont : R.string.wave_mode_beat, s.opossumContinuous,
                v -> { s.opossumContinuous = v; changed(); buildControls(); });
        addSeek(llOpossum, R.string.vib_burst, 100, 3000, s.vibBurstMs, v -> v + " ms", v -> { s.vibBurstMs = v; changed(); });
        addSwitch(llOpossum, R.string.vib_follow, s.vibFollowTempo, v -> { s.vibFollowTempo = v; changed(); });
        addSeek(llOpossum, R.string.vib_manual, 0, 200, s.vibManual, v -> v + " / 200", v -> { s.vibManual = v; changed(); });
        addSeek(llOpossum, R.string.vib_min, 0, 200, s.vibMin, String::valueOf, v -> { s.vibMin = Math.min(v, s.vibMax); changed(); });
        addSeek(llOpossum, R.string.vib_max, 0, 200, s.vibMax, String::valueOf,
                v -> { s.vibMax = v; if (s.vibMin > v) s.vibMin = v; changed(); });

        addSeek(llTiming, R.string.timing_latency, 0, 400, s.latencyMs, v -> v + " ms", v -> { s.latencyMs = v; changed(); });
        addSeek(llTiming, R.string.timing_burst, 25, 3000, s.burstMs, v -> v + " ms", v -> { s.burstMs = v; changed(); });
        Button rot = new Button(this);
        rot.setText(R.string.btn_rotate);
        rot.setOnClickListener(v -> { svc.tracker.rotateDownbeat(); svc.log("downbeat -> beat " + (svc.tracker.downbeatPhase() + 1)); });
        llTiming.addView(rot);
        addSeek(llTiming, R.string.timing_bpm_lo, 60, 200, (int) s.bpmLo, v -> v + " BPM",
                v -> { s.bpmLo = Math.min(v, s.bpmHi - 1); changed(); });
        addSeek(llTiming, R.string.timing_bpm_hi, 61, 220, (int) s.bpmHi, v -> v + " BPM",
                v -> { s.bpmHi = Math.max(v, s.bpmLo + 1); changed(); });
    }

    private void changed() { if (svc != null) svc.settingsChanged(); }

    /** e.g. "C: go 110-180 BPM" - which tempo range triggers this device, and whether it fires right now. */
    private String fireLine(String tag, String kind) {
        Settings s = svc.settings;
        if ("opossum".equals(kind) && s.vibAnyMusic)
            return tag + ":" + (svc.deviceActive(kind) ? "▶" : "·") + " " + getString(R.string.any_sound);
        String range = s.bpmMin(kind) <= 60 && s.bpmMax(kind) >= 220 ? getString(R.string.bpm_any)
                : (s.bpmMin(kind) <= 60 ? "≤" + s.bpmMax(kind) : s.bpmMax(kind) >= 220 ? "≥" + s.bpmMin(kind)
                : s.bpmMin(kind) + "-" + s.bpmMax(kind)) + " BPM";
        String why = svc.deviceActive(kind) ? "▶" : !svc.tracker.locked() ? "·"
                : !s.bpmAllowed(kind, svc.tracker.bpm()) ? "✗bpm" : "✗lock";
        return tag + ":" + why + " " + range;
    }

    private void addRate(LinearLayout parent, int current, IntConsumer onPick) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFDDDDDD);
        tv.setText(R.string.pulse_rate);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.pulse_rate_downbeat), getString(R.string.pulse_rate_beat),
                getString(R.string.pulse_rate_2), getString(R.string.pulse_rate_4)});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(current);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onPick.accept(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(tv);
        parent.addView(sp);
    }

    private void addWave(LinearLayout parent, Waveforms.Waveform[] table, String current, java.util.function.Consumer<String> onPick) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFDDDDDD);
        tv.setText(R.string.wave_pick);
        java.util.Locale loc = getResources().getConfiguration().getLocales().get(0);
        List<String> names = new ArrayList<>();
        names.add(Waveforms.Waveform.simple(30).label(loc));
        for (Waveforms.Waveform w : table) names.add(w.label(loc) + "  (" + String.format("%.1f", w.seconds()) + " s)");
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Waveforms.indexOf(table, current));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                onPick.accept(pos == 0 ? Waveforms.SIMPLE_ID : table[pos - 1].id);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(tv);
        parent.addView(sp);
    }

    private void addSeek(LinearLayout parent, int labelRes, int min, int max, int value, IntFunction<String> fmt, IntConsumer onChange) {
        String label = getString(labelRes);
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFDDDDDD);
        tv.setPadding(0, 16, 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min); sb.setMax(max); sb.setProgress(value);
        sb.setPadding(24, 20, 24, 20);
        Runnable refresh = () -> tv.setText(label + ":  " + fmt.apply(sb.getProgress()));
        refresh.run();
        IntConsumer setValue = v -> {
            int nv = Math.max(min, Math.min(max, v));
            if (nv == sb.getProgress()) return;
            sb.setProgress(nv);           // triggers onProgressChanged(fromUser=false) below
            onChange.accept(nv);
            refresh.run();
        };
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                refresh.run();
                if (fromUser) onChange.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        // tap the value to type an exact number
        tv.setOnClickListener(v -> {
            android.widget.EditText et = new android.widget.EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(sb.getProgress()));
            et.selectAll();
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(label + "  (" + min + " – " + max + ")")
                    .setView(et)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        try { setValue.accept(Integer.parseInt(et.getText().toString().trim())); } catch (NumberFormatException ignored) {}
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(stepButton("−", -1, sb, setValue));
        sb.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(sb);
        row.addView(stepButton("+", +1, sb, setValue));
        parent.addView(tv);
        parent.addView(row);
    }

    /** −/+ button: tap = one step, press and hold = repeat (faster after a moment). */
    private Button stepButton(String text, int delta, SeekBar sb, IntConsumer setValue) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(48)));
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] ticks = {0};
        Runnable[] rep = new Runnable[1];
        rep[0] = () -> {
            setValue.accept(sb.getProgress() + delta * (ticks[0] > 15 ? 5 : 1));
            ticks[0]++;
            h.postDelayed(rep[0], 90);
        };
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    ticks[0] = 0;
                    setValue.accept(sb.getProgress() + delta);
                    h.postDelayed(rep[0], 400);
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(rep[0]);
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addSwitch(LinearLayout parent, int labelRes, boolean value, java.util.function.Consumer<Boolean> onChange) {
        Switch sw = new Switch(this);
        sw.setText(labelRes);
        sw.setTextColor(0xFFDDDDDD);
        sw.setChecked(value);
        sw.setPadding(0, 14, 0, 14);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean c) -> onChange.accept(c));
        parent.addView(sw);
    }

    // ---- service callbacks ----------------------------------------------------------------------------

    @Override
    public void onDevices() {
        if (svc == null) return;
        tvSource.setText("phone".equals(svc.audioSource) ? R.string.src_now_phone
                : "mic".equals(svc.audioSource) ? R.string.src_now_mic : R.string.src_now_none);
        llDevices.removeAllViews();
        // connected / connecting devices first (they stop advertising, so a new scan would drop them), then scan results
        java.util.List<BeatService.Found> rows = new ArrayList<>();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (BleDevice d : svc.devices.values()) {
            BeatService.Found f = svc.found.get(d.address());
            if (f == null) f = new BeatService.Found(d.device, d.name.isEmpty() ? d.label : d.name, d.kind, 0);
            rows.add(f); shown.add(d.address());
        }
        for (BeatService.Found f : svc.found.values()) if (!shown.contains(f.device.getAddress())) rows.add(f);
        if (rows.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(svc.scanning ? R.string.scanning : R.string.no_devices);
            llDevices.addView(tv);
        }
        for (BeatService.Found f : rows) {
            BleDevice d = svc.devices.get(f.kind);
            boolean isThis = d != null && d.address().equals(f.device.getAddress());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);
            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            String state = !isThis ? "" : d.connected
                    ? "  ✓ " + getString(R.string.connected) + (d.battery >= 0 ? " " + d.battery + "%" : "")
                    : "  " + getString(R.string.connecting);
            tv.setText(getString("coyote".equals(f.kind) ? R.string.dev_coyote : R.string.dev_opossum)
                    + "\n" + f.name + (f.rssi != 0 ? "  " + f.rssi + " dBm" : "") + state);
            tv.setTextColor(isThis && d.connected ? 0xFF8BC34A : 0xFFDDDDDD);
            Button b = new Button(this);
            b.setText(isThis ? R.string.btn_disconnect : R.string.btn_connect);
            b.setOnClickListener(v -> { if (isThis) svc.disconnect(f.kind); else svc.connect(f.device.getAddress()); });
            row.addView(tv);
            row.addView(b);
            llDevices.addView(row);
            if (isThis && d.connected) {
                Switch sw = new Switch(this);
                boolean en = svc.settings.enabled(f.kind);
                sw.setText(en ? R.string.dev_pulse_on : R.string.dev_pulse_off);
                sw.setChecked(en);
                sw.setTextColor(0xFFDDDDDD);
                sw.setPadding(24, 0, 0, 12);
                sw.setOnCheckedChangeListener((CompoundButton cb, boolean c) -> svc.setDeviceEnabled(f.kind, c));
                llDevices.addView(sw);
            }
        }
    }

    @Override
    public void onLog(String line) {
        tvLog.append(line + "\n");
        String t = tvLog.getText().toString();
        int lines = t.split("\n").length;
        if (lines > 16) tvLog.setText(t.substring(t.indexOf('\n') + 1));
    }

    @Override
    public void onState() {
        if (svc == null) return;
        if (inPip) { updatePip(); return; }
        TempoTracker tr = svc.tracker;
        boolean anyConn = svc.devices.values().stream().anyMatch(d -> d.connected);
        String state = getString(!anyConn ? R.string.state_no_device : svc.armed ? R.string.state_armed : R.string.state_stopped);
        StringBuilder devs = new StringBuilder();
        for (BleDevice d : svc.devices.values()) if (d.connected)
            devs.append(d.label.charAt(0)).append(':').append(Math.max(0, d.strength)).append('(').append(d.actualA).append(") ");
        String bar = tr.barKnown() ? getString(R.string.bar_n, tr.downbeatPhase() + 1) : getString(R.string.bar_unknown);
        tvStatus.setText(getString(R.string.status_fmt, state, devs, bar, svc.bursts)
                + (svc.audioRunning ? "" : "  " + getString(R.string.state_mic_off))
                + String.format("\n%s ♪ %d   lock %.0f%%   %s %.0f dB (+%.0f over floor)", svc.detector.soundPresent() ? "♫" : "·",
                svc.onsetCount, tr.confidence * 100, "phone".equals(svc.audioSource) ? "in" : "mic",
                svc.detector.levelDb, svc.detector.musicDb)
                + "\n" + fireLine("C", "coyote") + "   " + fireLine("O", "opossum")
                + (svc.timerNote.isEmpty() ? "" : "   ⏱ " + svc.timerNote)
                + (svc.motionRunning ? String.format("\n▦ motion %.1f (floor %.1f)  onsets %d  downbeat votes %d", svc.motion.level, svc.motion.floorLevel, svc.motion.onsets, tr.evidenceHits) : ""));
        // locked: orange number. Still deciding: grey "~" number with how periodic the beats are. Nothing: dash.
        double now = java.lang.System.nanoTime() / 1e9;
        if (tr.locked()) {
            tvBpm.setText(String.format("%.1f BPM ●", tr.bpm()));
            tvBpm.setTextColor(now - svc.lastFlash < 0.12 ? 0xFFFFFFFF : 0xFFFF7A00);
        } else if (tr.bpm() > 0) {
            tvBpm.setText(String.format("~%.0f BPM ○  %.0f%%", tr.bpm(), tr.confidence * 100));
            tvBpm.setTextColor(0xFF9E9E9E);
        } else {
            tvBpm.setText(getString(R.string.bpm_none));
            tvBpm.setTextColor(0xFF9E9E9E);
        }
        meter.setProgress((int) Math.max(0, Math.min(100, (svc.detector.levelDb + 60) * 100 / 60)));
        double nowS = java.lang.System.nanoTime() / 1e9;
        motionView.setVisibility(svc.motionRunning || svc.settings.screenMotion ? View.VISIBLE : View.GONE);
        motionView.set(svc.motionRunning, svc.motion.level, svc.motion.triggerLevel(), svc.motion.onsets, svc.motionRate(),
                svc.lastMotionAt > 0 && nowS - svc.lastMotionAt < 0.15);
        BleDevice dc = svc.devices.get("coyote"), dop = svc.devices.get("opossum");
        boolean cOn = dc != null && dc.connected, oOn = dop != null && dop.connected;
        outCoyote.setData(svc.histCoyote, svc.histPos, svc.strengthNormCoyote, "Coyote",
                cOn ? String.format("%d/%d  %3d%%", Math.max(0, dc.strength), svc.settings.coyoteMax, svc.slotNowCoyote) : "--",
                0xFFFF7A00, cOn && svc.settings.coyoteEnabled);
        outOpossum.setData(svc.histOpossum, svc.histPos, svc.strengthNormOpossum, "Opossum",
                oOn ? String.format("%d/200  %3d%%", Math.max(0, dop.strength), svc.slotNowOpossum) : "--",
                0xFF4FC3F7, oOn && svc.settings.opossumEnabled);
        btnArm.setText(svc.armed ? R.string.btn_stop : R.string.btn_arm);
        btnArm.setBackgroundColor(svc.armed ? 0xFFD32F2F : 0xFF2E7D32);
    }
}
