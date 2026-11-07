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

All phrases live in [`server/commands.json`](server/commands.json) on the server: edit, save, done.

## How it works

```
 hold mic button        record while held             server (e.g. a Pi)
 ┌──────────┐  BLE   ┌───────────────────┐  PCM     ┌──────────────────────┐
 │  remote  │ ─────▶ │ TV: skazhi        │ ───────▶ │ Vosk: grammar + free │
 └──────────┘        │ (accessibility    │          │ transcripts, matched │
                     │  service)         │ ◀─────── │ to commands.json     │
                     └───────────────────┘  action  └──────────────────────┘
                       │                    ◀── found over mDNS (_skazhi._tcp)
                       ▼
            launch app · volume · media key · home/back · Lampa search
```

- **[app/](app)** — a tiny Android accessibility service (no UI, ~350 lines of
  Java, built without Gradle). It finds the server on the LAN by itself, records
  the remote mic while the voice button is held, sends the audio and runs the
  action the server answers with. Nothing to configure on the TV.
- **[server/](server)** — a small Python HTTP server around
  [Vosk](https://alphacephei.com/vosk/). It transcribes twice — restricted to the
  command phrases (near perfect on short commands) and free-form (for «найди
  ‹title›») — matches the result against `commands.json` and announces itself
  over mDNS.

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
- Speech is transcribed **twice**: with a grammar of the command phrases (short
  commands come out near perfect) and free-form, because a movie title for
  «найди …» can't be in any grammar.
- Lampa's `SEARCH` intent filter has no `CATEGORY_DEFAULT`, so the search intent
  is resolved and started explicitly with a `query` extra.

## Setup

You need: the TV with network debugging (ADB) enabled, a Raspberry Pi or any
Linux box on the same LAN, and `adb` on your computer.

### 1. Server (Raspberry Pi)

```bash
cd server
sudo ./install.sh                         # downloads vosk-model-small-ru-0.22
# or: sudo ./install.sh /path/to/vosk-model-dir
```

Installs to `/opt/skazhi` and runs as the `skazhi-asr` systemd service on port
2700, announced on the LAN over mDNS. With nftables, tcp/2700 and udp/5353 are
opened to `192.168.0.0/16`. Re-run it to update; your `commands.json` is kept.

```bash
curl http://<pi-ip>:2700/health           # {"ok": true}
journalctl -u skazhi-asr -f               # what it heard, live
```

### 2. App (TV)

Download `skazhi.apk` from [Releases](https://github.com/bobrynya/skazhi/releases/latest), then:

```bash
app/deploy.sh <tv-ip>:5555 ~/Downloads/skazhi.apk   # install, grant mic, enable the service
```

The app finds the server by itself; hold the voice button and talk. To build
the APK yourself: Android cmdline-tools (build-tools 34, platform 34) and a JDK,
then `app/build.sh`.

## Adding commands

Edit `/opt/skazhi/commands.json` on the server and save: it is picked up on the
next command, no restart or rebuild. A broken edit is logged and the previous
version stays in use.

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
