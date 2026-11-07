<p align="center"><img src="art/icon-256.png" width="128" alt="skazhi"></p>

# skazhi

Voice commands for a Google-less (China-firmware) Xiaomi Android TV, using the
**remote's own microphone** and offline Russian speech recognition on a
Raspberry Pi. Hold the remote's mic button, say a command ("запусти ютуб"),
release — the named app opens.

No Google services, no cloud, no API keys. Audio never leaves the home network.

## How it works

```
remote mic ──BLE──▶ TV ──PCM/HTTP──▶ Raspberry Pi ──▶ app launches
 (hold button)    (records)       (Vosk STT, grammar)
```

- **app/** — a tiny Android accessibility service on the TV. It catches the
  remote's voice button (a key the firmware delivers but doesn't fully own),
  records the remote mic via `AUDIO_SOURCE_VOICE_RECOGNITION` while the button
  is held, POSTs the PCM to the Pi, matches the recognized text against
  `commands.json`, and launches the mapped package.
- **server/** — a small HTTP server on the Pi wrapping [Vosk](https://alphacephei.com/vosk/).
  `POST /recognize` with raw 16 kHz mono PCM16 → `{"text": "..."}`. An optional
  base64 grammar header restricts recognition to the command phrases, which makes
  short commands near-perfect even on a noisy mic.

## Why these choices (the non-obvious bits)

- The remote mic reaches apps as `AUDIO_SOURCE_VOICE_RECOGNITION`, **not** the
  built-in far-field mic (`MIC`, which is nearly silent here). The XiaoAI socket
  path is SELinux-locked and unused.
- The remote streams over BLE in **bursts**; raw `AudioRecord` concatenation
  compresses speech in time and ruins recognition. The recorder pads gaps with
  silence by wall clock to keep the timeline real.
- Recognition runs **on the Pi**, never on the TV — on-device Vosk crashed this
  TV's video pipeline. The TV only records and uploads.
- Grammar travels in a **base64** header; raw Cyrillic in an HTTP header is
  mangled to mojibake.
- The key is **not consumed** (the service returns `false`), so the firmware
  keeps streaming the mic; the disabled XiaoAI assistant fails harmlessly.

## Setup

### Server (Raspberry Pi / any Linux)

```bash
cd server
sudo ./install.sh                     # downloads the small Russian model
# or: sudo ./install.sh /path/to/vosk-model-dir
```
Installs to `/opt/skazhi`, runs as a `skazhi-asr` systemd service on port 2700,
and opens that port to the LAN if `nft` is present. Health: `curl PI:2700/health`.

### App (TV, over ADB)

Needs Android cmdline-tools (build-tools 34 + platform 34) and a JDK.

```bash
cd app
# edit commands.json: set "server" to http://<pi-ip>:2700/recognize, add commands
./build.sh                            # -> skazhi.apk  (no Gradle)
./deploy.sh 192.168.88.57:5555        # install + enable accessibility + grant mic
```

## Commands

`app/assets/commands.json` maps spoken phrases to packages:

```json
{
  "server": "http://192.168.88.241:2700/recognize",
  "trigger_scan_code": 63,
  "commands": [
    { "phrases": ["запусти ютуб", "ютуб"], "package": "com.google.android.youtube.tv" },
    { "phrases": ["запусти лампу", "лампа"], "package": "top.rootu.lampa" }
  ]
}
```

Add a command: add an entry, rebuild, redeploy. `trigger_scan_code` is the
remote's voice-button scancode (63 on this remote; find yours with
`getevent`/logcat if different).

## Hardware this was built for

Xiaomi TV (MediaTek, Android 14, China HyperOS, no GMS) + its BT voice remote,
and a Raspberry Pi 4 on the same LAN. Other devices will differ in scancodes,
package names and mic routing.
