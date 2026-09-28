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

## Android app (native Java) — `android-app/`

**DG Tempo** turns whatever you are watching or listening to into pulses on your DG-Lab gear. Pair
it with a Coyote 3.0 e-stim box, an Opossum vibrator, or both, then pick where the music comes from:
share the phone's own audio and scroll through dance videos on TikTok, RedNote or your local
library, or switch to the microphone and let the room, a speaker system or a dance floor drive it.
The app listens for the kick, locks onto the tempo, works out which beat is the downbeat, and
fires on the beat with the official DG-Lab waveform library, at a strength that rises with the
BPM. The Opossum can simply buzz on every kick, while the Coyote can be held back until the beat
is solid, limited to a tempo range, fired only once per bar, put on a timer that lands on the
tempo's high point, or given a random level between your base and max so no two shocks feel the
same. With phone audio on, it can also watch the video itself and fire on the dancers' moves. A
hard cap is written into the Coyote, every device has its own on/off switch, output stops the
moment the music stops, and a small picture-in-picture window keeps the BPM, the next level and a
"shock incoming" bar in view while you keep scrolling.

**DG Tempo** 把你正在看、正在听的一切变成 DG-Lab 设备上的脉冲。连接郊狼 3.0 电击主机、负鼠震动器，或两者一起，
然后选择音乐来源：共享手机自身的声音，一边刷抖音、小红书或本地视频里的舞蹈，一边跟着节奏走；或者切换到麦克风，
让房间里的音乐、音箱或舞池来驱动。App 会捕捉鼓点、锁定节奏、判断哪一拍是强拍，并用 DG-Lab 官方波形库在节拍上输出，
强度随 BPM 升高而增强。负鼠可以简单地在每个鼓点上震动；郊狼则可以等到节拍足够稳定才输出、限定在某个 BPM 区间、
每小节只输出一次、按定时器在节奏最高点触发，或者在你设定的基础与最大强度之间随机取值，让每一次电击都不一样。
开启手机内部音频后，它还能"看"视频画面，随舞者的动作触发。硬上限会直接写入郊狼主机，每个设备都有独立的开关，
音乐一停输出立刻停止，小窗（画中画）则会在你继续刷视频时一直显示 BPM、下一次的强度和"即将电击"的进度条。


The phone version of the direct-BLE controller. No web layer: a foreground service owns the audio
thread, the tempo tracker and the 100 ms BLE frame loop, so it keeps pulsing while you watch
videos in other apps or the screen is off. UI in English, 中文 and 日本語 (button top-right).

```bash
cd android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (copy also kept at ../dg-tempo-debug.apk)
# wireless install: python3 -m http.server 3001 in a folder with the APK, open http://<laptop-ip>:3001 on the phone
```

**Step 1 — audio source.** *Phone audio* captures what the phone itself plays (videos, music apps)
via Android playback capture: full bass, no room noise, keeps working when you switch apps.
Android asks for screen/audio capture consent. DRM apps (Netflix etc.) deliver silence; YouTube,
browsers and local players work. *Microphone* is for a real speaker system or the dance floor.
The mic cannot hear the bass of the phone's own speaker, which is why pulses stopped when
switching to a video before this option existed.

**Step 2 — devices.** Close the DG-Lab app, SCAN, CONNECT each device. Each connected device
has a *Pulses ON/OFF* switch so you can mute one while keeping it connected. The app shows the
pairing recipes: Coyote → power off, on, turn wheels A and B in opposite directions until the
wolf eye blinks 5×; Opossum → press power 5× until the Bluetooth icon is yellow. Connects retry
3× automatically and the log decodes GATT status codes (133 = scan running / held elsewhere).

**Step 3 — strength and waveform.** Per device: a waveform picker with the **official DG-Lab
library** (24 Coyote waveforms, 20 Opossum waveforms, from `dungeonlab-open/dglab-kit`, GPL-3.0)
plus the simple flat pulse. Two play modes: *on each beat* (the waveform restarts at every beat /
downbeat and runs for "Pulse length", up to 3 s) or *continuous* (loops like the official app
while strength still follows tempo). Coyote max strength is a hard limit written into the box as
its BF soft cap. Opossum strength is fixed or tempo-mapped.

**Step 4 — timing (advanced).** Latency compensation, pulse length, every-beat vs downbeat,
shift downbeat, kick sensitivity, BPM range for the strength map. Settings persist.

The notification shows live BPM, lock, per-device strength and audio level, and has a STOP
action. Tap *Allow background* once so the phone does not kill the service off-screen.

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
# dg-tempo
