"""Synthetic 4/4 at 128 BPM: kick on every beat (heavier + sub-bass on beat 1), hats, syncopated
bass, 20% of kicks dropped, +-12 ms timing jitter. Checks lock, downbeat, predict-loop firing,
and the BPM->strength map. No hardware needed."""
import asyncio, os, sys, time, types
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import beatsync_socket as bs

rng = np.random.default_rng(7)
SR, BPM, SECS = 44100, 128.0, 24
period = 60.0 / BPM
OFFSET = 0.37
audio = rng.standard_normal(int(SR * SECS)).astype(np.float32) * 0.01
beat_times = np.arange(OFFSET, SECS, period)
for b, k in enumerate(beat_times):
    if b % 4 != 0 and rng.random() < 0.2:
        continue                                   # dropped kick
    k += rng.uniform(-0.012, 0.012)
    i, n = int(k * SR), int(0.15 * SR)
    env = np.exp(-np.arange(n) / (0.04 * SR))
    gain = 0.9 if b % 4 == 0 else 0.4
    audio[i:i + n] += gain * np.sin(2 * np.pi * 60 * np.arange(n) / SR * (1 + 2 * env)) * env
    if b % 4 == 0:
        m = int(0.3 * SR)
        audio[i:i + m] += 0.5 * np.sin(2 * np.pi * 45 * np.arange(m) / SR) * np.exp(-np.arange(m) / (0.1 * SR))
for k in np.arange(OFFSET + period / 2, SECS, period / 2):          # hats on off-8ths
    i, n = int(k * SR), int(0.03 * SR)
    audio[i:i + n] += 0.2 * rng.standard_normal(n) * np.exp(-np.arange(n) / (0.01 * SR))
for k in np.arange(OFFSET + 3 * period / 4, SECS, 2 * period):     # syncopated bass stabs
    i, n = int(k * SR), int(0.08 * SR)
    audio[i:i + n] += 0.3 * np.sin(2 * np.pi * 80 * np.arange(n) / SR) * np.exp(-np.arange(n) / (0.03 * SR))

loop = asyncio.new_event_loop(); asyncio.set_event_loop(loop)
q = asyncio.Queue()
det = bs.OnsetDetector(loop, q, sensitivity=2.0)
det.loop = types.SimpleNamespace(call_soon_threadsafe=lambda f, x: f(x))
orig = time.monotonic
for start in range(0, len(audio) - det.HOP, det.HOP):
    time.monotonic = lambda s=start: s / SR
    det._cb(audio[start:start + det.HOP].reshape(-1, 1), det.HOP, None, None)
time.monotonic = orig
onsets = []
while not q.empty():
    onsets.append(q.get_nowait())

# Drive tracker + predict loop together in fake time (4 ms polling, 150 ms lead)
args = types.SimpleNamespace(strength=5, max=30, bpm_lo=90.0, bpm_hi=150.0, mode="predict", lead=150,
                             sens=2.0, device=None, every_beat=False, verbose=False)
app = bs.App.__new__(bs.App)
app.args, app.auto, app.offset, app.lead, app.fired = args, True, 0, 0.15, None
app.tracker = tr = bs.TempoTracker()
fires, lock_at, bar_at, resets = [], None, None, 0
oi = 0
t = 0.0
while t < SECS:
    while oi < len(onsets) and onsets[oi][0] <= t:
        tr.add_onset(*onsets[oi]); oi += 1
    if lock_at is None and tr.locked: lock_at = t
    if bar_at is None and tr.bar_known: bar_at = t
    idx = app.predict_step(t)
    if idx is not None:
        key = (tr.generation, idx)
        if app.fired is None or key > app.fired:
            app.fired = key
            fires.append((t + 0.15, tr.bar_known))       # burst is felt ~lead later
    t += 0.004
resets = tr.generation

true_downbeats = beat_times[::4]
db_fires = [f for f, known in fires if known]
db_errs = [min(abs(f - b) for b in true_downbeats) * 1000 for f in db_fires]
print(f"onsets={len(onsets)}  locked at {lock_at}s  bar known at {bar_at}s  phase resets={resets}")
print(f"tracker: bpm={tr.bpm:.2f} weights={[round(x) for x in tr.bar_weight]}")
print(f"fires total={len(fires)}  downbeat fires={len(db_fires)}  "
      f"median |err|={np.median(db_errs):.1f}ms  max={max(db_errs):.1f}ms")
assert tr.locked and tr.bar_known
assert lock_at < 8, "took too long to lock"
assert len(db_fires) >= 6, "predict loop is not firing"
assert max(db_errs) < 40, "downbeat fires drifted off the real downbeats"
expected = sum(1 for b in true_downbeats if b > bar_at + 0.15)
assert abs(len(db_fires) - expected) <= 1, f"expected ~{expected} downbeat fires, got {len(db_fires)}"

# --every-beat: roughly one fire per beat
args.every_beat = True; app.fired = None; n_eb = 0; tr2 = bs.TempoTracker(); app.tracker = tr2
oi = 0; t = 0.0
while t < SECS:
    while oi < len(onsets) and onsets[oi][0] <= t:
        tr2.add_onset(*onsets[oi]); oi += 1
    idx = app.predict_step(t)
    if idx is not None and (app.fired is None or (tr2.generation, idx) > app.fired):
        app.fired = (tr2.generation, idx); n_eb += 1
    t += 0.004
print(f"--every-beat fires={n_eb} (beats after lock ≈ {int((SECS - 6) / period)})")
assert n_eb > 0.7 * (SECS - 6) / period

# BPM -> strength map
args.every_beat = False; app.tracker = tr
print(f"strength at {tr.bpm:.0f} BPM -> {app.target_strength()}")
tr.period = 60/80;  assert app.target_strength() == 5
tr.period = 60/120; assert app.target_strength() == 18
tr.period = 60/170; assert app.target_strength() == 30
tr.hits = 0;        assert app.target_strength() == 0
print("DETECT/DOWNBEAT/PREDICT/STRENGTH OK")
