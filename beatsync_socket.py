#!/usr/bin/env python3
"""
Coyote 3.0 beat-sync prototype — SOCKET v2 route (official DG-Lab app relays to the device).

Flow:
  1. This script runs a DG-Lab WebSocket server on the LAN and prints a QR code.
  2. In the DG-Lab app: SOCKET control -> scan QR. The app stays BLE-connected to the Coyote.
  3. Mic -> low-band onset detection (kick) -> tempo + bar tracker -> one pulse burst per DOWNBEAT.

Strength (the "volume") is decided by tempo: linear map from --bpm-lo -> --strength up to
--bpm-hi -> --max. No beat lock (silence, fan noise, talking) = strength 0 and no pulses.
--max is a hard cap this script never exceeds; the app's own limit caps it too.

Keys while running:
  SPACE  STOP / re-arm (strength 0 + clear queue)   q  quit (stops output first)
  + / -  manual strength offset +1 / -1              a  toggle auto (BPM) strength on/off
  d      rotate downbeat by one beat (fix by ear)    m  toggle mode reactive <-> predict
  [ / ]  lead -10ms / +10ms (predict mode)           i / k  pulse intensity +10 / -10
  t      test pulse: one burst at --strength if 0    s  re-print QR code
"""
import argparse
import asyncio
import collections
import math
import os
import signal
import socket
import sys
import termios
import time
import tty

import numpy as np

from beatcore import OnsetDetector, TempoTracker  # noqa: E402


# --------------------------------------------------------------------------------------
# DG-Lab socket side
# --------------------------------------------------------------------------------------

def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def show_qr(uri, client):
    import qrcode
    payload = client.get_qrcode(uri)
    qr = qrcode.QRCode(border=1)
    qr.add_data(payload)
    qr.make()
    qr.print_ascii(invert=True)
    qr.make_image().save("qr.png")
    print(f"\nQR payload: {payload}\n(also saved to qr.png)  -> DG-Lab app: SOCKET control -> scan\n")


class Coyote:
    """Thin wrapper: strength set/capped here, pulses are bursts on channel A (and B if enabled)."""

    def __init__(self, client, args):
        from pydglab_ws import Channel
        self.client = client
        self.args = args
        self.channels = [Channel.A] + ([Channel.B] if args.both else [])
        self.bound = False
        self.armed = True
        self.strength = 0
        self.app_limit = 200
        self.intensity = args.intensity
        self.freq = args.freq
        self.last_burst = 0.0
        self.bursts = 0

    def burst_items(self):
        n = max(1, math.ceil(self.args.burst_ms / 100.0))
        f, i = self.freq, max(0, min(100, self.intensity))
        return [((f, f, f, f), (i, i, i, i))] * n

    async def send_burst(self):
        now = time.monotonic()
        if not (self.bound and self.armed) or now - self.last_burst < 0.12:
            return False
        if now - self.last_burst < self.args.burst_ms / 1000.0:
            for ch in self.channels:
                await self.client.clear_pulses(ch)
        self.last_burst = now
        self.bursts += 1
        items = self.burst_items()
        for ch in self.channels:
            await self.client.add_pulses(ch, *items)
        return True

    async def set_strength(self, value):
        from pydglab_ws import StrengthOperationType
        value = max(0, min(value, self.args.max, self.app_limit))
        if value == self.strength and self.bound:
            return
        self.strength = value
        if not self.bound:
            return
        for ch in self.channels:
            await self.client.set_strength(ch, StrengthOperationType.SET_TO, value)
        sys.stdout.write(f"\rstrength -> {value:<100}\n")

    async def stop(self, reason=""):
        self.armed = False
        if reason:
            print(f"\n!! STOP: {reason}")
        if not self.bound:
            return
        try:
            self.strength = -1   # force a real send of 0
            await self.set_strength(0)
            for ch in self.channels:
                await self.client.clear_pulses(ch)
        except Exception as e:  # noqa: BLE001
            print(f"stop() send failed: {e}")


# --------------------------------------------------------------------------------------
# Main app
# --------------------------------------------------------------------------------------

class App:
    def __init__(self, args):
        self.args = args
        self.mode = args.mode
        self.lead = args.lead / 1000.0
        self.auto = True            # BPM decides strength
        self.offset = 0             # manual +/- on top of the BPM strength
        self.loop = asyncio.get_event_loop()
        self.onsets = asyncio.Queue()
        self.tracker = TempoTracker()
        self.detector = OnsetDetector(self.loop, self.onsets, args.sens, device=args.device)
        self.coyote = None
        self.quit = asyncio.Event()
        self.flash = 0.0
        self.fired = None           # (generation, index) of the last beat we fired on
        self.link = None
        self.was_locked = False
        self.was_bar_known = False

    # ---- strength policy --------------------------------------------------------------

    def target_strength(self):
        """BPM -> strength. Not locked -> 0. Linear between (bpm_lo, strength) and (bpm_hi, max)."""
        a = self.args
        if not self.tracker.locked:
            return 0
        if not self.auto:
            return a.strength + self.offset
        x = (self.tracker.bpm - a.bpm_lo) / max(1.0, a.bpm_hi - a.bpm_lo)
        x = max(0.0, min(1.0, x))
        return int(round(a.strength + x * (a.max - a.strength))) + self.offset

    async def strength_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.5)
            if self.coyote.bound and self.coyote.armed:
                await self.coyote.set_strength(self.target_strength())

    # ---- socket / app link ------------------------------------------------------------

    async def link_task(self, client, uri):
        from pydglab_ws import RetCode, StrengthData
        show_qr(uri, client)
        print("Waiting for the DG-Lab app to bind...")
        while not self.quit.is_set():
            await client.ensure_bind()
            if not self.coyote.bound:
                self.coyote.bound = True
                print("\n>> App bound. Strength follows BPM once a beat is locked.")
                self.coyote.strength = -1
                await self.coyote.set_strength(0)
            data = await client.recv_data()
            if isinstance(data, StrengthData):
                self.coyote.app_limit = data.a_limit
                if data.a != self.coyote.strength:
                    print(f"\n   app reports A={data.a} (limit {data.a_limit}) B={data.b} (limit {data.b_limit})")
            elif data == RetCode.CLIENT_DISCONNECTED:
                self.coyote.bound = False
                self.coyote.armed = False
                print("\n!! App disconnected — output assumed stopped. Re-scan QR to rebind, then press SPACE to arm.")
                show_qr(uri, client)
                await client.rebind()
            elif data in (RetCode.RECIPIENT_NOT_FOUND, RetCode.MESSAGE_TOO_LONG, RetCode.SERVER_INTERNAL_ERROR):
                print(f"\n   socket error: {data.name}")

    # ---- beat logic -------------------------------------------------------------------

    def log(self, msg):
        sys.stdout.write(f"\r{msg:<110}\n")

    def should_fire(self, idx):
        return self.tracker.is_downbeat(idx) or not self.tracker.bar_known or self.args.every_beat

    async def fire(self, index):
        key = (self.tracker.generation, index)
        if self.fired is not None and key <= self.fired:
            return
        self.fired = key
        if await self.coyote.send_burst():
            self.flash = time.monotonic()
            if self.args.verbose:
                self.log(f"burst  beat#{index} ({'downbeat' if self.tracker.bar_known else 'any beat'})")

    def note_transitions(self):
        tr = self.tracker
        if tr.locked != self.was_locked:
            self.was_locked = tr.locked
            self.log(f"{'LOCKED' if tr.locked else 'UNLOCKED'}  {tr.bpm:.1f} BPM")
        if tr.bar_known != self.was_bar_known:
            self.was_bar_known = tr.bar_known
            if tr.bar_known:
                self.log(f"bar found: firing on beat {tr.downbeat_phase + 1} of 4 (press d to rotate)")

    async def beat_task(self):
        while not self.quit.is_set():
            t, weight = await self.onsets.get()
            idx = self.tracker.add_onset(t, weight)
            if self.args.verbose:
                self.log(f"onset  w={weight:6.1f} -> {'beat#%d' % idx if idx is not None else 'off-grid'}")
            self.note_transitions()
            if self.mode == "reactive" and idx is not None and self.should_fire(idx):
                await self.fire(idx)

    def predict_step(self, now):
        """Index of the beat to fire now, or None. Fires --lead before the next beat/downbeat."""
        if not self.tracker.locked:
            return None
        when, idx = self.tracker.next_fire(now, self.args.every_beat)
        if when is not None and when - self.lead <= now:
            return idx
        return None

    async def predict_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.004)
            self.note_transitions()
            if self.mode != "predict":
                continue
            idx = self.predict_step(time.monotonic())
            if idx is not None:
                await self.fire(idx)

    async def watchdog_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.5)
            if self.coyote.armed and time.monotonic() - self.detector.last_audio > 2.0:
                await self.coyote.stop("no audio for 2 s (mic lost?)")

    # ---- UI ---------------------------------------------------------------------------

    async def status_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.1)
            d, tr, c = self.detector, self.tracker, self.coyote
            lvl = max(0, min(24, int((d.level_db + 60) / 2.5)))
            beat = "●" if time.monotonic() - self.flash < 0.12 else " "
            state = ("ARMED" if c.armed else "STOPPED") if c.bound else "NO APP"
            bar = f"bar:{tr.downbeat_phase + 1}" if tr.bar_known else "bar:?"
            auto = "auto" if self.auto else "man "
            line = (f"\r{beat} [{state:7}] {self.mode:8} bpm={tr.bpm:5.1f}{'*' if tr.locked else ' '} {bar} "
                    f"str={c.strength:3d}/{min(self.args.max, c.app_limit):3d}{auto}{self.offset:+d} "
                    f"int={c.intensity:3d} lead={int(self.lead*1000):3d}ms n={c.bursts:4d} |{'#'*lvl:<24}|")
            sys.stdout.write(line)
            sys.stdout.flush()

    def on_key(self):
        ch = os.read(sys.stdin.fileno(), 1).decode(errors="ignore")
        c = self.coyote
        if ch == " ":
            if c.armed:
                asyncio.ensure_future(c.stop("user"))
            else:
                c.armed = True
                print("\n>> re-armed")
        elif ch == "q":
            self.quit.set()
        elif ch in "+=":
            self.offset += 1
        elif ch == "-":
            self.offset -= 1
        elif ch == "a":
            self.auto = not self.auto
        elif ch == "d":
            self.tracker.rotate_downbeat()
        elif ch == "[":
            self.lead = max(0.0, self.lead - 0.01)
        elif ch == "]":
            self.lead = min(0.5, self.lead + 0.01)
        elif ch == "i":
            c.intensity = min(100, c.intensity + 10)
        elif ch == "k":
            c.intensity = max(0, c.intensity - 10)
        elif ch == "m":
            self.mode = "predict" if self.mode == "reactive" else "reactive"
        elif ch == "t":
            asyncio.ensure_future(self.test_pulse())
        elif ch == "s" and self.link:
            show_qr(*self.link)

    async def test_pulse(self):
        """Pipeline check without music: strength to --strength if it is 0, then one burst."""
        if self.coyote.strength <= 0:
            await self.coyote.set_strength(self.args.strength)
        ok = await self.coyote.send_burst()
        self.log(f"test pulse {'sent' if ok else 'NOT sent (app not bound / stopped)'} at strength {self.coyote.strength}")

    # ---- run --------------------------------------------------------------------------

    async def run(self):
        from pydglab_ws import DGLabWSServer
        host_ip = self.args.host_ip or lan_ip()
        uri = f"ws://{host_ip}:{self.args.port}"
        fd = sys.stdin.fileno()
        old_tty = termios.tcgetattr(fd)
        async with DGLabWSServer("0.0.0.0", self.args.port, heartbeat_interval=5) as server:
            client = server.new_local_client()
            self.link = (uri, client)
            self.coyote = Coyote(client, self.args)
            self.loop.add_signal_handler(signal.SIGINT, self.quit.set)
            self.detector.start()
            tty.setcbreak(fd)
            self.loop.add_reader(fd, self.on_key)
            tasks = [asyncio.ensure_future(t) for t in (
                self.link_task(client, uri), self.beat_task(), self.predict_task(),
                self.strength_task(), self.watchdog_task(), self.status_task())]
            try:
                await self.quit.wait()
            finally:
                self.loop.remove_reader(fd)
                termios.tcsetattr(fd, termios.TCSADRAIN, old_tty)
                print("\nShutting down: strength 0 + clear queue")
                await self.coyote.stop()
                for t in tasks:
                    t.cancel()
                self.detector.stop()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", type=int, default=5678)
    p.add_argument("--host-ip", help="LAN IP to embed in the QR (auto-detected by default)")
    p.add_argument("--mic", "--device", dest="device", type=int,
                   help="microphone index from --list-devices")
    p.add_argument("--list-devices", action="store_true")
    p.add_argument("--strength", type=int, default=5, help="strength at --bpm-lo and below (start LOW)")
    p.add_argument("--max", type=int, default=30, help="strength at --bpm-hi and above; hard cap")
    p.add_argument("--bpm-lo", type=float, default=90.0, help="BPM that maps to --strength")
    p.add_argument("--bpm-hi", type=float, default=150.0, help="BPM that maps to --max")
    p.add_argument("--intensity", type=int, default=100, help="waveform intensity of a beat pulse 0-100")
    p.add_argument("--freq", type=int, default=30, help="waveform frequency byte 10-240")
    p.add_argument("--burst-ms", type=int, default=100, help="pulse burst length, rounded up to 100ms items")
    p.add_argument("--both", action="store_true", help="drive channel B as well as A")
    p.add_argument("--mode", choices=["reactive", "predict"], default="predict")
    p.add_argument("--lead", type=int, default=150, help="predict mode: fire this many ms before predicted beat")
    p.add_argument("--sens", type=float, default=2.0, help="onset threshold in std-devs (lower = more sensitive)")
    p.add_argument("--every-beat", action="store_true", help="fire on every beat, not just the downbeat")
    p.add_argument("--verbose", action="store_true", help="log every onset and burst")
    args = p.parse_args()

    if args.list_devices:
        import sounddevice as sd
        print(sd.query_devices())
        return
    if not 0 <= args.strength <= args.max <= 200:
        sys.exit("need 0 <= --strength <= --max <= 200")
    if not 10 <= args.freq <= 240:
        sys.exit("--freq must be 10-240")
    if args.bpm_lo >= args.bpm_hi:
        sys.exit("--bpm-lo must be below --bpm-hi")

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(App(args).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
