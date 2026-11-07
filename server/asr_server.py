#!/usr/bin/env python3
"""skazhi ASR server: POST raw 16kHz mono PCM16 -> {"text": "..."} via Vosk.

The client sends an optional grammar (JSON array of phrases) in the X-Grammar
header; recognition is then restricted to those phrases, which makes short
voice commands near-perfect even on a noisy/quiet mic. No grammar => free form.
"""
import base64
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
MODEL_PATH = os.environ.get("SKAZHI_MODEL", os.path.join(os.path.dirname(__file__), "model"))
HOST = os.environ.get("SKAZHI_HOST", "0.0.0.0")
PORT = int(os.environ.get("SKAZHI_PORT", "2700"))
RATE = 16000

print(f"loading model from {MODEL_PATH} ...", flush=True)
model = Model(MODEL_PATH)
print("model loaded", flush=True)


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/recognize":
            self._json(404, {"error": "not found"})
            return
        n = int(self.headers.get("Content-Length", 0))
        pcm = self.rfile.read(n) if n else b""
        g64 = self.headers.get("X-Grammar-B64")
        grammar = base64.b64decode(g64).decode("utf-8") if g64 else None
        try:
            if grammar:
                rec = KaldiRecognizer(model, RATE, grammar)  # grammar is a JSON array string
            else:
                rec = KaldiRecognizer(model, RATE)
            rec.SetWords(False)
            rec.AcceptWaveform(pcm)
            text = json.loads(rec.FinalResult()).get("text", "")
            self._json(200, {"text": text})
        except Exception as e:
            self._json(500, {"error": str(e)})

    def log_message(self, *a):
        pass  # quiet


if __name__ == "__main__":
    print(f"skazhi asr on {HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
