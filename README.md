# dg-tempo — Coyote 3.0 beat-sync prototype (SOCKET route)

v1 uses DG-Lab's **SOCKET v2** control: the official app stays connected to the Coyote over
BLE, this script runs a WebSocket server on your laptop, and the app relays our commands.
No BLE binding questions, no licensing surprises from talking to the device directly.

## Setup (macOS)

```bash
python3.13 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Laptop and phone must be on the same Wi-Fi/LAN.

## Run

```bash
.venv/bin/python beatsync_socket.py --list-devices          # find your mic index
.venv/bin/python beatsync_socket.py --device 5 --strength 5 --max 30
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
