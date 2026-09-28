#!/usr/bin/env python3
"""
DG-Lab beat-sync prototype — DIRECT BLE route (no DG-Lab app in the loop).

Drives up to two devices from one beat clock:
  * Coyote 3.0 pulse host   (BLE name 47L121000)  — e-stim, B0 waveform frames + BF soft cap
  * Opossum 负鼠 vibrator   (BLE name 47L127000)  — vibration, same B0 frame shape, B3 intensity

Flow:
  1. Scan, connect to whatever targets are found, subscribe to 0x150B, write caps.
  2. Mic -> kick onset detection -> tempo + bar tracker (beatcore.py).
  3. A jitter-free 100 ms loop. Every frame is 4 x 25 ms slots; the scheduler looks ahead and
     switches ON exactly the slots that cover each predicted downbeat, --latency ms early to
     cancel BLE + device delay. Between beats the slots are 0 = no output.

Strength follows tempo: --bpm-lo -> min, --bpm-hi -> max, per device
(Coyote: --strength/--max, Opossum: --vib-min/--vib-max). No beat lock = 0 and no pulses.

Keys while running:
  SPACE  STOP / re-arm (strength 0, silent frames)  q  quit (zero frame, disconnect)
  + / -  manual strength offset +1 / -1 (Coyote)    a  toggle auto (BPM) strength on/off
  d      rotate downbeat by one beat (fix by ear)    e  toggle every-beat / downbeat-only
  [ / ]  latency -10ms / +10ms (earlier / later)     i / k  pulse intensity +10 / -10
  t      test pulse: one burst at min strength       v  toggle verbose onset log
"""
import argparse
import asyncio
import os
import signal
import sys
import termios
import time
import tty

from beatcore import OnsetDetector, TempoTracker

UUID_WRITE = "0000150a-0000-1000-8000-00805f9b34fb"
UUID_NOTIFY = "0000150b-0000-1000-8000-00805f9b34fb"
UUID_BATTERY = "00001500-0000-1000-8000-00805f9b34fb"
SERVICE_UUID = "0000180c-0000-1000-8000-00805f9b34fb"
COYOTE_NAME = "47L121000"
OPOSSUM_NAME = "47L127000"
FRAME_S = 0.100
SLOT_S = 0.025


# --------------------------------------------------------------------------------------
# Protocol helpers (pure functions, unit-tested)
# --------------------------------------------------------------------------------------

def build_b0(seq, mode, sa, sb, fa, ia, fb, ib):
    """Coyote 20-byte B0. mode: high 2 bits = A, low 2 bits = B (00 keep, 01 +, 10 -, 11 set)."""
    assert 0 <= seq <= 15 and 0 <= mode <= 15
    assert len(fa) == len(ia) == len(fb) == len(ib) == 4
    return bytes([0xB0, (seq << 4) | mode, sa & 0xFF, sb & 0xFF, *fa, *ia, *fb, *ib])


def build_bf(cap_a, cap_b, freq_bal_a=160, freq_bal_b=160, int_bal_a=0, int_bal_b=0):
    """Coyote 7-byte BF: soft strength caps (0-200), freq balance, intensity balance."""
    return bytes([0xBF, cap_a, cap_b, freq_bal_a, freq_bal_b, int_bal_a, int_bal_b])


def parse_b1(data):
    """Coyote B1 notification -> (seq, strength_a, strength_b) or None."""
    if len(data) >= 4 and data[0] == 0xB1:
        return data[1], data[2], data[3]
    return None


def build_opossum_b0(ia, ib):
    """Opossum 20-byte B0: 0xB0 + 7x00 + A slots(4) + 4x00 + B slots(4); slot values 0-100."""
    assert len(ia) == len(ib) == 4
    return bytes([0xB0, *([0] * 7), *ia, *([0] * 4), *ib])


def build_opossum_b3(a, b):
    """Opossum intensity 0-200 per channel; 0xFF leaves that channel unchanged."""
    return bytes([0xB3, a & 0xFF, b & 0xFF])


def build_opossum_b2(a, b):
    """Opossum on-screen intensity display update after a B3."""
    frame = bytes([0xB2, 0xFF, 0xFF, 0x00]) + bytes([0xFF] * 12) + bytes([0x08, 0x09, a & 0xFF, b & 0xFF])
    assert len(frame) == 20
    return frame


def parse_opossum_b3(data):
    if len(data) >= 3 and data[0] == 0xB3:
        return data[1], data[2]
    return None


def slot_pattern(frame_start, bursts, on):
    """Which of the 4 slots starting at frame_start overlap any (start, end) burst by >= half a slot.
    Returns a 4-list of 0/`on`."""
    out = [0, 0, 0, 0]
    for j in range(4):
        s0 = frame_start + j * SLOT_S
        s1 = s0 + SLOT_S
        for b0, b1 in bursts:
            if min(s1, b1) - max(s0, b0) >= SLOT_S / 2:
                out[j] = on
                break
    return out


def device_kind(device, adv):
    """'coyote' / 'opossum' / None from a scan result. macOS sometimes hides the name; then we
    fall back to the advertised 0x180C service (which both DG-Lab devices share)."""
    names = [n for n in (device.name, getattr(adv, "local_name", None)) if n]
    for n in names:
        if n.startswith(COYOTE_NAME[:6]):
            return "coyote"
        if n.startswith(OPOSSUM_NAME[:6]):
            return "opossum"
    if any(u.lower() == SERVICE_UUID for u in (getattr(adv, "service_uuids", None) or [])):
        return "coyote"
    return None


# --------------------------------------------------------------------------------------
# BLE devices
# --------------------------------------------------------------------------------------

class BleDevice:
    kind = "?"
    label = "?"

    def __init__(self, args, on_disconnect, log):
        self.args = args
        self.on_disconnect = on_disconnect
        self.log = log
        self.client = None
        self.connected = False
        self.write_kwargs = {}
        self.actual_a = 0
        self.actual_b = 0
        self.battery = None
        self.frames = 0
        self.strength = -1           # last strength we commanded (-1 = force a write)

    async def connect(self, target):
        from bleak import BleakClient
        self.client = BleakClient(target, disconnected_callback=self._disconnected, timeout=20.0)
        await self.client.connect()
        char = self.client.services.get_characteristic(UUID_WRITE)
        if char is None:
            raise RuntimeError("write characteristic 0x150A not found")
        self.write_kwargs = {"response": "write-without-response" not in char.properties}
        await self.client.start_notify(UUID_NOTIFY, self._notify)
        try:
            b = await self.client.read_gatt_char(UUID_BATTERY)
            self.battery = b[0] if b else None
        except Exception:  # noqa: BLE001
            self.battery = None
        self.connected = True
        self.strength = -1
        await self.after_connect()
        self.log(f"{self.label} connected ({'write-no-rsp' if not self.write_kwargs['response'] else 'write-rsp'}), "
                 f"battery={self.battery}%")

    def _disconnected(self, _client):
        self.connected = False
        self.on_disconnect(self)

    async def write(self, data):
        await self.client.write_gatt_char(UUID_WRITE, data, **self.write_kwargs)

    async def after_connect(self):
        pass

    def _notify(self, _sender, data):
        pass

    def strength_change_allowed(self):
        return True

    def target_strength(self, x, offset):
        """x = 0..1 position between --bpm-lo and --bpm-hi."""
        raise NotImplementedError

    async def send_frame(self, strength, set_strength, ia, ib):
        raise NotImplementedError

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                await self.send_frame(0, True, [0] * 4, [0] * 4)
                await self.client.disconnect()
            except Exception as e:  # noqa: BLE001
                print(f"{self.label} disconnect: {e}")


class CoyoteDevice(BleDevice):
    kind = "coyote"
    label = "Coyote"

    def __init__(self, *a):
        super().__init__(*a)
        self.pending_seq = None
        self.pending_since = 0.0
        self.seq = 0

    @property
    def cap(self):
        return self.args.max

    async def after_connect(self):
        cap_b = self.args.max if self.args.both else 0
        await self.write(build_bf(self.args.max, cap_b, self.args.freq_balance, self.args.freq_balance,
                                  self.args.intensity_balance, self.args.intensity_balance))
        self.log(f"Coyote BF soft cap written: A={self.args.max} B={cap_b}")

    def _notify(self, _sender, data):
        p = parse_b1(bytes(data))
        if p is None:
            return
        seq, a, b = p
        if seq != 0 and seq == self.pending_seq:
            self.pending_seq = None
        if seq == 0 and (a, b) != (self.actual_a, self.actual_b):
            self.log(f"Coyote wheel: A={a} B={b}")
        self.actual_a, self.actual_b = a, b

    def strength_change_allowed(self):
        if self.pending_seq is None:
            return True
        if time.monotonic() - self.pending_since > 0.5:   # B1 never came; don't wedge
            self.pending_seq = None
            return True
        return False

    def target_strength(self, x, offset):
        a = self.args
        v = int(round(a.strength + x * (a.max - a.strength))) + offset
        return max(0, min(a.max, v))

    async def send_frame(self, strength, set_strength, ia, ib):
        if not self.connected:
            return
        seq, mode = 0, 0
        if set_strength:
            self.seq = self.seq % 15 + 1
            seq, mode = self.seq, 0b1111
            self.pending_seq, self.pending_since = seq, time.monotonic()
            self.strength = strength
        sb = strength if self.args.both else 0
        f = [self.args.freq] * 4
        await self.write(build_b0(seq, mode, strength, sb, f, ia, f, ib))
        self.frames += 1


class OpossumDevice(BleDevice):
    kind = "opossum"
    label = "Opossum"

    @property
    def cap(self):
        return self.args.vib_max

    def _notify(self, _sender, data):
        p = parse_opossum_b3(bytes(data))
        if p is None:
            return
        a, b = p
        if (a, b) != (self.actual_a, self.actual_b) and a != self.strength:
            self.log(f"Opossum button: A={a} B={b}")
        self.actual_a, self.actual_b = a, b

    def target_strength(self, x, offset):
        a = self.args
        v = int(round(a.vib_min + x * (a.vib_max - a.vib_min)))
        return max(0, min(a.vib_max, v))

    async def send_frame(self, strength, set_strength, ia, ib):
        if not self.connected:
            return
        if set_strength:
            sb = strength if self.args.both else 0xFF
            await self.write(build_opossum_b3(strength, sb))
            await self.write(build_opossum_b2(strength, strength if self.args.both else self.actual_b))
            self.strength = strength
        await self.write(build_opossum_b0(ia, ib))
        self.frames += 1


# --------------------------------------------------------------------------------------
# Main app
# --------------------------------------------------------------------------------------

class App:
    def __init__(self, args):
        self.args = args
        self.latency = args.latency / 1000.0
        self.auto = True
        self.offset = 0
        self.every_beat = args.every_beat
        self.verbose = args.verbose
        self.intensity = args.intensity
        self.loop = asyncio.get_event_loop()
        self.onsets = asyncio.Queue()
        self.tracker = TempoTracker()
        self.detector = OnsetDetector(self.loop, self.onsets, args.sens, device=args.device)
        self.devices = []
        if "coyote" in args.targets:
            self.devices.append(CoyoteDevice(args, self._on_disconnect, self.log))
        if "opossum" in args.targets:
            self.devices.append(OpossumDevice(args, self._on_disconnect, self.log))
        self.quit = asyncio.Event()
        self.armed = False
        self.flash = 0.0
        self.late_ticks = 0
        self.bursts = 0
        self.test_burst_until = 0.0
        self.was_locked = False
        self.was_bar_known = False
        self.scanning = False

    def log(self, msg):
        sys.stdout.write(f"\r{msg:<110}\n")
        sys.stdout.flush()

    def _on_disconnect(self, dev):
        self.armed = False
        if not self.quit.is_set():
            self.log(f"!! {dev.label} BLE disconnected — output stops with the link. Reconnecting...")

    @property
    def connected_devices(self):
        return [d for d in self.devices if d.connected]

    # ---- strength policy --------------------------------------------------------------

    def tempo_x(self):
        """0..1 position of the current tempo between --bpm-lo and --bpm-hi; None = no output."""
        a = self.args
        if not self.armed or not self.tracker.locked:
            return None
        if not self.auto:
            return 0.0
        x = (self.tracker.bpm - a.bpm_lo) / max(1.0, a.bpm_hi - a.bpm_lo)
        return max(0.0, min(1.0, x))

    def target_for(self, dev):
        x = self.tempo_x()
        if x is None:
            return 0
        return dev.target_strength(x, self.offset if dev.kind == "coyote" else 0)

    # ---- lookahead slot scheduler -----------------------------------------------------

    def bursts_in(self, t0, t1):
        """(start, end) output intervals that touch [t0, t1), from predicted beats/downbeats.
        Each burst starts --latency early so it is felt on the beat."""
        out = []
        if self.test_burst_until > t0:
            out.append((self.test_burst_until - self.args.burst_ms / 1000.0, self.test_burst_until))
        if not (self.armed and self.tracker.locked):
            return out
        blen = self.args.burst_ms / 1000.0
        after = t0 + self.latency - blen
        for _ in range(8):
            when, idx = self.tracker.next_fire(after, self.every_beat)
            if when is None:
                break
            start = when - self.latency
            if start >= t1:
                break
            out.append((start, start + blen))
            after = when + 1e-3
        return out

    def frame_for(self, tick):
        """Slot intensities for the frame sent at `tick`."""
        bursts = self.bursts_in(tick, tick + FRAME_S)
        on = max(0, min(100, self.intensity))
        return slot_pattern(tick, bursts, on)

    async def sender_task(self):
        """The 100 ms loop. Never skips a frame; resyncs if the event loop stalled."""
        next_tick = time.monotonic()
        while not self.quit.is_set():
            now = time.monotonic()
            if next_tick > now:
                await asyncio.sleep(next_tick - now)
            tick = next_tick
            next_tick += FRAME_S
            now = time.monotonic()
            if now - tick > 0.03:
                self.late_ticks += 1
                if now - tick > 0.3:
                    next_tick = now + FRAME_S
            devs = self.connected_devices
            if not devs:
                continue
            ia = self.frame_for(tick)
            ib = ia if self.args.both else [0] * 4
            if any(ia):
                if time.monotonic() - self.flash > 0.15:
                    self.bursts += 1
                self.flash = time.monotonic()
                if self.verbose:
                    self.log(f"frame slots={ia}")
            sends = []
            for d in devs:
                target = self.target_for(d)
                set_str = target != d.strength and d.strength_change_allowed()
                if set_str:
                    self.log(f"{d.label} strength -> {target}")
                strength = target if set_str else max(0, d.strength)
                sends.append(self._send(d, strength, set_str, ia, ib))
            await asyncio.gather(*sends)

    async def _send(self, dev, strength, set_str, ia, ib):
        try:
            await dev.send_frame(strength, set_str, ia, ib)
        except Exception as e:  # noqa: BLE001
            self.log(f"{dev.label} write failed: {e}")
            dev.connected = False

    # ---- audio -> tracker ----------------------------------------------------------------

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
            if self.verbose:
                self.log(f"onset  w={weight:7.1f} -> {'beat#%d' % idx if idx is not None else 'off-grid'}")
            self.note_transitions()

    # ---- BLE link management -------------------------------------------------------------

    async def link_task(self):
        """Keep every wanted device connected. One scan finds both; each connects independently,
        so a missing Opossum never blocks the Coyote (or vice versa)."""
        from bleak import BleakScanner
        while not self.quit.is_set():
            missing = [d for d in self.devices if not d.connected]
            if not missing:
                await asyncio.sleep(0.5)
                continue
            found = {}
            forced = {"coyote": self.args.coyote_address, "opossum": self.args.opossum_address}
            for d in missing:
                if forced[d.kind]:
                    found[d.kind] = forced[d.kind]
            if any(not forced[d.kind] for d in missing):
                self.scanning = True
                self.log(f"Scanning for {', '.join(d.label for d in missing)} ...")
                try:
                    results = await BleakScanner.discover(timeout=self.args.scan_timeout, return_adv=True)
                    for dev, adv in results.values():
                        k = device_kind(dev, adv)
                        if k and k not in found and any(m.kind == k for m in missing):
                            found[k] = dev
                except Exception as e:  # noqa: BLE001
                    self.log(f"scan failed: {e}")
                self.scanning = False
            for d in missing:
                if d.kind not in found:
                    self.log(f"{d.label} not found (is the DG-Lab app closed? Opossum: press power 5x for "
                             f"yellow BT icon). Retrying.")
                    continue
                try:
                    await d.connect(found[d.kind])
                    self.armed = False
                    self.log(f">> {d.label} ready. Press SPACE to arm.")
                except Exception as e:  # noqa: BLE001
                    self.log(f"{d.label} connect failed: {e}")
            await asyncio.sleep(2.0)

    async def watchdog_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.5)
            if self.armed and time.monotonic() - self.detector.last_audio > 2.0:
                self.armed = False
                self.log("!! STOP: no audio for 2 s (mic lost?)")

    # ---- UI ---------------------------------------------------------------------------

    async def status_task(self):
        while not self.quit.is_set():
            await asyncio.sleep(0.1)
            d, tr = self.detector, self.tracker
            lvl = max(0, min(16, int((d.level_db + 60) / 3.75)))
            beat = "●" if time.monotonic() - self.flash < 0.12 else " "
            devs = " ".join(
                f"{x.label[0]}:{max(0, x.strength):3d}({x.actual_a:3d})/{x.cap:3d}" if x.connected
                else f"{x.label[0]}:---" for x in self.devices)
            state = ("ARMED" if self.armed else "STOPPED") if self.connected_devices else "NO BLE"
            bar = f"bar:{tr.downbeat_phase + 1}" if tr.bar_known else "bar:?"
            fire = "every" if self.every_beat else "down "
            line = (f"\r{beat} [{state:7}] bpm={tr.bpm:5.1f}{'*' if tr.locked else ' '} {bar} {fire} "
                    f"{devs} {'auto' if self.auto else 'man '}{self.offset:+d} int={self.intensity:3d} "
                    f"lat={int(self.latency*1000):3d}ms n={self.bursts:4d} late={self.late_ticks:3d} "
                    f"|{'#'*lvl:<16}|")
            sys.stdout.write(line)
            sys.stdout.flush()

    def on_key(self):
        ch = os.read(sys.stdin.fileno(), 1).decode(errors="ignore")
        if ch == " ":
            self.armed = not self.armed
            self.log(">> ARMED" if self.armed else ">> STOPPED (strength 0)")
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
        elif ch == "e":
            self.every_beat = not self.every_beat
        elif ch == "[":
            self.latency = max(0.0, self.latency - 0.01)
        elif ch == "]":
            self.latency = min(0.5, self.latency + 0.01)
        elif ch == "i":
            self.intensity = min(100, self.intensity + 10)
        elif ch == "k":
            self.intensity = max(0, self.intensity - 10)
        elif ch == "v":
            self.verbose = not self.verbose
        elif ch == "t":
            self.test_pulse()

    def test_pulse(self):
        """Pipeline check without music: min strength on every connected device, one burst."""
        if not self.connected_devices:
            self.log("test pulse: nothing connected")
            return
        if self.tempo_x() is None:
            self.armed = True
            self.auto = False
            self.tracker.hits = max(self.tracker.hits, 4)
            if self.tracker.period is None:
                self.tracker.period, self.tracker.beat = 0.5, time.monotonic()
        self.test_burst_until = time.monotonic() + FRAME_S + self.args.burst_ms / 1000.0
        self.log("test pulse queued on: " + ", ".join(d.label for d in self.connected_devices))

    # ---- run --------------------------------------------------------------------------

    async def run(self):
        fd = sys.stdin.fileno()
        old_tty = termios.tcgetattr(fd)
        self.loop.add_signal_handler(signal.SIGINT, self.quit.set)
        self.detector.start()
        tty.setcbreak(fd)
        self.loop.add_reader(fd, self.on_key)
        tasks = [asyncio.ensure_future(t) for t in (
            self.link_task(), self.beat_task(), self.sender_task(),
            self.watchdog_task(), self.status_task())]
        try:
            await self.quit.wait()
        finally:
            self.loop.remove_reader(fd)
            termios.tcsetattr(fd, termios.TCSADRAIN, old_tty)
            print("\nShutting down: zero frames + disconnect")
            for t in tasks:
                t.cancel()
            await asyncio.gather(*(d.disconnect() for d in self.devices), return_exceptions=True)
            self.detector.stop()


async def scan_cmd(timeout):
    from bleak import BleakScanner
    print(f"Scanning {timeout}s ...")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    rows = sorted(found.values(), key=lambda da: -da[1].rssi)
    for d, adv in rows:
        name = d.name or getattr(adv, "local_name", None) or "?"
        svcs = ",".join(u[4:8] for u in (getattr(adv, "service_uuids", None) or [])) or "-"
        k = device_kind(d, adv)
        mark = f"  <-- {k.capitalize()}" if k else ""
        print(f"{d.address}  rssi={adv.rssi:4d}  svc={svcs:<12} {name}{mark}")
    print("\nCoyote missing?  Close the DG-Lab app (the box stops advertising while the app is connected).\n"
          "Opossum missing? Press its power button 5x until the Bluetooth icon turns yellow.\n"
          "Force a device with --coyote-address / --opossum-address <UUID>.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scan", action="store_true", help="list BLE devices and exit")
    p.add_argument("--scan-timeout", type=float, default=6.0)
    p.add_argument("--targets", default="coyote,opossum",
                   help="which devices to drive: coyote, opossum, or coyote,opossum (default: both)")
    p.add_argument("--coyote-address", "--address", dest="coyote_address", help="Coyote UUID from --scan")
    p.add_argument("--opossum-address", help="Opossum UUID from --scan")
    p.add_argument("--mic", "--device", dest="device", type=int,
                   help="microphone index from --list-devices (NOT the Bluetooth device)")
    p.add_argument("--list-devices", action="store_true")
    p.add_argument("--strength", type=int, default=5, help="Coyote strength at --bpm-lo and below (start LOW)")
    p.add_argument("--max", type=int, default=30, help="Coyote strength at --bpm-hi; also the BF soft cap")
    p.add_argument("--vib-min", type=int, default=40, help="Opossum intensity (0-200) at --bpm-lo")
    p.add_argument("--vib-max", type=int, default=120, help="Opossum intensity (0-200) at --bpm-hi; cap")
    p.add_argument("--bpm-lo", type=float, default=90.0)
    p.add_argument("--bpm-hi", type=float, default=150.0)
    p.add_argument("--intensity", type=int, default=100, help="slot intensity in ON slots 0-100 (both devices)")
    p.add_argument("--freq", type=int, default=30, help="Coyote waveform frequency byte 10-240")
    p.add_argument("--burst-ms", type=int, default=100, help="burst length; 25 ms slot resolution")
    p.add_argument("--latency", type=int, default=100,
                   help="ms to fire early (BLE + device delay). Tune with [ ] until it feels on-beat")
    p.add_argument("--both", action="store_true", help="drive channel B as well as A")
    p.add_argument("--every-beat", action="store_true", help="fire on every beat, not just the downbeat")
    p.add_argument("--freq-balance", type=int, default=160, help="Coyote BF freq balance 0-255")
    p.add_argument("--intensity-balance", type=int, default=0, help="Coyote BF intensity balance 0-255")
    p.add_argument("--sens", type=float, default=2.0, help="onset threshold in std-devs (lower = more sensitive)")
    p.add_argument("--verbose", action="store_true")
    args = p.parse_args()
    args.targets = [t.strip() for t in args.targets.split(",") if t.strip()]

    if args.list_devices:
        import sounddevice as sd
        print(sd.query_devices())
        return
    if args.scan:
        asyncio.run(scan_cmd(args.scan_timeout))
        return
    if not set(args.targets) <= {"coyote", "opossum"} or not args.targets:
        sys.exit("--targets must be coyote, opossum, or coyote,opossum")
    if not 0 <= args.strength <= args.max <= 200:
        sys.exit("need 0 <= --strength <= --max <= 200")
    if not 0 <= args.vib_min <= args.vib_max <= 200:
        sys.exit("need 0 <= --vib-min <= --vib-max <= 200")
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
