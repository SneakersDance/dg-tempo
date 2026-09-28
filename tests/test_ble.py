"""BLE prototype checks without hardware: frame bytes vs the documented examples, lookahead slot
scheduling against a locked tracker in fake time, and an end-to-end run against a fake bleak
with both a Coyote and an Opossum."""
import asyncio, os, sys, time, types
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import beatsync_ble as bb
from beatcore import TempoTracker

# ---- 1. protocol bytes ------------------------------------------------------------------
ex = bb.build_b0(0, 0, 0, 0, [10]*4, [0, 10, 20, 30], [0]*4, [0, 0, 0, 101])
assert ex == bytes.fromhex("B00000000A0A0A0A000A141E0000000000000065"), ex.hex()
f = bb.build_b0(3, 0b1111, 25, 0, [30]*4, [100, 100, 0, 0], [30]*4, [0]*4)
assert f[:4] == bytes([0xB0, 0x3F, 25, 0]) and len(f) == 20
assert bb.build_bf(30, 0) == bytes([0xBF, 30, 0, 160, 160, 0, 0])
assert bb.parse_b1(bytes([0xB1, 3, 25, 0])) == (3, 25, 0)
assert bb.parse_b1(b"\xb0\x00") is None
# Opossum, from the protocol README examples
assert bb.build_opossum_b3(160, 0xFF) == bytes.fromhex("B3A0FF")
assert bb.build_opossum_b3(0xFF, 200) == bytes.fromhex("B3FFC8")
assert bb.build_opossum_b3(10, 20) == bytes.fromhex("B30A14")
ob0 = bb.build_opossum_b0([100, 100, 0, 0], [0]*4)
assert len(ob0) == 20 and ob0[:8] == bytes([0xB0] + [0]*7) and ob0[8:12] == bytes([100, 100, 0, 0]) \
    and ob0[12:16] == bytes(4) and ob0[16:] == bytes(4)
b2 = bb.build_opossum_b2(40, 0)
assert len(b2) == 20 and b2[0] == 0xB2 and b2[1:4] == bytes([0xFF, 0xFF, 0]) and b2[16:] == bytes([8, 9, 40, 0])
assert bb.parse_opossum_b3(bytes([0xB3, 40, 0])) == (40, 0)
print("bytes OK")

# device kind detection
NS = types.SimpleNamespace
assert bb.device_kind(NS(name="47L121000"), NS(local_name=None, service_uuids=[])) == "coyote"
assert bb.device_kind(NS(name="47L127000"), NS(local_name=None, service_uuids=[])) == "opossum"
assert bb.device_kind(NS(name=None), NS(local_name="47L127000", service_uuids=[])) == "opossum"
assert bb.device_kind(NS(name=None), NS(local_name=None, service_uuids=[bb.SERVICE_UUID])) == "coyote"
assert bb.device_kind(NS(name="STANMORE"), NS(local_name=None, service_uuids=[])) is None
print("device kind OK")

# ---- 2. scheduler: bursts land --latency before each predicted downbeat --------------------
def make_args(**kw):
    a = dict(strength=5, max=30, vib_min=40, vib_max=120, bpm_lo=90.0, bpm_hi=150.0, intensity=100,
             freq=30, burst_ms=100, latency=100, both=False, every_beat=False, sens=2.0, device=None,
             verbose=False, freq_balance=160, intensity_balance=0, coyote_address=None,
             opossum_address=None, scan_timeout=0.01, targets=["coyote", "opossum"])
    a.update(kw)
    return types.SimpleNamespace(**a)

def make_app(**kw):
    args = make_args(**kw)
    app = bb.App.__new__(bb.App)
    app.args, app.latency, app.auto, app.offset = args, args.latency / 1000.0, True, 0
    app.every_beat, app.intensity, app.armed, app.test_burst_until = args.every_beat, 100, True, 0.0
    app.devices = []
    tr = TempoTracker(); tr.period = 60 / 128; tr.beat = 100.0; tr.anchor_index = 0; tr.hits = 20
    tr.bar_evidence = 20; tr.phase = 0
    app.tracker = tr
    return app, tr

for every in (False, True):
    app, tr = make_app(every_beat=every)
    on_starts, prev_on = [], False
    tick = 100.0 - 0.37
    while tick < 112.0:
        for j, v in enumerate(app.frame_for(tick)):
            if v > 0 and not prev_on:
                on_starts.append(tick + j * bb.SLOT_S)
            prev_on = v > 0
        tick += bb.FRAME_S
    step = tr.period * (1 if every else 4)
    expected = [100.0 + k * step - 0.1 for k in range(0, 40) if 100.0 - 0.37 <= 100.0 + k * step - 0.1 < 112.0]
    errs = [min(abs(s - e) for e in expected) * 1000 for s in on_starts]
    print(f"every={every}: {len(on_starts)} bursts, expected {len(expected)}, max |slot err|={max(errs):.1f}ms")
    assert len(on_starts) == len(expected) and max(errs) <= 12.6

app, tr = make_app(burst_ms=200)
pattern = sum((app.frame_for(99.85 + k * 0.1) for k in range(5)), [])
s = "".join("1" if v else "0" for v in pattern)
assert "11111111" in s, s
print("scheduler OK:", s)

# per-device strength maps from one tempo position
app, tr = make_app()
coy = bb.CoyoteDevice(app.args, lambda d: None, lambda m: None)
opo = bb.OpossumDevice(app.args, lambda d: None, lambda m: None)
app.devices = [coy, opo]
assert app.target_for(coy) == 21 and app.target_for(opo) == 91
app.armed = False; assert app.target_for(coy) == 0 and app.target_for(opo) == 0
app.armed = True; tr.hits = 0; assert app.target_for(coy) == 0
print("strength OK")

# ---- 3. end-to-end with a fake bleak: Coyote + Opossum ----------------------------------------
writes = {"coyote": [], "opossum": []}
notify_cb = {}
class FakeChar:
    properties = ["write", "write-without-response"]
class FakeServices:
    def get_characteristic(self, uuid): return FakeChar()
class FakeClient:
    def __init__(self, target, disconnected_callback=None, timeout=None):
        self.kind = target; self.is_connected = False; self.services = FakeServices()
    async def connect(self): self.is_connected = True
    async def disconnect(self): self.is_connected = False
    async def start_notify(self, uuid, cb): notify_cb[self.kind] = cb
    async def read_gatt_char(self, uuid): return b"\x55"
    async def write_gatt_char(self, uuid, data, response=True):
        writes[self.kind].append((time.monotonic(), bytes(data), response))
        if self.kind == "coyote" and data[0] == 0xB0 and (data[1] >> 4):
            notify_cb[self.kind](None, bytes([0xB1, data[1] >> 4, data[2], data[3]]))
        if self.kind == "opossum" and data[0] == 0xB3:
            notify_cb[self.kind](None, bytes([0xB3, data[1], 0]))
class FakeScanner:
    @staticmethod
    async def discover(timeout=None, return_adv=True):
        return {"a": (NS(name="47L121000", address="coyote"), NS(local_name=None, service_uuids=[], rssi=-60)),
                "b": (NS(name="47L127000", address="opossum"), NS(local_name=None, service_uuids=[], rssi=-70)),
                "c": (NS(name="STANMORE", address="x"), NS(local_name=None, service_uuids=[], rssi=-80))}
fake = types.ModuleType("bleak"); fake.BleakClient = FakeClient; fake.BleakScanner = FakeScanner
sys.modules["bleak"] = fake
# our fake client is keyed by the BLEDevice's address
_orig_connect = bb.BleDevice.connect
async def _connect(self, target): return await _orig_connect(self, target.address)
bb.BleDevice.connect = _connect

async def e2e():
    app, tr = make_app()
    app.log = lambda m: None
    app.devices = [bb.CoyoteDevice(app.args, app._on_disconnect, app.log),
                   bb.OpossumDevice(app.args, app._on_disconnect, app.log)]
    app.quit = asyncio.Event(); app.flash = 0.0; app.late_ticks = 0; app.bursts = 0
    app.verbose = False; app.scanning = False; app.was_locked = app.was_bar_known = False
    app.detector = NS(last_audio=time.monotonic(), level_db=-30)
    tasks = [asyncio.ensure_future(app.link_task()), asyncio.ensure_future(app.sender_task())]
    await asyncio.sleep(0.4)
    assert all(d.connected for d in app.devices) and not app.armed, "both connect, app disarmed"
    app.armed = True                                   # user presses SPACE
    tr.beat = time.monotonic() + 0.25
    await asyncio.sleep(1.2)
    app.quit.set()
    for t in tasks: t.cancel()
    await asyncio.gather(*(d.disconnect() for d in app.devices))
    return app

app = asyncio.new_event_loop().run_until_complete(e2e())

cw = writes["coyote"]
assert cw[0][1][0] == 0xBF and cw[0][1][1:3] == bytes([30, 0]), "Coyote BF soft cap must be first write"
b0s = [(t, d) for t, d, r in cw if d[0] == 0xB0][:-1]
gaps = [b0s[i+1][0] - b0s[i][0] for i in range(len(b0s) - 1)]
print(f"coyote: {len(b0s)} B0 frames, cadence {min(gaps)*1000:.0f}-{max(gaps)*1000:.0f} ms")
assert 0.085 <= min(gaps) and max(gaps) <= 0.13, gaps
sets = [(d[1] >> 4, d[1] & 0xF, d[2]) for _, d in b0s if d[1] >> 4]
print("coyote strength-set frames (seq, mode, A):", sets)
assert sets[0][2] == 0 and sets[0][1] == 0b1111 and any(a == 21 for _, _, a in sets)
assert app.devices[0].pending_seq is None
assert any(any(d[8:12]) and d[4:8] == bytes([30]*4) for _, d in b0s), "coyote burst frames"
assert cw[-1][1][0] == 0xB0 and cw[-1][1][2] == 0 and not any(cw[-1][1][8:12]), "final coyote frame silent"

ow = writes["opossum"]
kinds = [d[0] for _, d, _ in ow]
assert 0xBF not in kinds, "Opossum has no BF"
b3s = [d for _, d, _ in ow if d[0] == 0xB3]
print("opossum B3 intensity writes:", [(d[1], d[2]) for d in b3s])
assert b3s[0] == bytes([0xB3, 0, 0xFF]) and any(d[1] == 91 for d in b3s), "opossum intensity via B3, B kept"
assert any(d[0] == 0xB2 for _, d, _ in ow), "display update after B3"
ob0s = [d for _, d, _ in ow if d[0] == 0xB0]
assert all(d[1:8] == bytes(7) and d[12:16] == bytes(4) for d in ob0s), "opossum B0 has zeros where Coyote has freq"
assert any(any(d[8:12]) for d in ob0s), "opossum burst frames"
assert app.devices[1].actual_a == 0 and ow[-1][1][0] == 0xB0 and not any(ow[-1][1][8:12]), "final opossum silent"
# both devices get the same slot pattern on the same tick
c_on = [d[8:12] for _, d in b0s if any(d[8:12])]
o_on = [d[8:12] for d in ob0s if any(d[8:12])]
assert c_on == o_on, "both devices must receive identical slot patterns"
print(f"E2E OK  (coyote {len(b0s)} frames, opossum {len(ob0s)} frames, {len(c_on)} burst frames each)")
print("BLE ALL OK")
