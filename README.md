# dg-tempo — Coyote 3.0 beat-sync

☕ Like the app? It's free — but a lot of tokens were burned testing it. Consider a crypto donation:

[![Cryptocurrency & Bitcoin donation button by NOWPayments](https://nowpayments.io/images/embeds/donation-button-white.svg)](https://nowpayments.io/donation?api_key=ca26d24c-1521-4563-9cea-a3a9d9098647)

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

No web layer: a foreground service owns the audio thread, the tempo tracker and the 100 ms BLE frame loop, so it keeps pulsing while you watch
videos in other apps or the screen is off. UI in English, 中文 and 日本語 (button top-right).

```bash
cd android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (copy also kept at ../dg-tempo-debug.apk)
# wireless install: python3 -m http.server 3001 in a folder with the APK, open http://<laptop-ip>:3001 on the phone
```

**Step 1 — audio source.** _Phone audio_ captures what the phone itself plays (videos, music apps)
via Android playback capture: full bass, no room noise, keeps working when you switch apps.
Android asks for screen/audio capture consent. DRM apps (Netflix etc.) deliver silence; YouTube,
browsers and local players work. _Microphone_ is for a real speaker system or the dance floor.
The mic cannot hear the bass of the phone's own speaker, which is why pulses stopped when
switching to a video before this option existed.

**Step 2 — devices.** Close the DG-Lab app, SCAN, CONNECT each device. Each connected device
has a _Pulses ON/OFF_ switch so you can mute one while keeping it connected. The app shows the
pairing recipes: Coyote → power off, on, turn wheels A and B in opposite directions until the
wolf eye blinks 5×; Opossum → press power 5× until the Bluetooth icon is yellow. Connects retry
3× automatically and the log decodes GATT status codes (133 = scan running / held elsewhere).

**Step 3 — strength and waveform.** Per device: a waveform picker with the **official DG-Lab
library** (24 Coyote waveforms, 20 Opossum waveforms, from `dungeonlab-open/dglab-kit`, GPL-3.0)
plus the simple flat pulse. Two play modes: _on each beat_ (the waveform restarts at every beat /
downbeat and runs for "Pulse length", up to 3 s) or _continuous_ (loops like the official app
while strength still follows tempo). Coyote max strength is a hard limit written into the box as
its BF soft cap. Opossum strength is fixed or tempo-mapped.

**Step 4 — timing (advanced).** Latency compensation, pulse length, every-beat vs downbeat,
shift downbeat, kick sensitivity, BPM range for the strength map. Settings persist.

The notification shows live BPM, lock, per-device strength and audio level, and has a STOP
action. Tap _Allow background_ once so the phone does not kill the service off-screen.

## Release pipeline (GitHub Actions → Vercel Blob → sneakersdance.com)

`.github/workflows/android-release.yml` runs on every push to `main` that touches `android-app/`
(or manually via *Run workflow*). It:

1. reads `versionName` / `versionCode` from `android-app/app/build.gradle`;
2. runs the unit tests and builds a **release** APK, signed with the keystore from the GitHub
   environment (falls back to the runner's throwaway debug key with a warning);
3. uploads it to Vercel Blob as `dg-tempo/dg-tempo-v<version>.apk` (fixed name, overwrite allowed);
4. calls `POST https://sneakersdance.com/api/apk/ci` with a shared secret so the site swaps its
   homepage download link and deletes the previous blob (only one APK is ever stored);
5. publishes a GitHub release `v<version>` with the APK attached (that is the version history).

**To ship a build:** bump `versionCode` and `versionName` in `android-app/app/build.gradle`,
commit, push to `main`. The same version pushed twice overwrites the blob and updates the release.

**GitHub environment `prod`** (Settings → Environments → prod):

| kind   | name                        | value |
|--------|-----------------------------|-------|
| secret | `BLOB_READ_WRITE_TOKEN`     | Vercel Blob read-write token |
| secret | `APK_PUBLISH_SECRET`        | long random string; the same value goes into the Vercel project env as `APK_PUBLISH_SECRET` |
| secret | `ANDROID_KEYSTORE_B64`      | `base64 -i <keystore> \| tr -d '\n'` |
| secret | `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| secret | `ANDROID_KEY_ALIAS`         | key alias |
| secret | `ANDROID_KEY_PASSWORD`      | key password |
| var    | `SITE_URL`                  | optional, default `https://sneakersdance.com` |

Android only installs an update over an existing app if both are signed with the **same key**.
Phones that already have a build from this laptop were signed with `~/.android/debug.keystore`
(password `android`, alias `androiddebugkey`). To keep those installs updatable, use that file as
the CI keystore: `base64 -i ~/.android/debug.keystore | tr -d '\n'`. Switching to a proper
release key later means one uninstall/reinstall on each phone.
