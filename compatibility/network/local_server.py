"""Loopback-only HTTP fixture with an independent request audit."""
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time


class ProbeServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.records = []
        self.tokens = set()
        self.lock = threading.Lock()

    def audit(self, token, pid):
        with self.lock:
            records = [r for r in self.records if r[0] == token and r[1] == str(pid)]
        actual = Counter(r[2] for r in records)
        expected = Counter({"/items": 2, "/defaults": 2, "/echo": 1, "/qualified": 1,
                            "/error": 2, "/malformed": 1, "/slow": 3, "/cache": 1})
        assert actual == expected, (actual, expected)
        assert all(r[3] for r in records), records


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def respond(self):
        token = self.headers.get("X-Probe-Run")
        pid = self.headers.get("X-Probe-Pid")
        valid = token in self.server.tokens and pid and pid.isdecimal() and self.headers.get("X-Probe-Interceptor") == "payload"
        item = {"display_name": "caffè ☕", "count": 7, "ignored_extra": True}
        body = {"data": [item], "run": token}
        status = 200
        if self.path == "/defaults":
            del item["count"]
        elif self.path == "/qualified":
            body = {"label": "mixed", "run": token}
        elif self.path == "/error":
            status, body = 422, {"error": "intentional", "run": token}
        elif self.path == "/echo":
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                valid = valid and self.command == "POST" and body["run"] == token
                valid = valid and body["data"] == [{"display_name": "posted ☕", "count": 11}]
            except (ValueError, KeyError, TypeError):
                valid = False
        elif self.path not in ("/items", "/slow", "/malformed", "/cache"):
            valid = False
        with self.server.lock:
            self.server.records.append((token, pid, self.path, bool(valid)))
        if not valid:
            status, body = 400, {"error": "invalid request"}
        encoded = b'{"data":[' if self.path == "/malformed" and valid else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        if self.path == "/cache": self.send_header("Cache-Control", "public, max-age=3600")
        self.end_headers()
        self.wfile.flush()
        if self.path == "/slow": time.sleep(2)
        try:
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected cancellation/timeout tests close these sockets.
