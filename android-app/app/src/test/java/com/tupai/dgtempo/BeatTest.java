package com.tupai.dgtempo;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthetic 4/4 at 128 BPM through the PCM detector + tracker + scheduler, like the Python tests. */
public class BeatTest {
    static final int SR = 48000;
    static final double BPM = 128, PERIOD = 60 / BPM, OFFSET = 0.37;
    static final int SECS = 24;

    static short[] synth(long seed) {
        Random rng = new Random(seed);
        double[] a = new double[SR * SECS];
        for (int i = 0; i < a.length; i++) a[i] = rng.nextGaussian() * 0.01;
        int b = 0;
        for (double k = OFFSET; k < SECS; k += PERIOD, b++) {
            if (b % 4 != 0 && rng.nextDouble() < 0.2) continue;          // dropped kick
            double kk = k + (rng.nextDouble() * 0.024 - 0.012);         // +-12 ms jitter
            int i0 = (int) (kk * SR), n = (int) (0.15 * SR);
            double gain = b % 4 == 0 ? 0.9 : 0.4;
            for (int j = 0; j < n && i0 + j < a.length; j++) {
                double env = Math.exp(-j / (0.04 * SR));
                a[i0 + j] += gain * Math.sin(2 * Math.PI * 60 * j / SR * (1 + 2 * env)) * env;
            }
            if (b % 4 == 0) {
                int m = (int) (0.3 * SR);
                for (int j = 0; j < m && i0 + j < a.length; j++)
                    a[i0 + j] += 0.5 * Math.sin(2 * Math.PI * 45 * j / SR) * Math.exp(-j / (0.1 * SR));
            }
        }
        for (double k = OFFSET + PERIOD / 2; k < SECS; k += PERIOD / 2) {   // hats
            int i0 = (int) (k * SR), n = (int) (0.03 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++) a[i0 + j] += 0.2 * rng.nextGaussian() * Math.exp(-j / (0.01 * SR));
        }
        for (double k = OFFSET + 3 * PERIOD / 4; k < SECS; k += 2 * PERIOD) { // syncopated bass stabs
            int i0 = (int) (k * SR), n = (int) (0.08 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++) a[i0 + j] += 0.3 * Math.sin(2 * Math.PI * 80 * j / SR) * Math.exp(-j / (0.03 * SR));
        }
        short[] pcm = new short[a.length];
        for (int i = 0; i < a.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, a[i] * 32767 * 0.5));
        return pcm;
    }

    @Test public void locksFindsDownbeatAndSchedules() {
        short[] pcm = synth(7);
        OnsetDetector det = new OnsetDetector(SR);
        TempoTracker tr = new TempoTracker();
        List<double[]> onsets = new ArrayList<>();
        short[] buf = new short[OnsetDetector.HOP];
        for (int p = 0; p + buf.length <= pcm.length; p += buf.length) {
            System.arraycopy(pcm, p, buf, 0, buf.length);
            OnsetDetector.Onset o = det.feed(buf, buf.length, (p + buf.length) / (double) SR);
            if (o != null) onsets.add(new double[]{o.t, o.weight});
        }
        assertTrue("too few onsets: " + onsets.size(), onsets.size() > 30);

        // drive tracker + scheduler in fake time, 4 ms polling, like the app's 100 ms frames
        double latency = 0.1, burst = 0.1;
        List<Double> fires = new ArrayList<>();
        Double lockAt = null, barAt = null;
        int oi = 0;
        long[] lastFired = {-1, -1};
        for (double t = 0; t < SECS; t += 0.1) {
            while (oi < onsets.size() && onsets.get(oi)[0] <= t) { tr.addOnset(onsets.get(oi)[0], onsets.get(oi)[1]); oi++; }
            if (lockAt == null && tr.locked()) lockAt = t;
            if (barAt == null && tr.barKnown()) barAt = t;
            int[] slots = Scheduler.frameFor(tr, true, t, latency, burst, false, 0, 100);
            for (int j = 0; j < 4; j++) {
                if (slots[j] > 0) {
                    double start = t + j * 0.025;
                    if (fires.isEmpty() || start - fires.get(fires.size() - 1) > 0.15) fires.add(start + latency);
                    break;
                }
            }
        }
        System.out.println("music: lockAt=" + lockAt + " barAt=" + barAt + " confidence=" + tr.confidence + " rejectedOnsets=" + det.rejected);
        assertNotNull("never locked", lockAt);
        assertNotNull("bar never found", barAt);
        assertTrue("locked too late: " + lockAt, lockAt < 10);
        assertEquals(128, tr.bpm(), 1.5);

        List<Double> downbeatFires = new ArrayList<>();
        for (double f : fires) if (f > barAt + 0.2) downbeatFires.add(f);
        StringBuilder dbg = new StringBuilder("fires(after bar): ");
        for (double f : downbeatFires) {
            double best = 1e9;
            for (double k = OFFSET; k < SECS + 4; k += 4 * PERIOD) best = Math.min(best, Math.abs(f - k));
            dbg.append(String.format("%.2f(%+.0f) ", f, (f - OFFSET) % (4 * PERIOD) * 1000));
        }
        System.out.println(dbg + " gen=" + tr.generation + " phase=" + tr.downbeatPhase() + " weights=" + java.util.Arrays.toString(tr.barWeight));
        assertTrue("too few downbeat fires: " + downbeatFires.size(), downbeatFires.size() >= 3);
        for (double f : downbeatFires) {
            double best = 1e9;
            for (double k = OFFSET; k < SECS + 4; k += 4 * PERIOD) best = Math.min(best, Math.abs(f - k));
            assertTrue("downbeat fire off by " + best * 1000 + " ms", best < 0.04);
        }
    }

    @Test public void randomClicksNeverLock() {
        // crowd / static: loud random impulses at ~3 per second with no periodicity
        Random rng = new Random(3);
        OnsetDetector det = new OnsetDetector(SR);
        TempoTracker tr = new TempoTracker();
        double[] a = new double[SR * 40];
        for (int i = 0; i < a.length; i++) a[i] = rng.nextGaussian() * 0.02;
        double t = 0.2;
        while (t < 40) {
            int i0 = (int) (t * SR), n = (int) (0.06 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++)
                a[i0 + j] += 0.5 * Math.sin(2 * Math.PI * 70 * j / SR) * Math.exp(-j / (0.02 * SR));
            t += 0.15 + rng.nextDouble() * 0.4;
        }
        short[] pcm = new short[a.length];
        for (int i = 0; i < a.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, a[i] * 32767 * 0.5));
        short[] buf = new short[OnsetDetector.HOP];
        int onsets = 0; boolean everLocked = false; double maxConf = 0;
        for (int p = 0; p + buf.length <= pcm.length; p += buf.length) {
            System.arraycopy(pcm, p, buf, 0, buf.length);
            OnsetDetector.Onset o = det.feed(buf, buf.length, (p + buf.length) / (double) SR);
            if (o != null) { onsets++; tr.addOnset(o.t, o.weight); }
            tr.checkTimeout((p + buf.length) / (double) SR);
            if (tr.locked()) everLocked = true;
            maxConf = Math.max(maxConf, tr.confidence);
        }
        System.out.println("random clicks: onsets=" + onsets + " maxConf=" + maxConf);
        assertTrue("random clicks must be detected as onsets: " + onsets, onsets > 60);
        assertFalse("random clicks produced a beat lock (maxConf=" + maxConf + ")", everLocked);
    }

    @Test public void tempoChangeAndStopAreFollowed() {
        // 128 BPM for 14 s, then 100 BPM for 14 s, then silence
        OnsetDetector det = new OnsetDetector(SR);
        TempoTracker tr = new TempoTracker();
        Random rng = new Random(11);
        int secs = 36;
        double[] a = new double[SR * secs];
        for (int i = 0; i < a.length; i++) a[i] = rng.nextGaussian() * 0.01;
        double t = 0.3;
        while (t < 28) {
            double per = t < 14 ? 60 / 128.0 : 60 / 100.0;
            int i0 = (int) (t * SR), n = (int) (0.15 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++) {
                double env = Math.exp(-j / (0.04 * SR));
                a[i0 + j] += 0.6 * Math.sin(2 * Math.PI * 60 * j / SR * (1 + 2 * env)) * env;
            }
            t += per + rng.nextGaussian() * 0.005;
        }
        short[] pcm = new short[a.length];
        for (int i = 0; i < a.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, a[i] * 32767 * 0.5));
        short[] buf = new short[OnsetDetector.HOP];
        Double at128 = null, at100 = null, unlockedAt = null;
        for (int p = 0; p + buf.length <= pcm.length; p += buf.length) {
            double now = (p + buf.length) / (double) SR;
            System.arraycopy(pcm, p, buf, 0, buf.length);
            OnsetDetector.Onset o = det.feed(buf, buf.length, now);
            if (o != null) tr.addOnset(o.t, o.weight);
            tr.checkTimeout(now);
            if (tr.locked()) {
                if (at128 == null && Math.abs(tr.bpm() - 128) < 2) at128 = now;
                if (now > 14 && at100 == null && Math.abs(tr.bpm() - 100) < 2) at100 = now;
                if (now > 14 && now < 26 && at100 != null) assertTrue("fell back to 128 after re-lock at " + now, Math.abs(tr.bpm() - 100) < 3);
            } else if (now > 28 && unlockedAt == null) unlockedAt = now;
        }
        System.out.println("locked 128 at " + at128 + ", 100 at " + at100 + ", unlocked at " + unlockedAt + " conf=" + tr.confidence);
        assertNotNull("never locked 128", at128);
        assertNotNull("never followed the change to 100 BPM", at100);
        assertTrue("took too long to follow tempo change: " + at100, at100 < 14 + 10);
        assertNotNull("never unlocked after music stopped", unlockedAt);
        assertTrue("unlock too slow: " + unlockedAt, unlockedAt < 28 + 4);
        assertFalse(tr.locked());
    }

    @Test public void phoneMicMusicLocksAndSoundGateDropsFast() {
        // "phone mic" music: kicks with nothing below 120 Hz (fundamental 150 Hz), plus hats; then silence
        Random rng = new Random(5);
        OnsetDetector det = new OnsetDetector(SR);
        TempoTracker tr = new TempoTracker();
        int secs = 24;
        double[] a = new double[SR * secs];
        for (int i = 0; i < a.length; i++) a[i] = rng.nextGaussian() * 0.004;
        double per = 60 / 124.0;
        for (double k = 0.3; k < 16; k += per) {
            int i0 = (int) (k * SR), n = (int) (0.12 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++) {
                double env = Math.exp(-j / (0.03 * SR));
                a[i0 + j] += 0.35 * Math.sin(2 * Math.PI * 150 * j / SR * (1 + env)) * env;
            }
        }
        for (double k = 0.3 + per / 2; k < 16; k += per / 2) {
            int i0 = (int) (k * SR), n = (int) (0.02 * SR);
            for (int j = 0; j < n && i0 + j < a.length; j++) a[i0 + j] += 0.15 * rng.nextGaussian() * Math.exp(-j / (0.008 * SR));
        }
        short[] pcm = new short[a.length];
        for (int i = 0; i < a.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, a[i] * 32767 * 0.5));
        short[] buf = new short[OnsetDetector.HOP];
        Double lockAt = null, soundOffAt = null; boolean soundDuringMusic = false;
        for (int p = 0; p + buf.length <= pcm.length; p += buf.length) {
            double now = (p + buf.length) / (double) SR;
            System.arraycopy(pcm, p, buf, 0, buf.length);
            OnsetDetector.Onset o = det.feed(buf, buf.length, now);
            if (o != null) tr.addOnset(o.t, o.weight);
            tr.checkTimeout(now);
            if (lockAt == null && tr.locked()) lockAt = now;
            if (now > 5 && now < 15 && det.soundPresent()) soundDuringMusic = true;
            if (now > 16 && soundOffAt == null && !det.soundPresent()) soundOffAt = now;
        }
        System.out.println("phone-mic music: lockAt=" + lockAt + " bpm=" + tr.bpm() + " conf=" + tr.confidence + " soundOffAt=" + soundOffAt);
        assertNotNull("music with no sub-bass never locked", lockAt);
        assertTrue("locked too late: " + lockAt, lockAt < 10);
        assertTrue(soundDuringMusic);
        assertNotNull("sound gate never dropped after music stopped", soundOffAt);
        assertTrue("sound gate too slow: " + (soundOffAt - 16), soundOffAt - 16 < 0.5);
    }

    @Test public void silenceNeverLocks() {
        Random rng = new Random(1);
        OnsetDetector det = new OnsetDetector(SR);
        TempoTracker tr = new TempoTracker();
        short[] buf = new short[OnsetDetector.HOP];
        int onsets = 0;
        for (int p = 0; p < SR * 20; p += buf.length) {
            for (int i = 0; i < buf.length; i++) buf[i] = (short) (rng.nextGaussian() * 300);  // fan hiss
            OnsetDetector.Onset o = det.feed(buf, buf.length, p / (double) SR);
            if (o != null) { onsets++; tr.addOnset(o.t, o.weight); }
        }
        assertFalse("locked on noise", tr.locked());
        assertEquals(0, Scheduler.frameFor(tr, true, 5.0, 0.1, 0.1, true, 0, 100)[0]);
        assertTrue("noise onsets: " + onsets, onsets < 60);
    }

    @Test public void waveformRendering() {
        Waveforms.Waveform w = Waveforms.find(Waveforms.COYOTE, "BREATHING", 30);
        assertEquals(12, w.rows());
        assertEquals("呼吸", w.cn);
        List<double[]> bursts = new ArrayList<>();
        bursts.add(new double[]{10.0, 10.0 + w.seconds()});
        // row r of the waveform must appear in the frame at 10.0 + 0.1 r
        for (int r = 0; r < w.rows(); r++) {
            Scheduler.Slots s = Scheduler.renderSlots(bursts, 10.0 + 0.1 * r + 1e-6, w, 100, false);
            assertArrayEquals("row " + r, w.freq[r], s.freq);
            assertArrayEquals("row " + r, w.inten[r], s.inten);
        }
        // after the waveform ends (no loop) the frame is silent; with loop it wraps to row 0
        assertFalse(Scheduler.renderSlots(bursts, 10.0 + w.seconds() + 0.05, w, 100, false).any());
        List<double[]> inf = new ArrayList<>();
        inf.add(new double[]{10.0, Double.POSITIVE_INFINITY});
        assertArrayEquals(w.inten[0], Scheduler.renderSlots(inf, 10.0 + w.seconds() + 1e-6, w, 100, true).inten);
        // a newer burst restarts the waveform; intensity scales; simple waveform uses the given freq
        List<double[]> two = new ArrayList<>();
        two.add(new double[]{10.0, 12.0}); two.add(new double[]{10.5, 12.5});
        assertArrayEquals(w.freq[0], Scheduler.renderSlots(two, 10.5 + 1e-6, w, 100, false).freq);
        Scheduler.Slots half = Scheduler.renderSlots(two, 10.5 + 1e-6, Waveforms.Waveform.simple(77), 50, true);
        assertArrayEquals(new int[]{77, 77, 77, 77}, half.freq);
        assertArrayEquals(new int[]{50, 50, 50, 50}, half.inten);
        // every official waveform parses to valid B0 values
        for (Waveforms.Waveform[] t : new Waveforms.Waveform[][]{Waveforms.COYOTE, Waveforms.OPOSSUM})
            for (Waveforms.Waveform x : t) for (int r = 0; r < x.rows(); r++) for (int j = 0; j < 4; j++) {
                assertTrue(x.id, x.freq[r][j] >= 10 && x.freq[r][j] <= 240);
                assertTrue(x.id, x.inten[r][j] >= 0 && x.inten[r][j] <= 100);
            }
        assertEquals(24, Waveforms.COYOTE.length);
        assertEquals(20, Waveforms.OPOSSUM.length);
    }

    @Test public void subdivisionsAddBurstsBetweenBeats() {
        TempoTracker tr = new TempoTracker();
        tr.period = 0.5; tr.beat = 100.0; tr.hits = 20; tr.confidence = 1.0; tr.barEvidence = 20;
        int n1 = 0, n2 = 0, n4 = 0;
        for (int k = 0; k < 40; k++) {
            double t = 100.0 + k * 0.1;
            n1 += countStarts(Scheduler.burstsIn(tr, true, t, t + 0.1, 0.0, 0.05, true, 0, 1), t, t + 0.1);
            n2 += countStarts(Scheduler.burstsIn(tr, true, t, t + 0.1, 0.0, 0.05, true, 0, 2), t, t + 0.1);
            n4 += countStarts(Scheduler.burstsIn(tr, true, t, t + 0.1, 0.0, 0.05, true, 0, 4), t, t + 0.1);
        }
        assertEquals(8, n1);    // 4 s at 120 BPM = 8 beats
        assertEquals(16, n2);
        assertEquals(32, n4);
        // downbeat-only ignores subdivisions
        int nd = 0;
        for (int k = 0; k < 40; k++) { double t = 100.0 + k * 0.1; nd += countStarts(Scheduler.burstsIn(tr, true, t, t + 0.1, 0.0, 0.05, false, 0, 4), t, t + 0.1); }
        assertEquals(2, nd);
    }

    private static int countStarts(List<double[]> bursts, double t0, double t1) {
        int n = 0;
        for (double[] b : bursts) if (b[0] >= t0 - 1e-9 && b[0] < t1 - 1e-9) n++;
        return n;
    }

    @Test public void perDeviceSensitivityThresholds() {
        // eager levels need less periodicity and fewer beats; strict levels more
        assertTrue(Settings.confMin(1) > Settings.confMin(5) && Settings.confMin(5) > Settings.confMin(10));
        assertTrue(Settings.lockHits(1) >= Settings.lockHits(5) && Settings.lockHits(5) >= Settings.lockHits(10));
        assertTrue(Settings.gateDb(1) > Settings.gateDb(10) && Settings.fluxK(1) > Settings.fluxK(10));
        // a moderately periodic beat (0.35) lets an eager Opossum (7) fire but not a strict Coyote (3)
        assertTrue(0.35 >= Settings.confMin(7));
        assertFalse(0.35 >= Settings.confMin(3));
        // random-click confidence (<= 0.28 in the noise test) never satisfies any level, even the most eager
        assertTrue(Settings.confMin(10) > 0.28);
        Settings s = new Settings();
        s.coyoteSens = 3; s.vibSens = 8;
        assertEquals(8, s.eagerLevel());
        assertEquals(4, Settings.subdiv(3));
        s.coyoteBpmMin = 110; s.coyoteBpmMax = 220; s.vibBpmMin = 60; s.vibBpmMax = 100;
        assertTrue(s.bpmAllowed("coyote", 128) && !s.bpmAllowed("coyote", 100));
        assertTrue(s.bpmAllowed("opossum", 90) && !s.bpmAllowed("opossum", 128));
        assertTrue(s.bpmAllowed("opossum", 60) && s.bpmAllowed("coyote", 220));
        assertEquals(1, Settings.subdiv(0));
    }

    @Test public void motionDetectorFindsMovesNotVideoNoise() {
        // 30 fps: continuous video "texture" motion ~6 +-1, dance hits every 0.5 s as spikes to ~25 for 3 frames
        Random rng = new Random(2);
        MotionDetector md = new MotionDetector();
        int hits = 0, falseHits = 0;
        for (int f = 0; f < 30 * 20; f++) {
            double t = f / 30.0;
            boolean spike = (f % 15) < 3 && f > 30;
            double m = 6 + rng.nextGaussian() * 1.0 + (spike ? 20 : 0);
            boolean on = md.feed(t, m);
            if (on) { if ((f % 15) < 4) hits++; else falseHits++; }
        }
        assertTrue("dance hits missed: " + hits, hits >= 30);
        assertTrue("false motion onsets: " + falseHits, falseHits <= 3);
        // static screen with noise only: nothing
        MotionDetector md2 = new MotionDetector();
        int none = 0;
        for (int f = 0; f < 30 * 20; f++) if (md2.feed(f / 30.0, 0.3 + Math.abs(rng.nextGaussian()) * 0.2)) none++;
        assertEquals(0, none);
    }

    @Test public void channelLinkAndSplit() {
        Settings s = new Settings();
        // linked (default): B resolves to A's dials for both devices
        assertTrue(s.coyoteLink && s.vibLink);
        assertSame(s.cA, s.chan("coyote", 1));
        assertSame(s.oA, s.chan("opossum", 1));
        // unlink: B gets its own dials, changing them leaves A alone
        s.setLinked("coyote", false);
        assertSame(s.cB, s.chan("coyote", 1));
        s.cB.max = 77; s.cB.wave = "BREATHING";
        assertEquals(2, s.cA.max);
        assertEquals("PULSATING", s.cA.wave);
        // relink copies A over B so the pair is identical again
        s.cA.max = 33;
        s.setLinked("coyote", true);
        assertEquals(33, s.cB.max);
        assertEquals("PULSATING", s.cB.wave);
        // burstMs(kind) reports the longest active channel
        s.setLinked("opossum", false);
        s.oA.burstMs = 200; s.oB.burstMs = 900; s.vibBothMotors = true;
        assertEquals(900, s.burstMs("opossum"));
        s.vibBothMotors = false;
        assertEquals(200, s.burstMs("opossum"));
    }

    @Test public void motionMapping() {
        // tilt factor: flat inside the dead band, full at tiltMax
        assertEquals(0.0, MotionMap.tiltFactor(5, 8, 60), 1e-9);
        assertEquals(0.5, MotionMap.tiltFactor(34, 8, 60), 1e-9);
        assertEquals(1.0, MotionMap.tiltFactor(80, 8, 60), 1e-9);
        // movement: either acceleration or rotation can saturate it
        assertEquals(0.0, MotionMap.moveFactor(0, 3, 0, 3), 1e-9);
        assertEquals(1.0, MotionMap.moveFactor(6, 3, 0, 3), 1e-9);
        assertEquals(1.0, MotionMap.moveFactor(0, 3, 4, 3), 1e-9);
        // defaults: Coyote = more tilt, less movement; Opossum = more tilt, more movement
        double tilted = 1.0, flat = 0.0, moving = 1.0, still = 0.0;
        assertEquals(1.0, MotionMap.drive(tilted, MotionMap.MORE, still, MotionMap.LESS), 1e-9);    // Coyote: tilted + idle = max
        assertEquals(0.0, MotionMap.drive(tilted, MotionMap.MORE, moving, MotionMap.LESS), 1e-9);   // Coyote: moving = off
        assertEquals(0.0, MotionMap.drive(flat, MotionMap.MORE, still, MotionMap.LESS), 1e-9);      // Coyote: level = off
        assertEquals(1.0, MotionMap.drive(tilted, MotionMap.MORE, moving, MotionMap.MORE), 1e-9);   // Opossum: tilted + moving = max
        assertEquals(0.0, MotionMap.drive(tilted, MotionMap.MORE, still, MotionMap.MORE), 1e-9);    // Opossum: idle = off
        assertEquals(0.5, MotionMap.drive(0.5, MotionMap.MORE, 0.3, MotionMap.IGNORE), 1e-9);        // ignore = pass-through
        assertEquals(0.0, MotionMap.drive(1, MotionMap.IGNORE, 1, MotionMap.IGNORE), 1e-9);          // nothing selected = off
        // strength: off below 5%, else min..max
        assertEquals(0, MotionMap.strength(0.02, 5, 50));
        assertEquals(50, MotionMap.strength(1.0, 5, 50));
        assertEquals(28, MotionMap.strength(0.5, 5, 50));
    }

    @Test public void chalkCageRules() {
        CageLogic g = new CageLogic();
        CageLogic.Config c = new CageLogic.Config();
        c.locked = true; c.warnS = 3; c.shockS = 30;
        double t = 0;
        CageLogic.Out o = null;
        // inside for a while: no shock, vibration on (inside-only default)
        for (int i = 0; i < 20; i++) { o = g.step(t, c, true, 0.2, 0.0); t += 0.1; }
        assertEquals(CageLogic.INSIDE, o.state); assertFalse(o.shock); assertTrue(o.vib); assertTrue(o.inside);
        // step out: warning starts after the 1 s majority, announces "outside", vibration pauses
        String ann = null;
        for (int i = 0; i < 12; i++) { o = g.step(t, c, true, 0.2, 0.9); t += 0.1; if (o.announce != null) ann = o.announce; }
        assertEquals("outside", ann); assertEquals(CageLogic.WARNING, o.state); assertFalse(o.vib); assertFalse(o.shock);
        // stays out past the warning: shock starts and lasts 30 s even after coming back (full punishment)
        for (int i = 0; i < 35; i++) { o = g.step(t, c, true, 0.2, 0.9); t += 0.1; }
        assertEquals(CageLogic.SHOCK, o.state); assertTrue(o.shock);
        int returnedAnn = 0;
        for (int i = 0; i < 50; i++) { o = g.step(t, c, true, 0.2, 0.0); t += 0.1; if ("returned".equals(o.announce)) returnedAnn++; }   // back inside 5 s in
        assertTrue("full punishment keeps shocking after return", o.shock);
        assertTrue("return is acknowledged while the shock continues", o.returnedDuringShock);
        assertEquals("announced once, immediately", 1, returnedAnn);
        for (int i = 0; i < 260; i++) { o = g.step(t, c, true, 0.2, 0.0); t += 0.1; if ("returned".equals(o.announce)) returnedAnn++; }
        assertFalse(o.shock); assertEquals(CageLogic.INSIDE, o.state);
        assertEquals("not announced again when the shock ends", 1, returnedAnn);
        // stop-early mode: returning ends the shock
        g.reset(); c.shockMode = CageLogic.SHOCK_STOP_EARLY; t = 0;
        for (int i = 0; i < 20; i++) g.step(t += 0.1, c, true, 0.2, 0.0);
        for (int i = 0; i < 50; i++) o = g.step(t += 0.1, c, true, 0.2, 0.9);
        assertTrue(o.shock);
        ann = null;
        for (int i = 0; i < 15; i++) { o = g.step(t += 0.1, c, true, 0.2, 0.0); if (o.announce != null) ann = o.announce; }
        assertFalse(o.shock); assertEquals("returned", ann);
        // until-return mode: shock outlives shockS while still outside
        g.reset(); c.shockMode = CageLogic.SHOCK_UNTIL_RETURN; c.shockS = 1; t = 0;
        for (int i = 0; i < 20; i++) g.step(t += 0.1, c, true, 0.2, 0.0);
        for (int i = 0; i < 120; i++) o = g.step(t += 0.1, c, true, 0.2, 0.9);
        assertTrue("keeps going while outside", o.shock);
        for (int i = 0; i < 15; i++) o = g.step(t += 0.1, c, true, 0.2, 0.0);
        assertFalse(o.shock);
        // not detected counts as outside (after the box is locked); pause stops everything
        g.reset(); c.shockMode = CageLogic.SHOCK_FULL; c.shockS = 30; t = 0;
        for (int i = 0; i < 20; i++) g.step(t += 0.1, c, true, 0.2, 0.0);
        for (int i = 0; i < 50; i++) o = g.step(t += 0.1, c, true, 0.0, 0.0);
        assertFalse(o.detected); assertEquals(CageLogic.SHOCK, o.state);
        c.paused = true; o = g.step(t += 0.1, c, true, 0.0, 0.0);
        assertFalse(o.shock); assertFalse(o.vib); assertEquals(CageLogic.IDLE, o.state);
        c.paused = false; c.notDetectedIsOutside = false; g.reset();
        for (int i = 0; i < 50; i++) o = g.step(t += 0.1, c, true, 0.0, 0.0);
        assertFalse("not-detected treated as inside when the toggle is off", o.shock);
        // a hand outside (10% of the body) does not count
        g.reset();
        for (int i = 0; i < 50; i++) o = g.step(t += 0.1, c, true, 0.2, 0.10);
        assertEquals(CageLogic.INSIDE, o.state);
    }

    @Test public void testBurstAndInactive() {
        TempoTracker tr = new TempoTracker();
        // test burst ends at 10.2 and is 100 ms long -> it fills the frame starting at 10.1, not 10.0
        assertArrayEquals(new int[]{0, 0, 0, 0}, Scheduler.frameFor(tr, false, 10.0, 0.1, 0.1, true, 10.2, 100));
        assertArrayEquals(new int[]{100, 100, 100, 100}, Scheduler.frameFor(tr, false, 10.1, 0.1, 0.1, true, 10.2, 100));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Scheduler.frameFor(tr, false, 10.0, 0.1, 0.1, true, 0, 100));
    }
}
