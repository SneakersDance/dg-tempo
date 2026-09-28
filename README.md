# dg-tempo — Coyote 3.0 beat-sync prototype

Two controllers share one audio engine ([beatcore.py](beatcore.py): kick onset detection,
tempo + downbeat tracking):

- **`beatsync_ble.py` — direct BLE (recommended).** The laptop is the BLE central and drives up
  to two DG-Lab devices from one beat clock:
  - **Coyote 3.0** e-stim box (`47L121000`): B0 waveform frames every 100 ms, BF soft cap.
  - **Opossum 负鼠** vibration controller (`47L127000`): same frame shape, intensity via B3.
    Each downbeat burst is placed into exact 25 ms slots ahead of time, so the only latency left
    is BLE + device, cancelled by a single `--latency` knob.
- **`beatsync_socket.py` — SOCKET v2 via the official app.** Kept for reference; the app relay
  adds too much jitter for tight beat sync, and it cannot drive the Opossum.

## Direct BLE quick start

```bash
.venv/bin/pip install -r requirements.txt
.venv/bin/python beatsync_ble.py --list-devices              # microphone index -> --mic
.venv/bin/python beatsync_ble.py --scan                      # look for '<-- Coyote' / '<-- Opossum'
.venv/bin/python beatsync_ble.py --mic 1 --strength 5 --max 50 --vib-min 40 --vib-max 120 --every-beat
```

- By default it connects to **both** devices, each independently: a missing one is retried in
  the background and never blocks the other. `--targets coyote` or `--targets opossum` limits it.
- Close the official DG-Lab app first: a device stops advertising while the app holds it.
  Opossum not listed? Press its power button 5x until the Bluetooth icon turns yellow.
- `--mic N` is the microphone index from `--list-devices`. Bluetooth devices are picked by
  name automatically; force one with `--coyote-address` / `--opossum-address <UUID>`.
- Strength follows tempo on both devices from the same 0..1 tempo position: Coyote
  `--strength`→`--max` (also written as its BF soft cap), Opossum `--vib-min`→`--vib-max`
  (0–200). `+`/`-` offsets the Coyote only.
- After connect the app is disarmed. Press `t` for a test pulse on every connected device,
  then `SPACE` to arm.
- Status line: `bpm=128.0*` = locked, `bar:N` = downbeat found, `C: 21( 21)/ 24` = Coyote
  commanded (device-reported)/cap, `O: 91( 91)/120` same for the Opossum, `lat=` latency knob,
  `late=` frames the 100 ms loop sent late (should stay near 0).
- Tune `[` `]` (latency, 10 ms steps) until pulses land on the beat. Start with `--every-beat`,
  then press `e` to switch to downbeats only once the bar is found.
- Keys are the same as the socket version, plus `e` (every-beat toggle) and `v` (verbose).

Direct BLE notes: the protocol repo forbids commercial use without DG-Lab authorization, and
the devices' behaviour when B0 frames stop is undocumented, so this script never stops sending
frames while connected and writes a strength-0 silent frame before disconnecting.

## SOCKET route (via the app)

## Setup (macOS)

```bash
python3.13 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Laptop and phone must be on the same Wi-Fi/LAN.

## Run

```bash
.venv/bin/python beatsync_socket.py --list-devices          # find your mic index
.venv/bin/python beatsync_socket.py --mic 5 --strength 5 --max 30
.venv/bin/python beatsync_socket.py --device 1 --strength 10 --max 50
.venv/bin/python beatsync_socket.py --device 1 --strength 5 --max 50 --every-beat
```

1. A QR code prints in the terminal (and `qr.png`). In the DG-Lab app: **SOCKET control → scan**.
2. In the app, set the channel strength **limit** low for the first tests. The script also
   never exceeds `--max`.
3. Play music near the mic. Status line shows BPM (`*` = locked), `bar:N` once the downbeat
   is known, and `●` flashes on each burst.

## Behaviour

- **One burst per downbeat.** The tracker locks tempo from kick onsets, then guesses which of
  the 4 beats is the "1" by where the heaviest low-band hits land. Until the bar is known it
  fires on every beat. Press `d` to rotate the downbeat by one beat if it picked the wrong one.
- **BPM decides strength.** Linear map: `--bpm-lo` (90) → `--strength`, `--bpm-hi` (150) → `--max`.
  Clamped at both ends, `--max` is a hard cap, and the app's own limit caps it too.
- **No beat, no output.** Fan noise, static, talking: the tracker never locks, strength stays 0
  and nothing fires. Strength drops back to 0 within 0.5 s of losing the lock.

## Keys

| key       | action                                                                                         |
| --------- | ---------------------------------------------------------------------------------------------- |
| `SPACE`   | STOP (strength 0, clear queue) / re-arm                                                        |
| `q`       | quit (stops output first)                                                                      |
| `+` / `-` | manual offset ±1 on top of the BPM strength (still capped)                                     |
| `a`       | toggle auto (BPM) strength; manual uses `--strength` + offset                                  |
| `d`       | rotate downbeat by one beat                                                                    |
| `m`       | toggle `reactive` ↔ `predict`                                                                  |
| `[` / `]` | lead time −/+ 10 ms (predict mode)                                                             |
| `i` / `k` | pulse intensity ±10                                                                            |
| `t`       | test pulse: one burst, at `--strength` if strength is 0 (checks the device path without music) |
| `s`       | re-print QR                                                                                    |

## Modes

- **reactive** — burst when a detected kick lands on a downbeat. Arrives ~150–300 ms late
  (mic buffer + WebSocket + app + BLE + 100 ms pulse frame).
- **predict** (default) — fires each burst `--lead` ms _before_ the predicted downbeat.
  Tune `[` `]` until it feels on-beat.

## Flags

`--strength` strength at `--bpm-lo` (start low), `--max` strength at `--bpm-hi` and hard cap,
`--bpm-lo` / `--bpm-hi` the tempo range of the map, `--intensity` waveform intensity
0–100, `--freq` waveform freq byte 10–240, `--burst-ms` burst length (100 ms steps),
`--both` drive channel B too, `--sens` onset threshold in std-devs (lower = more sensitive),
`--every-beat` fire on every beat instead of only the downbeat, `--verbose` log every onset and
burst, `--host-ip` override the LAN IP in the QR, `--port` (default 5678).

## Troubleshooting "no output"

Read the status line left to right:

1. **Level bar empty** → the mic is not delivering audio. Check macOS mic permission for your
   terminal, or pick another input with `--device`.
2. **`bpm=0.0`** → too few kick onsets. Move the mic closer or lower `--sens` (try 1.5).
3. **`bpm=128.0` but no `*`** → onsets found, but they do not sit on a steady grid. The script
   prints `LOCKED` when it does. Try `--every-beat` first, then `--verbose` to watch onsets.
4. **`*` but `bar:?`** → locked, firing on every beat until it has seen 8 beats on the grid.
5. **`str=0`** → strength follows BPM and is 0 until locked. Press `t` to confirm the device
   responds at all: it sets `--strength` and sends one burst.
6. **Bursts counting up (`n=`) but nothing felt** → raise `--strength`/`--max`, or the limit
   inside the app is lower than you think (the status line shows the effective cap).

## Fail-safes

- Strength comes from tempo only, never from loudness, and never exceeds `--max`.
- No mic audio for 2 s → strength 0 + queue cleared.
- App disconnects → disarmed; re-scan QR, then `SPACE` to re-arm.
- Quit / Ctrl-C → strength 0 + queue cleared before exit.

## Testing without hardware

```bash
.venv/bin/python tests/test_detect.py   # synthetic 128 BPM 4/4 -> tempo, downbeat, strength map
.venv/bin/python tests/test_socket.py   # fake DG-Lab app over WebSocket -> bind, strength, pulse, clear
```
