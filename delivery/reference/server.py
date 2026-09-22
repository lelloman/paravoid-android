#!/usr/bin/env python3
"""Store-agnostic HTTP fixture server. Serve supplied pre-signed heads; no signing/parser duplication.

Local HTTP only; a real deployment requires a configured HTTPS reverse proxy.
All configured files must be publisher-controlled, immutable and already verified.
This is a transport reference, not a publication/catalog service or wire-freeze claim.
"""
import argparse
from dataclasses import dataclass
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
from urllib.parse import parse_qsl, urlsplit

PARAMETERS = frozenset(("contract", "channel", "sdk", "abis", "runtime", "format", "protocol"))
IDENTIFIER = r"[A-Za-z0-9_-]{1,64}"
APP = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+"
ABIS = frozenset(("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
MAX_HEAD = 65536
MAX_ARCHIVE = 1 << 30


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(32768), b""):
            digest.update(chunk)
    return '"' + digest.hexdigest() + '"'


def scope(parameters):
    if set(parameters) != PARAMETERS:
        raise ValueError("invalid query fields")
    if not re.fullmatch(r"[0-9a-f]{64}", parameters["contract"]):
        raise ValueError("invalid contract")
    if not re.fullmatch(IDENTIFIER, parameters["channel"]):
        raise ValueError("invalid channel")
    for name in ("sdk", "runtime"):
        if not re.fullmatch(r"[1-9][0-9]{0,9}", parameters[name]):
            raise ValueError("invalid capability")
    if parameters["format"] != "1" or parameters["protocol"] != "1":
        raise ValueError("unsupported protocol")
    abis = parameters["abis"].split(",") if parameters["abis"] else []
    if len(abis) != len(set(abis)) or not set(abis) <= ABIS:
        raise ValueError("invalid ABIs")
    return tuple((key, parameters[key]) for key in sorted(PARAMETERS))


@dataclass(frozen=True)
class Head:
    body: bytes
    etag: str


@dataclass(frozen=True)
class Archive:
    path: Path
    size: int
    etag: str


class Catalog:
    """Operator-provided immutable files. Entitlement model deliberately stays out of the protocol."""
    def __init__(self, mode="public", prefix="/"):
        if mode not in ("public", "apkKey"):
            raise ValueError("invalid auth mode")
        if not re.fullmatch(r"/(?:[A-Za-z0-9_-]+/)*", prefix):
            raise ValueError("invalid deployment prefix")
        self.mode, self.prefix = mode, prefix
        self.heads, self.archives = {}, {}
        self._grants = {}
        self._lock = threading.Lock()

    def add_head(self, app, parameters, body):
        if not re.fullmatch(APP, app) or not 0 < len(body) <= MAX_HEAD:
            raise ValueError("invalid head fixture")
        key = (app, scope(parameters))
        if key in self.heads:
            raise ValueError("duplicate request scope")
        self.heads[key] = Head(bytes(body), '"' + hashlib.sha256(body).hexdigest() + '"')

    def add_archive(self, app, release, path):
        path = Path(path)
        if not re.fullmatch(APP, app) or not re.fullmatch(IDENTIFIER, release):
            raise ValueError("invalid archive route")
        size = path.stat().st_size
        if not 0 < size <= MAX_ARCHIVE or (app, release) in self.archives:
            raise ValueError("invalid archive fixture")
        self.archives[(app, release)] = Archive(path, size, digest_file(path))

    def grant(self, key, app, channels, releases):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", key) or not re.fullmatch(APP, app):
            raise ValueError("invalid authorization fixture")
        with self._lock:
            self._grants[key] = (app, frozenset(channels), frozenset(releases))

    def revoke(self, key):
        with self._lock:
            self._grants.pop(key, None)

    def authorize(self, authorization, app, channel=None, release=None):
        if self.mode == "public":
            return 200
        if authorization is None or not authorization.startswith("Bearer "):
            return 401
        with self._lock:
            grant = self._grants.get(authorization[7:])
        if grant is None or grant[0] != app:
            return 403
        if channel is not None and channel not in grant[1] or release is not None and release not in grant[2]:
            return 403
        return 200


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, catalog):
        self.catalog = catalog
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # Never log credentials, user-controlled URLs or query values.

    def fail(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "private, no-cache")
        self.send_header("Vary", "Authorization")
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()

    def headers_for(self, status, mime, length, etag):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(length))
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "private, no-cache")
        self.send_header("Vary", "Authorization")
        self.send_header("Content-Encoding", "identity")

    def do_GET(self):
        try:
            self.serve_get()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Cancellation has no effect on catalog or other clients.

    def serve_get(self):
        catalog = self.server.catalog
        for name in ("Authorization", "Range", "If-Range", "If-None-Match", "Accept-Encoding"):
            if len(self.headers.get_all(name, [])) > 1:
                return self.fail(400)
        if len(self.path) > 8192:
            return self.fail(414)
        url = urlsplit(self.path)
        if url.scheme or url.netloc or url.fragment or not url.path.startswith(catalog.prefix):
            return self.fail(400)
        route = url.path[len(catalog.prefix):]
        head = re.fullmatch(r"v1/apps/(" + APP + r")/head", route)
        archive = re.fullmatch(r"v1/apps/(" + APP + r")/releases/(" + IDENTIFIER + r")/payload\.vpk", route)
        if head:
            try:
                if re.search(r"%(?![0-9a-fA-F]{2})", url.query):
                    raise ValueError("invalid escape")
                pairs = parse_qsl(url.query, keep_blank_values=True, strict_parsing=True,
                                  encoding="utf-8", errors="strict", max_num_fields=7)
                if len(dict(pairs)) != len(pairs):
                    raise ValueError("duplicate parameter")
                query = dict(pairs)
                selected_scope = scope(query)
            except (ValueError, UnicodeError):
                return self.fail(400)
            auth = catalog.authorize(self.headers.get("Authorization"), head[1], channel=query["channel"])
            if auth != 200:
                return self.fail(auth)
            entry = catalog.heads.get((head[1], selected_scope))
            if entry is None:
                return self.fail(404)  # Never fabricate an unsigned no-compatible-release response.
            if self.headers.get("If-None-Match") == entry.etag:
                self.send_response(304)
                self.send_header("ETag", entry.etag)
                self.send_header("Cache-Control", "private, no-cache")
                self.send_header("Vary", "Authorization")
                self.end_headers()
                return
            self.headers_for(200, "application/json", len(entry.body), entry.etag)
            self.end_headers()
            self.wfile.write(entry.body)
        elif archive:
            if url.query:
                return self.fail(400)
            auth = catalog.authorize(self.headers.get("Authorization"), archive[1], release=archive[2])
            if auth != 200:
                return self.fail(auth)
            entry = catalog.archives.get((archive[1], archive[2]))
            if entry is None:
                return self.fail(404)
            offset = 0
            partial = False
            requested = self.headers.get("Range")
            if requested is not None:
                match = re.fullmatch(r"bytes=([0-9]{1,19})-", requested)
                if not match:
                    return self.fail(400)
                # Missing/mismatched If-Range yields a whole representation.
                if self.headers.get("If-Range") == entry.etag:
                    offset = int(match[1])
                    if offset >= entry.size:
                        self.send_response(416)
                        self.send_header("Content-Range", "bytes */" + str(entry.size))
                        self.send_header("Content-Length", "0")
                        self.send_header("Cache-Control", "private, no-cache")
                        self.send_header("Vary", "Authorization")
                        self.end_headers()
                        return
                    partial = True
            # Open before success headers, so deletion races are an HTTP error.
            try:
                source = entry.path.open("rb")
            except OSError:
                return self.fail(404)
            with source:
                source.seek(offset)
                self.headers_for(206 if partial else 200, "application/vnd.paravoid.vpk", entry.size - offset, entry.etag)
                self.send_header("Accept-Ranges", "bytes")
                if partial:
                    self.send_header("Content-Range", f"bytes {offset}-{entry.size - 1}/{entry.size}")
                self.end_headers()
                remaining = entry.size - offset
                while remaining:
                    chunk = source.read(min(32768, remaining))
                    if not chunk:
                        self.close_connection = True
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        else:
            self.fail(404)


def load_catalog(path):
    config = json.loads(path.read_text())
    catalog = Catalog(config["authentication"], config.get("prefix", "/"))
    root = path.parent
    for entry in config.get("heads", []):
        with (root / entry["file"]).open("rb") as source:
            body = source.read(MAX_HEAD + 1)
        catalog.add_head(entry["applicationId"], entry["query"], body)
    for entry in config.get("archives", []):
        catalog.add_archive(entry["applicationId"], entry["releaseId"], root / entry["file"])
    for entry in config.get("grants", []):
        catalog.grant(entry["key"], entry["applicationId"], entry["channels"], entry["releases"])
    return catalog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", type=Path, help="Private operator config; never put credentials in argv")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = Server(("127.0.0.1", args.port), load_catalog(args.catalog))
    print("Paravoid transport reference listening on loopback", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
