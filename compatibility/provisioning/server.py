#!/usr/bin/env python3
"""Loopback-only UNSIGNED test artifact server. Not a VPK/update implementation.

Config: {"apps": {"package": {"mode": "public"|"key", "keys": {"id": "sha256"},
"artifact": "/absolute/file", "release": "fixture-a"}}}. Reloaded on each request
for revocation tests. No admin HTTP API, no logging of headers/keys/query strings.
"""
import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading

MAX_ARTIFACT = 1024 * 1024  # Deliberately unsuitable for real app payloads.


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, config):
        super().__init__(address, Handler)
        self.config = Path(config)
        self.identities = {}
        self.lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body=b"", **headers):
        self.send_response(status)
        if status != 304:
            self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "private, no-store")
        for key, value in headers.items():
            self.send_header(key.replace("_", "-"), value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        match = re.fullmatch(r"/probe/v1/apps/([a-zA-Z0-9_.]+)/(?:(head)|releases/([a-zA-Z0-9_-]+)/artifact)", self.path)
        if not match:
            self.reply(404)
            return
        app, head, release = match.groups()
        try:
            settings = json.loads(self.server.config.read_text())["apps"].get(app)
            if settings is None:
                self.reply(404)
                return
            if settings["mode"] == "key":
                key_id = self.headers.get("X-Paravoid-Key-Id", "")
                auth = self.headers.get("Authorization", "")
                expected = settings.get("keys", {}).get(key_id)
                if (not expected or not auth.startswith("Bearer ") or
                        not hmac.compare_digest(hashlib.sha256(auth[7:].encode()).hexdigest(), expected)):
                    self.reply(401, WWW_Authenticate='Bearer realm="paravoid-fixture"')
                    return
            elif settings["mode"] != "public":
                raise ValueError("Unknown mode")
            # Authorization happens before all 304, range, HEAD and artifact responses.
            with Path(settings["artifact"]).open("rb") as file:
                artifact = file.read(MAX_ARTIFACT + 1)
            if len(artifact) > MAX_ARTIFACT:
                raise ValueError("Artifact exceeds fixture limit")
            digest = hashlib.sha256(artifact).hexdigest()
            identity = (app, settings["release"])
            with self.server.lock:
                previous = self.server.identities.setdefault(identity, digest)
            if previous != digest:
                raise ValueError("Release identity reused")
            if head:
                body = json.dumps({"profile": "UNSIGNED_FIXTURE_ONLY", "applicationId": app,
                    "releaseId": settings["release"], "size": len(artifact), "sha256": digest,
                    "path": f'/probe/v1/apps/{app}/releases/{settings["release"]}/artifact'},
                    sort_keys=True, separators=(",", ":")).encode()
                etag = '"' + hashlib.sha256(body).hexdigest() + '"'
                if self.headers.get("If-None-Match") == etag:
                    self.reply(304, ETag=etag)
                else:
                    self.reply(200, body, ETag=etag, Content_Type="application/json")
                return
            if release != settings["release"]:
                self.reply(404)
                return
            etag = '"' + digest + '"'
            requested = self.headers.get("Range")
            if requested and self.headers.get("If-Range", etag) == etag:
                bounds = re.fullmatch(r"bytes=(\d+)-(\d*)", requested)
                if not bounds:
                    self.reply(416, Content_Range=f"bytes */{len(artifact)}")
                    return
                start = int(bounds[1])
                end = min(int(bounds[2]) if bounds[2] else len(artifact) - 1, len(artifact) - 1)
                if start > end or start >= len(artifact):
                    self.reply(416, Content_Range=f"bytes */{len(artifact)}")
                    return
                self.reply(206, artifact[start:end + 1], ETag=etag,
                           Content_Range=f"bytes {start}-{end}/{len(artifact)}", Accept_Ranges="bytes")
            else:
                self.reply(200, artifact, ETag=etag, Accept_Ranges="bytes")
        except (OSError, ValueError, KeyError, TypeError):
            self.reply(503)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    with Server(("127.0.0.1", args.port), args.config) as server:
        print(f"UNSIGNED fixture server listening on loopback port {server.server_port}", flush=True)
        server.serve_forever()
