#!/usr/bin/env python3
"""skazhi server: speech in, TV action out.

The TV app POSTs raw 16 kHz mono PCM16; the server transcribes it with Vosk
twice (restricted to the command phrases, and free-form for "найди <title>"),
matches the result against commands.json and answers with the action to run.

commands.json is re-read whenever it changes, so edits apply without a restart.
The server announces itself on the LAN over mDNS as ``_skazhi._tcp``, so the
app finds it without any address configuration.
"""

import json
import logging
import os
import signal
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, NoReturn

from vosk import KaldiRecognizer, Model, SetLogLevel
from zeroconf import IPVersion, ServiceInfo, Zeroconf

from commands import Commands

HERE = Path(__file__).resolve().parent
MODEL_PATH = os.environ.get("SKAZHI_MODEL", str(HERE / "model"))
COMMANDS_PATH = Path(os.environ.get("SKAZHI_COMMANDS", HERE / "commands.json"))
HOST = os.environ.get("SKAZHI_HOST", "0.0.0.0")
PORT = int(os.environ.get("SKAZHI_PORT", "2700"))
RATE = 16000
MAX_BYTES = RATE * 2 * 60  # 60 s of PCM16; the app sends at most 10 s
SERVICE_TYPE = "_skazhi._tcp.local."

log = logging.getLogger("skazhi")


class CommandsFile:
    """commands.json, reloaded when its mtime changes.

    A broken edit is logged and the last good config stays in use, so a typo
    never takes the TV's voice control down.
    """

    def __init__(self, path: Path) -> None:
        """Load the file; fail loudly at startup if it is invalid."""
        self._path = path
        self._lock = threading.Lock()
        self._mtime = path.stat().st_mtime
        self._commands = Commands(path.read_text(encoding="utf-8"))

    def get(self) -> Commands:
        """Return the current config, reloading it if the file changed."""
        with self._lock:
            try:
                mtime = self._path.stat().st_mtime
                if mtime != self._mtime:
                    self._mtime = mtime
                    self._commands = Commands(self._path.read_text(encoding="utf-8"))
                    log.info("reloaded %s", self._path)
            except (OSError, ValueError) as e:
                log.warning("keeping previous commands, %s is broken: %s", self._path, e)
            return self._commands


def recognize(pcm: bytes, grammar: str | None = None) -> str:
    """Transcribe a whole utterance with a fresh recognizer.

    Args:
        pcm: Raw 16 kHz mono PCM16 audio.
        grammar: JSON array of allowed phrases as a string (``'["ютуб", "[unk]"]'``),
            or None for free-form recognition.

    Returns:
        The recognized text, lowercase; empty if nothing was heard.
    """
    rec = KaldiRecognizer(model, RATE, grammar) if grammar else KaldiRecognizer(model, RATE)
    rec.AcceptWaveform(pcm)
    text: str = json.loads(rec.FinalResult()).get("text", "")
    return text


class Handler(BaseHTTPRequestHandler):
    """HTTP API: ``GET /health``, ``GET /config``, ``POST /recognize``. One thread per request."""

    timeout = 30  # a client that under-sends its Content-Length must not pin a thread forever

    def _json(self, code: int, obj: dict[str, Any]) -> None:
        """Send ``obj`` as a UTF-8 JSON response with status ``code``."""
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        """``/health``: liveness. ``/config``: what the app needs before recording."""
        if self.path == "/health":
            self._json(200, {"ok": True})
        elif self.path == "/config":
            self._json(200, {"trigger_scan_code": commands.get().trigger_scan_code})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        """Recognize the PCM body and match it to a command.

        Body: raw 16 kHz mono PCM16, at most ``MAX_BYTES``.

        Responds 200 ``{"text", "free", "action", "arg", "query"}``: the two
        transcripts and the matched command (``action`` is null if nothing
        matched, ``query`` is set only for search); 400 on a malformed request;
        500 if recognition fails.
        """
        if self.path != "/recognize":
            self._json(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            if not 0 <= n <= MAX_BYTES:
                raise ValueError(f"Content-Length {n} out of range")
            pcm = self.rfile.read(n) if n else b""
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return
        try:
            cmds = commands.get()
            free = recognize(pcm)
            text = recognize(pcm, cmds.grammar)
            hit = cmds.match(text, free)
            log.info("%.1fs text=%r free=%r -> %s", len(pcm) / (RATE * 2), text, free, hit)
            self._json(200, {
                "text": text,
                "free": free,
                "action": hit.action if hit else None,
                "arg": hit.arg if hit else None,
                "query": hit.query if hit else None,
            })
        except Exception as e:  # HTTP boundary: any Vosk failure becomes a logged 500, not a dropped socket
            log.exception("recognition failed")
            self._json(500, {"error": str(e)})

    def log_message(self, format: str, *args: Any) -> None:
        """Silence the per-request access log; do_POST logs what was heard instead."""


def lan_ip() -> str:
    """The address other LAN devices reach this host at: SKAZHI_ADVERTISE_IP, else the default route's."""
    if ip := os.environ.get("SKAZHI_ADVERTISE_IP"):
        return ip
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("192.0.2.1", 9))  # no packet is sent; this just picks the outgoing interface
        addr: str = s.getsockname()[0]
        return addr


def advertise(ip: str) -> tuple[Zeroconf, ServiceInfo]:
    """Announce ``_skazhi._tcp`` on the LAN interface only (not on VPN/tunnel ones)."""
    host = socket.gethostname()
    info = ServiceInfo(
        SERVICE_TYPE,
        f"skazhi on {host}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=PORT,
        server=f"{host}.local.",
    )
    zc = Zeroconf(interfaces=[ip], ip_version=IPVersion.V4Only)
    zc.register_service(info)
    return zc, info


def _exit(*_: object) -> NoReturn:
    sys.exit(0)  # SIGTERM from systemd -> SystemExit, so the finally below says goodbye on mDNS


if __name__ == "__main__":
    # journald adds timestamps; SKAZHI_LOG_LEVEL=DEBUG also shows zeroconf internals.
    logging.basicConfig(level=os.environ.get("SKAZHI_LOG_LEVEL", "INFO"), format="%(levelname)s %(name)s: %(message)s")
    SetLogLevel(-1)
    commands = CommandsFile(COMMANDS_PATH)
    log.info("loading model from %s", MODEL_PATH)
    model = Model(MODEL_PATH)
    ip = lan_ip()
    zc, info = advertise(ip)
    signal.signal(signal.SIGTERM, _exit)
    log.info("listening on %s:%d, announced as %s:%d, commands from %s", HOST, PORT, ip, PORT, COMMANDS_PATH)
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    finally:
        zc.unregister_service(info)
        zc.close()
