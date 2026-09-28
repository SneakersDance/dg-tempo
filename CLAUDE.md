# Coyote Beat-Sync Prototype — Handoff Brief

## Goal
Prototype a phone-based (later: standalone box) controller for the **DG-Lab Coyote 3.0** e-stim unit that listens to ambient music via microphone and fires pulses in sync with the beat (target use case: wearer on a dance floor, pulses on the downbeat). Client is a DG-Lab reseller. First milestone: working prototype in ~1 hour — pulses on kick drum / beats, not true downbeat detection.

## Coyote V3 BLE protocol (from official docs)
Source: https://github.com/dungeonlab-open/dglab-bluetooth-protocol/blob/main/coyote/v3/README.md

- Device BLE name: `47L121000`
- Base UUID: `0000xxxx-0000-1000-8000-00805f9b34fb`
- Service `0x180C`:
  - `0x150A` WRITE — all commands (max 20 bytes)
  - `0x150B` NOTIFY — all responses (subscribe after connect)
- Service `0x180A` / char `0x1500` — battery (read/notify, 1 byte)
- No endianness conversion needed in V3 (unlike V2).
- Documented protocol has **no pairing/auth step** — plain GATT writes. BLE allows one central at a time, so the official app must be disconnected.

### B0 command — 20 bytes, write every 100ms
```
0xB0
+ seq (4 bits) + strength interpretation mode (4 bits)
+ A strength value (1B) + B strength value (1B)
+ A waveform freq x4 (4B) + A waveform intensity x4 (4B)
+ B waveform freq x4 (4B) + B waveform intensity x4 (4B)
```
- Each freq/intensity pair = 25ms of output; 4 pairs = 100ms frame.
- Strength mode, 2 bits per channel (high 2 = A, low 2 = B): `00` no change, `01` relative increase, `10` relative decrease, `11` absolute set.
- Channel strength range 0–200 (out-of-range treated as 0).
- Waveform freq byte range 10–240; waveform intensity 0–100. Any invalid value in a channel's waveform data → device discards all 4 slots for that channel. Trick to silence one channel: put an intensity of 101 in it.
- Freq conversion from input 10–1000: 10–100 → as-is; 101–600 → (x−100)/5+100; 601–1000 → (x−600)/10+200.
- seq > 0 → device replies with B1 carrying same seq. When changing strength with seq ≠ 0, wait for the matching B1 before changing strength again.
- Example (A channel only, no strength change): `B00000000A0A0A0A000A141E0000000000000065`

### BF command — 7 bytes, soft limits
```
0xBF + A,B strength soft cap (2B, 0–200) + A,B freq balance (2B, 0–255) + A,B intensity balance (2B, 0–255)
```
- Persists across power-off, no response. **Must rewrite on every (re)connect.**

### B1 notification
`0xB1 + seq (1B) + A actual strength (1B) + B actual strength (1B)` — sent immediately whenever strength changes (seq 0 if changed by wheel).

## Control model
- **Channel strength (0–200)** = volume knob. Set once, cap with BF, don't drive it from loudness.
- **Waveform intensity per 25ms slot** = *when* to pulse. 0 between beats, burst in chosen slots. Beat sync = choosing which slots in upcoming frames get the burst.

## Alternative path: SOCKET v2 via official app
Official app stays connected to device; you run a WebSocket server, user scans QR in app, app relays commands. Python lib: https://github.com/Ljzd-PRO/PyDGLab-WS. Avoids any binding issue and is arguably the sanctioned route, but adds network + app latency/jitter (bad for beat sync). App-side strength limits must be set manually by user in app. **Prefer direct BLE for this project.**

## Latency — the real problem
Reactive chain (mic buffer + analysis + BLE connection interval + 100ms device frame) ≈ 100–200ms late → feels off-beat. Solution: tempo tracking + prediction, schedule pulses into future 25ms slots, user-calibrated offset slider. True downbeat (beat 1 of bar) detection is hard — v1 targets every beat / low-band onsets.

## Prototype paths
1. **Laptop Python (fastest proof):** `bleak` (BLE) + `sounddevice` + `aubio` (onset/tempo).
2. **Phone web:** single HTML file, Web Bluetooth + Web Audio API. Works on **Chrome Android only** — iOS Safari has no Web Bluetooth. iOS later via Capacitor + BLE plugin.
3. **Standalone box (later):** ESP32 as BLE central + I2S MEMS mic.

## Prototype requirements
- Connect, subscribe to 0x150B, **write BF soft cap immediately on connect**.
- Jitter-free 100ms B0 send loop with a lookahead pulse scheduler.
- Onset detection on low band (kick), tempo estimate, predictive scheduling.
- UI: connect button, strength cap, base strength, pulse intensity/freq, latency offset slider, big STOP button.
- Stop on disconnect / audio loss; strength must never auto-escalate from loudness.

## Open items / before quoting client
- [ ] Verify "binding" claim: nRF Connect → connect → hand-write a B0 frame. If device refuses/ignores, binding is real.
- [ ] Confirm device behaviour when B0 frames stop (does output stop?).
- [ ] **Licensing:** repo states no commercial use without DG-Lab authorization. Client (reseller shipping a product) needs permission — contact via https://www.dungeon-lab.com.
- [ ] Check whether the official DG-Lab app already has a music-follow mode (changes what client is buying).
- [ ] Safety: e-stim + dance floor + alcohol + loud drops → hard caps, fail-safe on disconnect, no loudness-driven strength ramp.

## References
- Protocol repo: https://github.com/dungeonlab-open/dglab-bluetooth-protocol
- Waveform explainer: https://github.com/dungeonlab-open/dglab-bluetooth-protocol/blob/main/coyote/README.md
- Direct-BLE community example: https://github.com/amoeet/VRChat_X_DGLAB
- Socket v2 Python lib: https://github.com/Ljzd-PRO/PyDGLab-WS
