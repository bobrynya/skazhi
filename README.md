<p align="center"><img src="art/icon-256.png" width="128" alt="skazhi logo"></p>

<h1 align="center">skazhi</h1>

<p align="center">
  <b>Offline Russian voice control for an Android TV without Google</b><br>
  Hold the remote's mic button, say «запусти ютуб» — YouTube opens.
</p>

<p align="center">
  <img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue">
  <img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Vosk" src="https://img.shields.io/badge/STT-Vosk%20(offline)-6D3BFF">
  <img alt="Raspberry Pi" src="https://img.shields.io/badge/server-Raspberry%20Pi-C51A4A?logo=raspberrypi&logoColor=white">
</p>

China-firmware Xiaomi TVs ship without Google services, so the remote's voice
button does nothing useful outside China. **skazhi** («скажи», "say") brings it
back: it uses the **remote's own microphone**, recognizes speech **offline** on a
Raspberry Pi in your home network, and runs the command on the TV.

No Google, no cloud, no API keys, no root. Audio never leaves your LAN.

## What you can say

| Say | Does |
|---|---|
| «запусти / открой / включи **ютуб**» | opens YouTube (any app — configurable) |
| «включи **музыку**», «запусти **спотифай**» | opens Spotify |
| «**громче**», «**тише**», «**выключи звук**» | volume up / down / mute |
| «**пауза**», «**продолжи**», «**дальше**», «**предыдущий**» | media keys for the current player |
| «**домой**», «**назад**» | Home / Back |
| «**найди** реквием по мечте» | opens Lampa search with the title |
| «**выключи телевизор**» | puts the TV to sleep |

All phrases live in [`app/assets/commands.json`](app/assets/commands.json).

## How it works

```
 hold mic button        record while held            Vosk on the Pi
 ┌──────────┐  BLE   ┌───────────────────┐  HTTP   ┌──────────────────┐
 │  remote  │ ─────▶ │ TV: skazhi        │ ──────▶ │ skazhi-asr       │
 └──────────┘        │ (accessibility    │  PCM    │ grammar + free   │
                     │  service)         │ ◀────── │ transcripts      │
                     └───────────────────┘  JSON   └──────────────────┘
                       │ match commands.json
                       ▼
            launch app · volume · media key · home/back · Lampa search
```

- **[app/](app)** — a tiny Android accessibility service (no UI, ~300 lines of
  Java, built without Gradle). It catches the remote's voice button, records the
  remote mic while the button is held, sends the audio to the Pi and runs the
  matched action.
- **[server/](server)** — an ~80-line Python HTTP server wrapping
  [Vosk](https://alphacephei.com/vosk/). `POST /recognize` with raw 16 kHz mono
  PCM16 returns two transcripts: one restricted to the command phrases (near
  perfect on short commands) and one free-form (for «найди ‹title›»).

### The non-obvious bits

- The remote mic reaches apps as **`AUDIO_SOURCE_VOICE_RECOGNITION`**, and only
  while the button is held. The TV's own far-field mic (`MIC`) is nearly silent.
- The remote streams audio over BLE in **bursts**. Plain `AudioRecord` reads
  squash speech in time and wreck recognition, so the recorder pads the gaps
  with silence by wall clock.
- The key is **not consumed**: the firmware keeps streaming audio only while it
  thinks its own assistant is listening. The (unavailable) XiaoAI assistant then
  fails silently.
- Recognition runs **on the Pi**, not the TV: on-device Vosk broke this TV's
  video pipeline.
- The grammar travels in a **base64** header: raw Cyrillic in HTTP headers turns
  into mojibake.
- Lampa's `SEARCH` intent filter has no `CATEGORY_DEFAULT`, so the search intent
  is resolved and started explicitly with a `query` extra.

## Setup

You need: the TV with ADB over network enabled, a Raspberry Pi (or any Linux box)
on the same LAN, and on your computer Android cmdline-tools (build-tools 34,
platform 34) plus a JDK.

### 1. Server (Raspberry Pi)

```bash
cd server
sudo ./install.sh                         # downloads vosk-model-small-ru-0.22
# or: sudo ./install.sh /path/to/vosk-model-dir
```

Installs to `/opt/skazhi` and runs as the `skazhi-asr` systemd service on port
2700 (opened to `192.168.0.0/16` if nftables is used). Re-run it to update.

```bash
curl http://<pi-ip>:2700/health           # {"ok": true}
journalctl -u skazhi-asr -f               # what it heard, live
```

### 2. App (TV)

```bash
cd app
# set "server" in assets/commands.json to http://<pi-ip>:2700/recognize
./build.sh                                # -> skazhi.apk
./deploy.sh <tv-ip>:5555                  # install, grant mic, enable the service
```

## Adding commands

```json
{ "phrases": ["кинопоиск"], "launch": "ru.kinopoisk.tv" }
```

A command is a list of phrases plus one action:

| Action | Argument |
|---|---|
| `launch` | package name; `launch_verbs` («запусти», «открой», «включи») are prepended automatically |
| `volume` | `up`, `down`, `mute` |
| `key` | media key, e.g. `KEYCODE_MEDIA_PAUSE` |
| `global` | `home`, `back`, `lock` |

The `search` block sets the prefixes («найди», «поищи») and the app that gets
the rest of the phrase as a search query.

Phrases must use words from the Vosk model's vocabulary: Vosk silently drops
unknown words. For example, «спотифай» isn't in it, so it's written as «спот и
фай». `trigger_scan_code` is the voice button's scancode (63 here; find yours
with `adb shell getevent -l`).

## Tested on

Xiaomi TV (MediaTek, China firmware, no GMS) with its Bluetooth voice remote, and
a Raspberry Pi 4. Other TVs may differ in the button scancode, package names and
how the remote mic is routed. Reports and PRs are welcome.

---

## По-русски

**skazhi** — офлайн-голосовое управление телевизором Xiaomi с китайской
прошивкой (без Google, без Play Market, без XiaoAI) через микрофон родного пульта.
Зажимаешь кнопку микрофона, говоришь «запусти ютуб», «сделай громче» или «найди
матрица» — телевизор выполняет. Распознавание речи — Vosk на Raspberry Pi в
домашней сети, без облака и без root.

Умеет: запуск приложений (YouTube, Lampa, Spotify, любое по имени пакета),
громкость, пауза и перемотка, «домой» / «назад», поиск фильма в Lampa голосом,
выключение телевизора. Команды настраиваются в `commands.json`.

Подойдёт, если голосовой поиск на Mi TV / Xiaomi TV с китайской прошивкой не
работает, а Google Assistant поставить нельзя.

## License

[MIT](LICENSE)
