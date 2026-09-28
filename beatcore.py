"""
Shared audio side of the Coyote beat-sync prototypes: kick onset detection + tempo/bar tracking.
Used by beatsync_socket.py (via the DG-Lab app) and beatsync_ble.py (direct BLE).
"""
import collections
import math
import time

import numpy as np

# --------------------------------------------------------------------------------------
# Audio: low-band spectral-flux onset detector (runs in the PortAudio callback thread)
# --------------------------------------------------------------------------------------

class OnsetDetector:
    SR = 44100
    HOP = 512            # 11.6 ms per callback
    WIN = 2048           # 21.5 Hz per FFT bin
    BAND = (40.0, 170.0) # kick drum region

    def __init__(self, loop, queue, sensitivity=2.0, refractory=0.18, device=None):
        self.loop = loop
        self.queue = queue
        self.k = sensitivity
        self.refractory = refractory
        self.device = device
        self.buf = np.zeros(self.WIN, dtype=np.float32)
        self.window = np.hanning(self.WIN).astype(np.float32)
        freqs = np.fft.rfftfreq(self.WIN, 1.0 / self.SR)
        self.band = (freqs >= self.BAND[0]) & (freqs <= self.BAND[1])
        self.prev_mag = np.zeros(int(self.band.sum()), dtype=np.float32)
        self.prev_flux = 0.0
        self.history = collections.deque(maxlen=90)  # ~1 s of flux values
        self.last_onset = 0.0
        self.last_audio = time.monotonic()
        self.level_db = -100.0
        self.flux = 0.0
        self.thresh = 0.0
        self.stream = None
        self.pending = None      # (onset_time, peak_weight, blocks_left) while measuring weight

    def start(self):
        import sounddevice as sd
        self.stream = sd.InputStream(
            samplerate=self.SR, blocksize=self.HOP, channels=1, dtype="float32",
            device=self.device, callback=self._cb, latency="low",
        )
        self.stream.start()

    def stop(self):
        if self.stream:
            self.stream.stop()
            self.stream.close()

    def _cb(self, indata, frames, t_info, status):
        now = time.monotonic()
        self.last_audio = now
        x = indata[:, 0]
        rms = float(np.sqrt(np.mean(x * x)) + 1e-12)
        self.level_db = 20.0 * math.log10(rms)

        self.buf = np.roll(self.buf, -frames)
        self.buf[-frames:] = x
        spec = np.abs(np.fft.rfft(self.buf * self.window))
        band = spec[self.band]
        mag = np.log1p(band * 10.0)
        flux = float(np.sum(np.maximum(0.0, mag - self.prev_mag)))
        self.prev_mag = mag

        hist = np.fromiter(self.history, dtype=np.float32) if self.history else np.zeros(1)
        mean = float(hist.mean())
        std = float(hist.std())
        thresh = mean + self.k * max(std, 0.05) + 0.02
        self.history.append(flux)
        self.flux, self.thresh = flux, thresh

        # Rising edge above adaptive threshold, outside refractory window.
        # Onset "weight" = peak low-band energy over the ~46 ms after the onset: used to guess
        # which beat of the bar is the downbeat (kick + bass on the 1 is usually heaviest).
        energy = float(np.sum(band))
        if self.pending is not None:
            t0, peak, left = self.pending
            peak, left = max(peak, energy), left - 1
            self.pending = (t0, peak, left)
            if left <= 0:
                self.pending = None
                self.loop.call_soon_threadsafe(self.queue.put_nowait, (t0, peak))
        elif flux > thresh and flux > self.prev_flux and (now - self.last_onset) > self.refractory:
            self.last_onset = now
            self.pending = (now, energy, 4)
        self.prev_flux = flux


# --------------------------------------------------------------------------------------
# Tempo + bar tracker: IOI histogram for period, PLL phase lock, 4/4 downbeat by kick weight
# --------------------------------------------------------------------------------------

class TempoTracker:
    MIN_BPM, MAX_BPM = 70.0, 180.0
    BAR = 4                      # beats per bar (4/4 assumed)
    BAR_MIN_EVIDENCE = 8         # aligned beats before we trust the downbeat guess

    def __init__(self):
        self.onsets = collections.deque(maxlen=64)
        self.period = None       # seconds
        self.beat = None         # monotonic time of the anchor beat
        self.anchor_index = 0    # integer beat number of the anchor
        self.hits = 0
        self.misses = 0
        self.bar_weight = [0.0] * self.BAR   # decayed kick weight per beat-of-bar
        self.bar_evidence = 0
        self.manual_rotate = 0
        self.phase = 0           # current downbeat guess (beat-of-bar), sticky
        self.generation = 0      # bumps on every phase reset (beat indices restart)
        self.last_hit = 0.0
        self.pending_period = None
        self.pending_count = 0

    @property
    def bpm(self):
        return 60.0 / self.period if self.period else 0.0

    @property
    def locked(self):
        return self.period is not None and self.beat is not None and self.hits >= 4

    @property
    def bar_known(self):
        return self.locked and self.bar_evidence >= self.BAR_MIN_EVIDENCE

    @property
    def downbeat_phase(self):
        return (self.phase + self.manual_rotate) % self.BAR

    def _update_phase(self):
        best = int(np.argmax(self.bar_weight))
        if best != self.phase and self.bar_weight[best] > 1.3 * self.bar_weight[self.phase]:
            self.phase = best      # only switch on a clear winner (hysteresis)

    def rotate_downbeat(self):
        self.manual_rotate = (self.manual_rotate + 1) % self.BAR

    def is_downbeat(self, index):
        return (index - self.downbeat_phase) % self.BAR == 0

    def _fold(self, ioi):
        lo, hi = 60.0 / self.MAX_BPM, 60.0 / self.MIN_BPM
        while ioi < lo:
            ioi *= 2.0
        while ioi > hi:
            ioi /= 2.0
        return ioi

    def _estimate_period(self):
        ts = [t for t, _ in self.onsets if t > self.onsets[-1][0] - 8.0]
        if len(ts) < 6:
            return None
        iois = []
        for i in range(len(ts)):
            for j in range(i + 1, len(ts)):
                d = ts[j] - ts[i]
                if d > 2.5:
                    break
                if d > 0.15:
                    iois.append(self._fold(d))
        if len(iois) < 8:
            return None
        bins = np.arange(60.0 / self.MAX_BPM, 60.0 / self.MIN_BPM + 0.01, 0.01)
        h, edges = np.histogram(iois, bins=bins)
        h = np.convolve(h, [1, 2, 1], mode="same")
        i = int(np.argmax(h))
        centre = (edges[i] + edges[i + 1]) / 2.0
        near = [d for d in iois if abs(d - centre) < 0.02]
        return float(np.mean(near)) if near else centre

    def _reset_phase(self, t):
        self.beat = t
        self.anchor_index = 0
        self.hits, self.misses = 1, 0
        self.last_hit = t
        self.bar_weight = [0.0] * self.BAR
        self.bar_evidence = 0
        self.phase = 0
        self.generation += 1
        self.pending_period, self.pending_count = None, 0

    def add_onset(self, t, weight=1.0):
        """Returns the beat index this onset aligned to, or None if it was off-grid / not locked."""
        self.onsets.append((t, weight))
        p = self._estimate_period()
        if p is None:
            return None
        if self.period is None:
            self.period = p
            self._reset_phase(t)
            return None
        if abs(p - self.period) > 0.05 * self.period:
            # Real music makes the histogram peak wobble; only accept a tempo change
            # once it has been seen 3 times in a row, otherwise ignore this estimate.
            if self.pending_period is not None and abs(p - self.pending_period) < 0.02:
                self.pending_count += 1
            else:
                self.pending_period, self.pending_count = p, 1
            if self.pending_count >= 3:
                self.period = p
                self._reset_phase(t)
            return None
        self.pending_period, self.pending_count = None, 0
        self.period = 0.9 * self.period + 0.1 * p
        n = round((t - self.beat) / self.period)
        predicted = self.beat + n * self.period
        err = t - predicted
        if abs(err) < 0.12 * self.period:
            self.beat = predicted + 0.15 * err   # gentle PLL correction
            self.anchor_index += n
            self.hits += 1
            self.misses = 0
            self.last_hit = t
            idx = self.anchor_index
            for k in range(self.BAR):            # slow decay so the guess follows the music
                self.bar_weight[k] *= 0.95
            self.bar_weight[idx % self.BAR] += weight
            self.bar_evidence += 1
            self._update_phase()
            return idx
        self.misses += 1
        if t - self.last_hit > 4 * self.period:  # nothing on the grid for a bar: re-anchor
            self._reset_phase(t)
        return None

    def next_beat_index(self, after):
        if not self.locked:
            return None
        return self.anchor_index + math.ceil((after - self.beat) / self.period)

    def beat_time(self, index):
        return self.beat + (index - self.anchor_index) * self.period

    def next_fire(self, after, every_beat=False):
        """Time + index of the next beat we should fire on: downbeat if the bar is known, else any beat."""
        k = self.next_beat_index(after)
        if k is None:
            return None, None
        if self.bar_known and not every_beat:
            while not self.is_downbeat(k):
                k += 1
        return self.beat_time(k), k
