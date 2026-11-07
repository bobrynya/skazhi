#!/usr/bin/env python3
"""skazhi ASR server: POST raw 16kHz mono PCM16 -> {"text": "...", "free": "..."} via Vosk.

The client sends an optional grammar (JSON array of phrases, base64 in
X-Grammar-B64); "text" is then restricted to those phrases, which makes short
voice commands near-perfect even on a quiet mic. "free" is always the
unrestricted transcript (needed for "find <movie title>").
"""
import base64
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from vosk import KaldiRecognizer, Model, SetLogLevel

SetLogLevel(-1)
MODEL_PATH = os.environ.get("SKAZHI_MODEL", os.path.join(os.path.dirname(__file__), "model"))
HOST = os.environ.get("SKAZHI_HOST", "0.0.0.0")
PORT = int(os.environ.get("SKAZHI_PORT", "2700"))
RATE = 16000
MAX_BYTES = RATE * 2 * 60  # 60 s of PCM16; the app sends at most 10 s

print(f"loading model from {MODEL_PATH} ...", flush=True)
model = Model(MODEL_PATH)
print("model loaded", flush=True)


def recognize(pcm: bytes, grammar: str | None = None) -> str:
    """grammar is a JSON array of phrases as a string; None means free-form."""
    rec = KaldiRecognizer(model, RATE, grammar) if grammar else KaldiRecognizer(model, RATE)
    rec.AcceptWaveform(pcm)
    text: str = json.loads(rec.FinalResult()).get("text", "")
    return text


class Handler(BaseHTTPRequestHandler):
    timeout = 30  # a client that under-sends its Content-Length must not pin a thread forever

    def _json(self, code: int, obj: dict[str, Any]) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._json(200, {"ok": True})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/recognize":
            self._json(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            if not 0 <= n <= MAX_BYTES:
                raise ValueError(f"Content-Length {n} out of range")
            pcm = self.rfile.read(n) if n else b""
            g64 = self.headers.get("X-Grammar-B64")
            grammar = base64.b64decode(g64).decode("utf-8") if g64 else None
        except ValueError as e:  # bad int, base64 (binascii.Error) and UTF-8 are all ValueError
            self._json(400, {"error": str(e)})
            return
        try:
            free = recognize(pcm)
            text = recognize(pcm, grammar) if grammar else free
            print(f"{len(pcm) / (RATE * 2):.1f}s text={text!r} free={free!r}", flush=True)
            self._json(200, {"text": text, "free": free})
        except Exception as e:  # noqa: BLE001 - HTTP boundary: any Vosk failure becomes a 500, not a dropped socket
            self._json(500, {"error": str(e)})

    def log_message(self, format: str, *args: Any) -> None:
        pass  # quiet


if __name__ == "__main__":
    print(f"skazhi asr on {HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
