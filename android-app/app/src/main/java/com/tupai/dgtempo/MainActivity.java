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
    private Button btnArm, btnAdvanced, btnMute, btnPhoneAudio, btnMic;
    private static final int C_TEXT = 0xFFE8EEF8, C_MUTED = 0xFF8B97AB, C_DIM = 0xFF5A6577, C_ACCENT = 0xFF00E5FF,
            C_COYOTE = 0xFFFF3D7F, C_OPOSSUM = 0xFF00E5FF, C_GO = 0xFF4DFF88, C_DANGER = 0xFFFF3B5C, C_BG = 0xFF0A0C12;
    private LinearLayout llDevices, llCoyote, llOpossum, llTiming, llAudio, llRate, llCoyoteOut, llOpossumOut, llMotion;
    private View pageMusic, pageMotion;
    private Button tabMusic, tabMotion;
    private MotionReadoutView motionReadout;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((BeatService.LocalBinder) b).get();
            svc.setUi(MainActivity.this);
            try {
                String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                svc.log("DG Tempo v" + ver);
            } catch (Exception ignored) {}
            buildControls();
            refreshPipParams();
            reportSelfWindow();
            onDevices();
            onState();
            reofferPhoneAudio();
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
        btnMute = findViewById(R.id.btnMute);
        btnMute.setOnClickListener(v -> { if (svc != null) { svc.setMuted(!svc.muted); onState(); } });
        llDevices = findViewById(R.id.llDevices);
        llCoyote = findViewById(R.id.llCoyote);
        llOpossum = findViewById(R.id.llOpossum);
        llTiming = findViewById(R.id.llTiming);
        llAudio = findViewById(R.id.llAudio);
        llRate = findViewById(R.id.llRate);
        llCoyoteOut = findViewById(R.id.llCoyoteOut);
        llOpossumOut = findViewById(R.id.llOpossumOut);
        llMotion = findViewById(R.id.llMotion);
        pageMusic = findViewById(R.id.pageMusic);
        pageMotion = findViewById(R.id.pageMotion);
        tabMusic = findViewById(R.id.tabMusic);
        tabMotion = findViewById(R.id.tabMotion);
        motionReadout = findViewById(R.id.motionReadout);
        tabMusic.setOnClickListener(v -> { if (svc != null) { svc.setMode(0); showTab(0); } });
        tabMotion.setOnClickListener(v -> { if (svc != null) { svc.setMode(1); showTab(1); } });

        btnArm.setOnClickListener(v -> {
            if (svc == null) return;
            boolean anyConnected = svc.devices.values().stream().anyMatch(d -> d.connected);
            if (!svc.armed && !anyConnected) {
                // nothing to arm: explain and offer to jump straight to scanning
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.arm_no_device_title)
                        .setMessage(R.string.arm_no_device_msg)
                        .setPositiveButton(R.string.btn_scan, (d, w) -> {
                            svc.startScan();
                            findViewById(R.id.btnScan).getParent().requestChildFocus(findViewById(R.id.btnScan), findViewById(R.id.btnScan));
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            svc.toggleArm();
        });
        findViewById(R.id.btnTest).setOnClickListener(v -> { if (svc != null) svc.testPulse(); });
        findViewById(R.id.btnPip).setOnClickListener(v -> enterPip(true));
        findViewById(R.id.btnScan).setOnClickListener(v -> { if (svc != null) svc.startScan(); });
        btnPhoneAudio = findViewById(R.id.btnPhoneAudio);
        btnMic = findViewById(R.id.btnMic);
        btnPhoneAudio.setOnClickListener(v -> requestPhoneAudio());
        btnMic.setOnClickListener(v ->
                ContextCompat.startForegroundService(this, new Intent(this, BeatService.class).setAction(BeatService.ACTION_MIC)));
        btnAdvanced.setOnClickListener(v -> {
            boolean show = llTiming.getVisibility() != View.VISIBLE;
            llTiming.setVisibility(show ? View.VISIBLE : View.GONE);
            btnAdvanced.setText(show ? R.string.btn_advanced_hide : R.string.btn_advanced_show);
        });
        setupLanguageSpinner();
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            if (svc == null) return;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.reset_title)
                    .setMessage(R.string.reset_msg)
                    .setPositiveButton(R.string.reset_confirm, (d, w) -> { svc.resetSettings(); buildControls(); onState(); })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
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
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tvVersion)).setText(getString(R.string.version_fmt, ver));
        } catch (Exception ignored) {}
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
        if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (manual) onLog("PiP: this device reports no picture-in-picture support");
            return;
        }
        try {
            boolean ok = enterPictureInPictureMode(pipParams(svc != null && svc.settings.pip));
            if (!ok && manual) onLog("PiP refused by the system: " + pipRefusalHint());
        } catch (Exception e) {
            if (manual) onLog("PiP: " + e.getMessage());
        }
    }

    /** Best guess at why enterPictureInPictureMode() returned false, for the log line. */
    private String pipRefusalHint() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return "screen is locked";
        if (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode()) return "app is in split screen - leave split screen first";
        if (!hasWindowFocus()) return "app is not in the foreground";
        return "Android " + Build.VERSION.RELEASE + " on " + Build.MANUFACTURER + " " + Build.MODEL
                + " - check Settings > Apps > DG Tempo > Picture-in-picture, and any OEM 'floating window' / background limits";
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPipParams();
        reportSelfWindow();
        reofferPhoneAudio();
    }

    /** The system ended the phone-audio share (Android 15: every screen lock): ask for it again right away. */
    private void reofferPhoneAudio() {
        if (svc == null || !svc.phoneAudioLost) return;
        svc.phoneAudioLost = false;
        if (svc.audioRunning) return;                 // user already switched to the microphone
        onLog(getString(R.string.phone_audio_reoffer));
        requestPhoneAudio();
    }

    /** Android 15 stops the phone-audio capture on every screen lock, so keep the screen awake while it runs. */
    private void keepScreenOnWhilePhoneAudio() {
        boolean on = svc != null && "phone".equals(svc.audioSource);
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        // Android 12+: the auto-enter params registered in refreshPipParams() make the system enter PiP
        // itself. Calling enterPictureInPictureMode() here as well runs mid-transition and returns false,
        // which used to log a spurious "PiP refused" even though the window appeared.
        if (Build.VERSION.SDK_INT >= 31) return;
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
        if (!isInPip) mainRoot.post(this::refitHeaderButtons);   // after the expand animation has settled
    }

    /** Back from the mini window: force the header buttons to re-measure and re-fit their auto-sized text. */
    private void refitHeaderButtons() {
        for (int id : new int[]{R.id.btnArm, R.id.btnTest, R.id.btnMute, R.id.btnPip}) {
            Button b = findViewById(id);
            if (b != null) b.setText(b.getText());
        }
        mainRoot.requestLayout();
    }

    private void updatePip() {
        TempoTracker tr = svc.tracker;
        String bpm = svc.gyroMode()
                ? String.format("%.0f°", svc.motionSensors != null && svc.motionSensors.running ? svc.motionSensors.tiltDeg : 0)
                : tr.locked() ? String.format("%.0f", tr.bpm()) : tr.bpm() > 0 ? String.format("~%.0f", tr.bpm()) : "—";
        BleDevice dc = svc.devices.get("coyote"), dop = svc.devices.get("opossum");
        Settings st = svc.settings;
        // left: just the cap. In the bar: while firing, the A/B level being sent; otherwise why not (silent / lock / range)
        String lvC = dc != null && dc.connected ? String.valueOf(st.chan("coyote", 0).max) : "--";
        String lvO = dop != null && dop.connected ? String.valueOf(st.vibFollowTempo ? st.chan("opossum", 0).max : st.chan("opossum", 0).manual) : "--";
        // firing: the A/B level being sent. Otherwise: the level the NEXT pulse would have, then why we wait / the countdown
        String nC = svc.readiness("coyote") >= 1 && dc != null
                ? String.format("A %d  B %d", Math.max(0, dc.strength), st.channelB ? Math.max(0, dc.strengthB) : 0)
                : (dc != null && dc.connected ? "→" + svc.nextLevel("coyote", 0) + (st.channelB && !st.coyoteLink ? "/" + svc.nextLevel("coyote", 1) : "") + "  " : "") + svc.readinessNote("coyote");
        String nO = svc.readiness("opossum") >= 1 && dop != null
                ? String.format("A %d  B %d", Math.max(0, dop.strength), st.vibBothMotors ? Math.max(0, dop.strengthB) : dop.actualB)
                : (dop != null && dop.connected ? "→" + svc.nextLevel("opossum", 0) + (st.vibBothMotors && !st.vibLink ? "/" + svc.nextLevel("opossum", 1) : "") + "  " : "") + svc.readinessNote("opossum");
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

    // ---- tabs ---------------------------------------------------------------------------------------

    private void showTab(int mode) {
        pageMusic.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        pageMotion.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        tabMusic.setBackgroundResource(mode == 0 ? R.drawable.bg_btn_accent : R.drawable.bg_btn_ghost);
        tabMusic.setTextColor(mode == 0 ? C_BG : C_TEXT);
        tabMotion.setBackgroundResource(mode == 1 ? R.drawable.bg_btn_accent : R.drawable.bg_btn_ghost);
        tabMotion.setTextColor(mode == 1 ? C_BG : C_TEXT);
    }

    // ---- controls ---------------------------------------------------------------------------------

    private void buildControls() {
        Settings s = svc.settings;
        llCoyote.removeAllViews(); llOpossum.removeAllViews(); llTiming.removeAllViews();
        llAudio.removeAllViews(); llRate.removeAllViews(); llCoyoteOut.removeAllViews(); llOpossumOut.removeAllViews();
        llMotion.removeAllViews();
        showTab(s.mode);
        buildMotionControls(s);
        addSwitch(llAudio, R.string.mic_fallback, R.string.x_mic_fallback, s.micFallback, v -> { s.micFallback = v; changed(); });
        addSwitch(llAudio, R.string.motion_switch, R.string.x_motion, s.screenMotion, v -> { svc.setScreenMotion(v); buildControls(); });
        if (s.screenMotion) {
            addSeek(llAudio, R.string.motion_sens, 0, R.string.end_strict, R.string.end_eager, 1, 10, s.motionSens, v -> v + " / 10", v -> { s.motionSens = v; changed(); });
            addSwitch(llAudio, R.string.motion_coyote, 0, s.motionCoyote, v -> { s.motionCoyote = v; changed(); });
            addSwitch(llAudio, R.string.motion_opossum, 0, s.motionOpossum, v -> { s.motionOpossum = v; changed(); });
        }

        // Coyote output dials (common to both tabs): one set for A+B when linked, otherwise a set per channel
        addSwitch(llCoyoteOut, R.string.coy_channel_b, R.string.x_both, s.channelB, v -> { s.channelB = v; changed(); buildControls(); });
        if (s.channelB) addSwitch(llCoyoteOut, R.string.link_ab, R.string.x_link, s.coyoteLink, v -> { s.setLinked("coyote", v); changed(); buildControls(); });
        if (s.coyoteLink || !s.channelB) {
            coyoteChannelDials(llCoyoteOut, s.cA, R.string.chan_ab);
        } else {
            coyoteChannelDials(llCoyoteOut, s.cA, R.string.chan_a);
            coyoteChannelDials(llCoyoteOut, s.cB, R.string.chan_b);
        }
        // Coyote music triggers
        addSwitch(llCoyote, R.string.coy_auto, R.string.x_auto, s.autoStrength, v -> { s.autoStrength = v; changed(); });
        subTitle(llCoyote, R.string.move_delay_title);
        addSwitch(llCoyote, R.string.move_delay, R.string.x_move_delay, s.moveDelayOn, v -> { svc.setMoveDelay(v); buildControls(); });
        if (s.moveDelayOn) {
            addSeek(llCoyote, R.string.move_delay_max, 0, R.string.end_sooner, R.string.end_rarer, 5, 300, s.moveDelayMaxS, v -> v + " s", v -> { s.moveDelayMaxS = v; changed(); });
            addSeek(llCoyote, R.string.move_delay_need, 0, R.string.end_still, R.string.end_vigorous, 5, 100, s.moveDelayNeedPct, v -> v + " %", v -> { s.moveDelayNeedPct = v; changed(); });
            addSwitch(llCoyote, R.string.move_delay_final, R.string.x_move_delay_final, s.moveDelayFinal, v -> { s.moveDelayFinal = v; changed(); buildControls(); });
            if (s.moveDelayFinal)
                addSeek(llCoyote, R.string.move_delay_shock, 0, R.string.end_short, R.string.end_long, 1, 30, s.moveDelayShockS, v -> v + " s", v -> { s.moveDelayShockS = v; changed(); }, C_POWER_COYOTE);
        }
        subTitle(llCoyote, R.string.step4);
        addSeek(llCoyote, R.string.sens_level, R.string.x_sens, R.string.end_strict, R.string.end_eager, 1, 10, s.coyoteSens, v -> v + " / 10", v -> { s.coyoteSens = v; changed(); });
        addRate(llCoyote, s.coyotePulseRate, v -> { s.coyotePulseRate = v; changed(); });
        addSeek(llCoyote, R.string.bpm_min, R.string.x_bpm_range, R.string.end_slow, R.string.end_fast, 60, 220, s.coyoteBpmMin, v -> v <= 60 ? getString(R.string.bpm_any) : v + " BPM",
                v -> { s.coyoteBpmMin = v; if (s.coyoteBpmMax < v) s.coyoteBpmMax = v; changed(); });
        addSeek(llCoyote, R.string.bpm_max, 0, R.string.end_slow, R.string.end_fast, 60, 220, s.coyoteBpmMax, v -> v >= 220 ? getString(R.string.bpm_any) : v + " BPM",
                v -> { s.coyoteBpmMax = v; if (s.coyoteBpmMin > v) s.coyoteBpmMin = v; changed(); });
        TextView tvTimer = label(R.string.coy_timer, R.string.x_timer);
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
        hint(llCoyote, R.string.x_timer);
        llCoyote.addView(spTimer);
        if (s.coyoteTimerMode == 1)
            addSeek(llCoyote, R.string.coy_max_wait, 0, R.string.end_sooner, R.string.end_rarer, 5, 300, s.coyoteMaxWaitS, v -> v + " s", v -> { s.coyoteMaxWaitS = v; changed(); });
        if (s.coyoteTimerMode == 2) {
            addSeek(llCoyote, R.string.timer_rand_min, 0, R.string.end_sooner, R.string.end_rarer, 1, 600, s.coyoteRandMinS, v -> v + " s",
                    v -> { s.coyoteRandMinS = v; if (s.coyoteRandMaxS < v) s.coyoteRandMaxS = v; changed(); });
            addSeek(llCoyote, R.string.timer_rand_max, 0, R.string.end_sooner, R.string.end_rarer, 1, 600, s.coyoteRandMaxS, v -> v + " s",
                    v -> { s.coyoteRandMaxS = v; if (s.coyoteRandMinS > v) s.coyoteRandMinS = v; changed(); });
        }

        addSwitch(llOpossumOut, R.string.vib_both_motors, R.string.x_both, s.vibBothMotors, v -> { s.vibBothMotors = v; changed(); buildControls(); });
        if (s.vibBothMotors) addSwitch(llOpossumOut, R.string.link_ab, R.string.x_link, s.vibLink, v -> { s.setLinked("opossum", v); changed(); buildControls(); });
        addSwitch(llOpossumOut, R.string.vib_follow, R.string.x_vib_follow, s.vibFollowTempo, v -> { s.vibFollowTempo = v; changed(); buildControls(); });
        if (s.vibLink || !s.vibBothMotors) {
            opossumChannelDials(llOpossumOut, s.oA, R.string.chan_ab);
        } else {
            opossumChannelDials(llOpossumOut, s.oA, R.string.chan_a);
            opossumChannelDials(llOpossumOut, s.oB, R.string.chan_b);
        }
        // Opossum music triggers
        addSwitch(llOpossum, R.string.vib_any_music, R.string.x_vib_any, s.vibAnyMusic, v -> { s.vibAnyMusic = v; changed(); buildControls(); });
        if (!s.vibAnyMusic) {
            subTitle(llOpossum, R.string.step4);
            addSeek(llOpossum, R.string.sens_level, R.string.x_sens, R.string.end_strict, R.string.end_eager, 1, 10, s.vibSens, v -> v + " / 10", v -> { s.vibSens = v; changed(); });
            addRate(llOpossum, s.vibPulseRate, v -> { s.vibPulseRate = v; changed(); });
            addSeek(llOpossum, R.string.bpm_min, R.string.x_bpm_range, R.string.end_slow, R.string.end_fast, 60, 220, s.vibBpmMin, v -> v <= 60 ? getString(R.string.bpm_any) : v + " BPM",
                    v -> { s.vibBpmMin = v; if (s.vibBpmMax < v) s.vibBpmMax = v; changed(); });
            addSeek(llOpossum, R.string.bpm_max, 0, R.string.end_slow, R.string.end_fast, 60, 220, s.vibBpmMax, v -> v >= 220 ? getString(R.string.bpm_any) : v + " BPM",
                    v -> { s.vibBpmMax = v; if (s.vibBpmMin > v) s.vibBpmMin = v; changed(); });
        }

        addSeek(llTiming, R.string.timing_latency, R.string.x_latency, R.string.end_earlier, R.string.end_later, 0, 400, s.latencyMs, v -> v + " ms", v -> { s.latencyMs = v; changed(); });
        Button rot = ghostButton(R.string.btn_rotate);
        rot.setOnClickListener(v -> { svc.tracker.rotateDownbeat(); svc.log("downbeat -> beat " + (svc.tracker.downbeatPhase() + 1)); });
        llTiming.addView(rot);
        addSeek(llTiming, R.string.timing_bpm_lo, R.string.x_tempo_points, R.string.end_slow, R.string.end_fast, 60, 200, (int) s.bpmLo, v -> v + " BPM",
                v -> { s.bpmLo = Math.min(v, s.bpmHi - 1); changed(); });
        addSeek(llTiming, R.string.timing_bpm_hi, 0, R.string.end_slow, R.string.end_fast, 61, 220, (int) s.bpmHi, v -> v + " BPM",
                v -> { s.bpmHi = Math.max(v, s.bpmLo + 1); changed(); });
        addSwitch(llTiming, R.string.pip_switch, R.string.x_pip, s.pip, v -> { s.pip = v; changed(); refreshPipParams(); });
    }

    /** GYRO tab: relations per device + calibration sliders. */
    private void buildMotionControls(Settings s) {
        hint(llMotion, R.string.x_motion_mode);
        subTitle(llMotion, R.string.sec_estim);
        addRelation(llMotion, R.string.rel_tilt, true, s.coyoteTiltRel, v -> { s.coyoteTiltRel = v; changed(); });
        addRelation(llMotion, R.string.rel_move, false, s.coyoteMoveRel, v -> { s.coyoteMoveRel = v; changed(); });
        addGyroWave(llMotion, Waveforms.COYOTE, s.cA, R.string.chan_a);
        if (s.channelB && !s.coyoteLink) addGyroWave(llMotion, Waveforms.COYOTE, s.cB, R.string.chan_b);
        subTitle(llMotion, R.string.sec_vib);
        TextView tvConst = new TextView(this);
        tvConst.setTextColor(C_TEXT); tvConst.setTextSize(14); tvConst.setPadding(0, dp(10), 0, 0);
        tvConst.setText(R.string.vib_gyro_const);
        Spinner spConst = new Spinner(this);
        ArrayAdapter<String> adConst = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.vib_gyro_follow), getString(R.string.vib_gyro_on), getString(R.string.vib_gyro_off)});
        adConst.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spConst.setAdapter(adConst);
        spConst.setSelection(s.vibGyroConst, false);
        spConst.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == s.vibGyroConst) return;
                s.vibGyroConst = pos; changed(); buildControls();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        llMotion.addView(tvConst);
        llMotion.addView(spConst);
        if (s.vibGyroConst == 0) {
            addRelation(llMotion, R.string.rel_tilt, true, s.vibTiltRel, v -> { s.vibTiltRel = v; changed(); });
            addRelation(llMotion, R.string.rel_move, false, s.vibMoveRel, v -> { s.vibMoveRel = v; changed(); });
        }
        addGyroWave(llMotion, Waveforms.OPOSSUM, s.oA, R.string.chan_a);
        if (s.vibBothMotors && !s.vibLink) addGyroWave(llMotion, Waveforms.OPOSSUM, s.oB, R.string.chan_b);
        subTitle(llMotion, R.string.step4);
        addSeek(llMotion, R.string.tilt_dead, 0, R.string.end_flat, R.string.end_upright, 0, 45, s.tiltDeadDeg, v -> v + "°",
                v -> { s.tiltDeadDeg = v; if (s.tiltMaxDeg <= v) s.tiltMaxDeg = v + 5; changed(); });
        addSeek(llMotion, R.string.tilt_full, 0, R.string.end_flat, R.string.end_upright, 5, 90, s.tiltMaxDeg, v -> v + "°",
                v -> { s.tiltMaxDeg = v; if (s.tiltDeadDeg >= v) s.tiltDeadDeg = Math.max(0, v - 5); changed(); });
        addSeek(llMotion, R.string.move_full, 0, R.string.end_still, R.string.end_vigorous, 5, 150, s.moveFullX10, v -> String.format("%.1f m/s²", v / 10.0),
                v -> { s.moveFullX10 = v; changed(); });
        addSeek(llMotion, R.string.gyro_full, 0, R.string.end_still, R.string.end_vigorous, 5, 100, s.gyroFullX10, v -> String.format("%.1f rad/s", v / 10.0),
                v -> { s.gyroFullX10 = v; changed(); });
    }

    /** Gyro-mode waveform for one channel: "same as music" (default), the simple pulse, or any library pattern. */
    private void addGyroWave(LinearLayout parent, Waveforms.Waveform[] table, Settings.ChannelCfg c, int chanRes) {
        TextView tv = new TextView(this);
        tv.setTextColor(C_TEXT); tv.setTextSize(14); tv.setPadding(0, dp(10), 0, 0);
        tv.setText(getString(R.string.gyro_wave) + "  (" + getString(chanRes) + ")");
        java.util.Locale loc = getResources().getConfiguration().getLocales().get(0);
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.wave_same));
        names.add(Waveforms.Waveform.simple(30).label(loc));
        for (Waveforms.Waveform w : table) names.add(w.label(loc) + "  (" + String.format("%.1f", w.seconds()) + " s)");
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int cur = c.gyroWave.isEmpty() ? 0 : 1 + Waveforms.indexOf(table, c.gyroWave);
        sp.setSelection(cur, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == cur) return;
                c.gyroWave = pos == 0 ? "" : pos == 1 ? Waveforms.SIMPLE_ID : table[pos - 2].id;
                changed();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(tv);
        parent.addView(sp);
    }

    private void addRelation(LinearLayout parent, int labelRes, boolean tilt, int current, IntConsumer onPick) {
        TextView tv = new TextView(this);
        tv.setTextColor(C_TEXT); tv.setTextSize(14); tv.setPadding(0, dp(10), 0, 0);
        tv.setText(labelRes);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.rel_ignore),
                getString(tilt ? R.string.rel_more_tilt : R.string.rel_more_move),
                getString(tilt ? R.string.rel_less_tilt : R.string.rel_less_move)});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(current, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (pos != current) onPick.accept(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(tv);
        parent.addView(sp);
    }

    private void updateMotionReadout() {
        MotionSensors m = svc.motionSensors;
        boolean run = m != null && m.running;
        Settings s = svc.settings;
        double dC = svc.drive("coyote"), dO = svc.drive("opossum");
        int sC = MotionMap.strength(dC, s.chan("coyote", 0).min, s.chan("coyote", 0).max);
        int sO = MotionMap.strength(dO, s.vibFollowTempo ? s.chan("opossum", 0).min : s.chan("opossum", 0).manual,
                s.vibFollowTempo ? s.chan("opossum", 0).max : s.chan("opossum", 0).manual);
        motionReadout.set(run, run ? m.tiltDeg : 0, svc.tiltF, run ? Math.max(m.accel, m.gyro) : 0, svc.moveF, dC, sC, dO, sO);
    }

    /** Coyote output dials for one channel config (strength, random, waveform, mode, intensity, freq, pulse length). */
    private void coyoteChannelDials(LinearLayout parent, Settings.ChannelCfg c, int titleRes) {
        subTitle(parent, titleRes);
        addSeek(parent, R.string.coy_max, R.string.x_max, R.string.end_gentle, R.string.end_hard, 0, 200, c.max, v -> v + " / 200",
                v -> { c.max = v; if (c.min > v) c.min = v; changed(); }, C_POWER_COYOTE);
        addSeek(parent, R.string.coy_base, R.string.x_base, R.string.end_gentle, R.string.end_hard, 0, 200, c.min, String::valueOf,
                v -> { c.min = Math.min(v, c.max); changed(); }, C_POWER_COYOTE);
        addSwitch(parent, R.string.coy_random_level, R.string.x_random, c.randomLevel, v -> { c.randomLevel = v; changed(); });
        addWave(parent, Waveforms.COYOTE, c.wave, id -> { c.wave = id; changed(); });
        hint(parent, R.string.x_wave);
        addSwitch(parent, R.string.wave_mode_beat, R.string.x_wave_mode, !c.continuous, v -> { c.continuous = !v; changed(); });
        addSeek(parent, R.string.coy_intensity, R.string.x_intensity, R.string.end_soft, R.string.end_strong, 0, 100, c.intensity, v -> v + " %", v -> { c.intensity = v; changed(); });
        addSeek(parent, R.string.coy_freq, R.string.x_freq, R.string.end_throb, R.string.end_buzz, 10, 240, c.freq, String::valueOf, v -> { c.freq = v; changed(); });
        addSeek(parent, R.string.timing_burst, R.string.x_burst, R.string.end_short, R.string.end_long, 25, 3000, c.burstMs, v -> v + " ms", v -> { c.burstMs = v; changed(); });
    }

    /** Opossum output dials for one motor config. */
    private void opossumChannelDials(LinearLayout parent, Settings.ChannelCfg c, int titleRes) {
        Settings s = svc.settings;
        subTitle(parent, titleRes);
        if (s.vibFollowTempo) {
            addSeek(parent, R.string.vib_min, 0, R.string.end_gentle, R.string.end_hard, 0, 200, c.min, String::valueOf, v -> { c.min = Math.min(v, c.max); changed(); }, C_POWER_OPOSSUM);
            addSeek(parent, R.string.vib_max, 0, R.string.end_gentle, R.string.end_hard, 0, 200, c.max, String::valueOf,
                    v -> { c.max = v; if (c.min > v) c.min = v; changed(); }, C_POWER_OPOSSUM);
        } else {
            addSeek(parent, R.string.vib_manual, 0, R.string.end_gentle, R.string.end_hard, 0, 200, c.manual, v -> v + " / 200", v -> { c.manual = v; changed(); }, C_POWER_OPOSSUM);
        }
        addWave(parent, Waveforms.OPOSSUM, c.wave, id -> { c.wave = id; changed(); });
        addSwitch(parent, R.string.wave_mode_beat, R.string.x_wave_mode, !c.continuous, v -> { c.continuous = !v; changed(); });
        addSeek(parent, R.string.vib_intensity, R.string.x_intensity, R.string.end_soft, R.string.end_strong, 0, 100, c.intensity, v -> v + " %", v -> { c.intensity = v; changed(); });
        addSeek(parent, R.string.vib_burst, R.string.x_vib_burst, R.string.end_short, R.string.end_long, 100, 3000, c.burstMs, v -> v + " ms", v -> { c.burstMs = v; changed(); });
    }

    // ---- small view factories (design system) -------------------------------------------------------

    private Button ghostButton(int textRes) {
        Button b = new Button(new android.view.ContextThemeWrapper(this, R.style.BtnGhost), null, 0);
        b.setBackgroundResource(R.drawable.bg_btn_ghost);
        b.setTextColor(C_TEXT);
        b.setText(textRes);
        b.setAllCaps(true);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void subTitle(LinearLayout parent, int textRes) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextColor(C_MUTED);
        t.setTextSize(11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(14), 0, dp(2));
        parent.addView(t);
    }

    private void hint(LinearLayout parent, int textRes) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextColor(C_MUTED);
        t.setTextSize(12);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t);
    }

    /** Label row: text + a tappable ⓘ that expands the explainer under it (returns the label view). */
    private TextView label(int labelRes, int hintRes) {
        TextView tv = new TextView(this);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(14);
        tv.setPadding(0, dp(12), 0, 0);
        tv.setText(getString(labelRes) + (hintRes != 0 ? "  ⓘ" : ""));
        if (hintRes != 0) {
            tv.setTag(R.id.tvLog, hintRes);
        }
        return tv;
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
        TextView tv = label(R.string.pulse_rate, R.string.x_rate);
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
        hint(parent, R.string.x_rate);
        parent.addView(sp);
    }

    private void addWave(LinearLayout parent, Waveforms.Waveform[] table, String current, java.util.function.Consumer<String> onPick) {
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
        parent.addView(sp);
    }

    private void addSeek(LinearLayout parent, int labelRes, int min, int max, int value, IntFunction<String> fmt, IntConsumer onChange) {
        addSeek(parent, labelRes, 0, 0, 0, min, max, value, fmt, onChange);
    }

    /**
     * Slider row: label + value (tap the value to type), an optional ⓘ explainer, −/+ steppers, and small
     * end labels under the track saying what each direction means (so nobody has to remember a sentence).
     */
    private void addSeek(LinearLayout parent, int labelRes, int hintRes, int endLeftRes, int endRightRes,
                         int min, int max, int value, IntFunction<String> fmt, IntConsumer onChange) {
        addSeek(parent, labelRes, hintRes, endLeftRes, endRightRes, min, max, value, fmt, onChange, 0);
    }

    private static final int C_POWER_COYOTE = 0xFFFF2A4A, C_POWER_OPOSSUM = 0xFF39FF7A;

    /** accent != 0: a POWER slider: track, thumb, value and steppers glow in the device colour so it stands out. */
    private void addSeek(LinearLayout parent, int labelRes, int hintRes, int endLeftRes, int endRightRes,
                         int min, int max, int value, IntFunction<String> fmt, IntConsumer onChange, int accent) {
        String label = getString(labelRes);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(12), 0, 0);
        TextView tvName = new TextView(this);
        tvName.setTextColor(C_TEXT);
        tvName.setTextSize(14);
        tvName.setText(label);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView tv = new TextView(this);            // the value, tappable
        tv.setTextColor(accent != 0 ? accent : C_ACCENT);
        tv.setTextSize(accent != 0 ? 17 : 15);
        if (accent != 0) {
            tv.setShadowLayer(14f, 0, 0, accent);    // neon glow on the number
            tvName.setTextColor(accent);
            tvName.setText("⚡ " + label);
        }
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(8), 0, dp(4), 0);
        head.addView(tvName);
        head.addView(tv);
        TextView tvHint = null;
        if (hintRes != 0) {
            TextView info = new TextView(this);
            info.setText("ⓘ");
            info.setTextColor(C_ACCENT);
            info.setTextSize(18);
            info.setPadding(dp(10), 0, dp(4), 0);
            head.addView(info);
            tvHint = new TextView(this);
            tvHint.setText(hintRes);
            tvHint.setTextColor(C_MUTED);
            tvHint.setTextSize(12);
            tvHint.setLineSpacing(dp(2), 1f);
            tvHint.setPadding(dp(10), dp(4), 0, dp(4));
            tvHint.setVisibility(View.GONE);
            final TextView h = tvHint;
            View.OnClickListener toggle = v -> h.setVisibility(h.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            info.setOnClickListener(toggle);
            tvName.setOnClickListener(toggle);
        }
        SeekBar sb = new SeekBar(this);
        sb.setMin(min); sb.setMax(max); sb.setProgress(value);
        sb.setPadding(dp(14), dp(14), dp(14), dp(14));
        if (accent != 0) {
            android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(accent);
            sb.setProgressTintList(tint);
            sb.setThumbTintList(tint);
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000));
        }
        Runnable refresh = () -> tv.setText(fmt.apply(sb.getProgress()));
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
        Button minus = stepButton("−", -1, sb, setValue), plus = stepButton("+", +1, sb, setValue);
        if (accent != 0) { minus.setTextColor(accent); plus.setTextColor(accent); }
        row.addView(minus);
        sb.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(sb);
        row.addView(plus);
        parent.addView(head);
        if (tvHint != null) parent.addView(tvHint);
        parent.addView(row);
        if (endLeftRes != 0 || endRightRes != 0) {
            LinearLayout ends = new LinearLayout(this);
            ends.setOrientation(LinearLayout.HORIZONTAL);
            ends.setPadding(dp(52), 0, dp(52), 0);
            TextView l = new TextView(this), r = new TextView(this);
            l.setText(endLeftRes != 0 ? getString(endLeftRes) : "");
            r.setText(endRightRes != 0 ? getString(endRightRes) : "");
            for (TextView e : new TextView[]{l, r}) { e.setTextColor(C_DIM); e.setTextSize(11); }
            l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            r.setGravity(Gravity.END);
            ends.addView(l);
            ends.addView(r);
            parent.addView(ends);
        }
    }

    /** −/+ button: tap = one step, press and hold = repeat (faster after a moment). */
    private Button stepButton(String text, int delta, SeekBar sb, IntConsumer setValue) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setTextColor(C_ACCENT);
        b.setBackgroundResource(R.drawable.bg_btn_ghost);
        b.setMinWidth(0); b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(42));
        lp.leftMargin = dp(2); lp.rightMargin = dp(2);
        b.setLayoutParams(lp);
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
        addSwitch(parent, labelRes, 0, value, onChange);
    }

    /** Switch row with an optional ⓘ explainer that expands under it. */
    private void addSwitch(LinearLayout parent, int labelRes, int hintRes, boolean value, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(6));
        Switch sw = new Switch(this);
        sw.setText(labelRes);
        sw.setTextColor(C_TEXT);
        sw.setTextSize(14);
        sw.setChecked(value);
        sw.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean c) -> onChange.accept(c));
        row.addView(sw);
        TextView tvHint = null;
        if (hintRes != 0) {
            TextView info = new TextView(this);
            info.setText("ⓘ");
            info.setTextColor(C_ACCENT);
            info.setTextSize(18);
            info.setPadding(dp(10), 0, dp(4), 0);
            row.addView(info);
            tvHint = new TextView(this);
            tvHint.setText(hintRes);
            tvHint.setTextColor(C_MUTED);
            tvHint.setTextSize(12);
            tvHint.setLineSpacing(dp(2), 1f);
            tvHint.setPadding(dp(10), 0, 0, dp(6));
            tvHint.setVisibility(View.GONE);
            final TextView h = tvHint;
            info.setOnClickListener(v -> h.setVisibility(h.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        }
        parent.addView(row);
        if (tvHint != null) parent.addView(tvHint);
    }

    // ---- service callbacks ----------------------------------------------------------------------------

    @Override
    public void onDevices() {
        if (svc == null) return;
        tvSource.setText("phone".equals(svc.audioSource) ? R.string.src_now_phone
                : "mic".equals(svc.audioSource) ? R.string.src_now_mic : R.string.src_now_none);
        keepScreenOnWhilePhoneAudio();
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
            tv.setTextColor(C_MUTED);
            tv.setPadding(0, dp(8), 0, dp(4));
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
            tv.setTextColor(isThis && d.connected ? C_GO : C_TEXT);
            Button b = ghostButton(isThis ? R.string.btn_disconnect : R.string.btn_connect);
            b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            b.setOnClickListener(v -> { if (isThis) svc.disconnect(f.kind); else svc.connect(f.device.getAddress()); });
            row.addView(tv);
            row.addView(b);
            llDevices.addView(row);
            if (isThis && d.connected) {
                Switch sw = new Switch(this);
                boolean en = svc.settings.enabled(f.kind);
                sw.setText(en ? R.string.dev_pulse_on : R.string.dev_pulse_off);
                sw.setChecked(en);
                sw.setTextColor(C_TEXT);
                sw.setPadding(dp(8), 0, 0, dp(8));
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
        String state = getString(!anyConn ? R.string.state_no_device : svc.armed ? R.string.state_armed : R.string.state_stopped)
                + (svc.muted ? " · " + getString(R.string.state_muted) : "");
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
                + (svc.delayNote.isEmpty() ? "" : "   " + svc.delayNote)
                + (svc.motionRunning ? String.format("\n▦ motion %.1f (floor %.1f)  onsets %d  downbeat votes %d", svc.motion.level, svc.motion.floorLevel, svc.motion.onsets, tr.evidenceHits) : ""));
        // locked: orange number. Still deciding: grey "~" number with how periodic the beats are. Nothing: dash.
        double now = java.lang.System.nanoTime() / 1e9;
        if (svc.gyroMode()) {
            updateMotionReadout();
            MotionSensors m = svc.motionSensors;
            tvBpm.setText(String.format("%.0f°  ⟲%.0f%%", m != null && m.running ? m.tiltDeg : 0, svc.moveF * 100));
            tvBpm.setTextColor(svc.armed && (svc.drive("coyote") >= 0.05 || svc.drive("opossum") >= 0.05) ? C_ACCENT : C_DIM);
        } else if (tr.locked()) {
            tvBpm.setText(String.format("%.1f BPM ●", tr.bpm()));
            tvBpm.setTextColor(now - svc.lastFlash < 0.12 ? 0xFFFFFFFF : C_ACCENT);
        } else if (tr.bpm() > 0) {
            tvBpm.setText(String.format("~%.0f BPM ○ %.0f%%", tr.bpm(), tr.confidence * 100));
            tvBpm.setTextColor(C_DIM);
        } else {
            tvBpm.setText(getString(R.string.bpm_none));
            tvBpm.setTextColor(C_DIM);
        }
        meter.setProgress((int) Math.max(0, Math.min(100, (svc.detector.levelDb + 60) * 100 / 60)));
        double nowS = java.lang.System.nanoTime() / 1e9;
        motionView.setVisibility(svc.motionRunning || svc.settings.screenMotion ? View.VISIBLE : View.GONE);
        motionView.set(svc.motionRunning, svc.motion.level, svc.motion.triggerLevel(), svc.motion.onsets, svc.motionRate(),
                svc.lastMotionAt > 0 && nowS - svc.lastMotionAt < 0.15);
        BleDevice dc = svc.devices.get("coyote"), dop = svc.devices.get("opossum");
        boolean cOn = dc != null && dc.connected, oOn = dop != null && dop.connected;
        outCoyote.setData(svc.histCoyote, svc.histPos, svc.strengthNormCoyote, "Coyote",
                cOn ? String.format("%d/%d  %3d%%", Math.max(0, dc.strength), svc.settings.chan("coyote", 0).max, svc.slotNowCoyote) : "--",
                C_COYOTE, cOn && svc.settings.coyoteEnabled);
        outOpossum.setData(svc.histOpossum, svc.histPos, svc.strengthNormOpossum, "Opossum",
                oOn ? String.format("%d/200  %3d%%", Math.max(0, dop.strength), svc.slotNowOpossum) : "--",
                C_OPOSSUM, oOn && svc.settings.opossumEnabled);
        btnArm.setText(svc.armed ? R.string.btn_stop : R.string.btn_arm);
        btnArm.setBackgroundResource(svc.armed ? R.drawable.bg_btn_danger : R.drawable.bg_btn_go);
        btnArm.setTextColor(C_BG);
        btnMute.setText(svc.muted ? R.string.btn_unmute : R.string.btn_mute);
        btnMute.setTextColor(svc.muted ? C_DANGER : C_TEXT);
        highlightSource(btnPhoneAudio, "phone".equals(svc.audioSource));
        highlightSource(btnMic, "mic".equals(svc.audioSource));
    }

    /** Green outline + tint on the audio-source button that is currently feeding the detector. */
    private void highlightSource(Button b, boolean active) {
        if (b == null) return;
        b.setBackgroundResource(active ? R.drawable.bg_btn_ghost_on : R.drawable.bg_btn_ghost);
        b.setTextColor(active ? C_GO : C_TEXT);
    }
}
