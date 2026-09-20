"""Loopback-only HTTP fixture with an independent request audit."""
from collections import Counter
from contextlib import contextmanager, ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import time


class ProbeServer(ThreadingHTTPServer):
    def __init__(self, secure=False):
        super().__init__(("127.0.0.1", 0), Handler)
        self.records = []
        self.tokens = set()
        self.lock = threading.Lock()
        self.secure = secure
        self.certificate = None

    def audit(self, token, pid):
        with self.lock:
            records = [r for r in self.records if r[0] == token and r[1] == str(pid)]
        actual = Counter(r[2] for r in records)
        expected = Counter({"/items": 2, "/defaults": 2, "/echo": 1, "/qualified": 1,
                            "/error": 2, "/malformed": 1, "/slow": 3, "/cache": 1})
        if self.secure: expected = Counter({"/tls": 1})
        assert actual == expected, (actual, expected)
        assert all(r[3] for r in records), records


@contextmanager
def running_server(secure=False):
    with ExitStack() as cleanup:
        server = ProbeServer(secure)
        cleanup.callback(server.server_close)
        if secure:
            directory = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix="paravoid-network-tls-")))
            cert, key = directory / "cert.pem", directory / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=Paravoid Local Probe", "-addext", "subjectAltName=IP:127.0.0.1",
                            "-addext", "basicConstraints=critical,CA:TRUE", "-keyout", str(key), "-out", str(cert)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.minimum_version = ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(cert, key)
            server.socket = tls.wrap_socket(server.socket, server_side=True)
            server.certificate = base64.b64encode(ssl.PEM_cert_to_DER_cert(cert.read_text())).decode()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(timeout=5)


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
        elif self.path == "/tls":
            valid = valid and self.server.secure
            body = {"run": token, "transport": "tls"}
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
