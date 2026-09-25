"""Renew signed discovery announcements; archive bytes and release versions never change."""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class Publisher:
    def __init__(self, config, root, state_directory, clock=time.time):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        self.config = dict(config)
        self.clock = clock
        self.app = config["applicationId"]
        self.contract = config["shellContractId"]
        self.channel = config.get("channel", "stable")
        self.runtime = config.get("runtimeAbi", 1)
        self.key_id = config["keyId"]
        self.key = serialization.load_der_private_key((root / config["privateKeyFile"]).read_bytes(), None)
        if not isinstance(self.key, rsa.RSAPrivateKey) or self.key.key_size != 3072 or self.key.public_key().public_numbers().e != 65537:
            raise ValueError("metadata signing requires RSA-3072/e65537")
        self.entries = []
        self.archives = []
        identities = {}
        for item in config.get("releases", []):
            path = root / item["file"]
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(32768), b""):
                    digest.update(chunk)
            release = {k: item[k] for k in ("releaseId", "payloadVersion", "manifestSha256", "archiveSha256", "archiveSize")}
            if digest.hexdigest() != release["archiveSha256"] or path.stat().st_size != release["archiveSize"]:
                raise ValueError("configured archive identity differs from bytes")
            previous = identities.setdefault(release["releaseId"], release)
            if previous != release:
                raise ValueError("conflicting release identity")
            self.entries.append(dict(minSdk=item.get("minSdk", 30), maxSdk=item.get("maxSdk", 0), abis=item.get("abis", []), release=release))
            self.archives.append((release["releaseId"], path))
        self.status = config.get("status", "available" if self.entries else "no-compatible-release")
        if self.status not in ("available", "no-compatible-release", "shell-update-required") or self.status != "available" and self.entries:
            raise ValueError("invalid publication status")
        self.fingerprint = hashlib.sha256(canonical(dict(entries=self.entries, status=self.status, key=self.key_id,
            public=base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode(), runtime=self.runtime))).hexdigest()
        self.directory = Path(state_directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / (hashlib.sha256(canonical([self.app, self.contract, self.channel])).hexdigest() + ".json")
        self.lock = threading.Lock()

    def _write(self, state):
        fd, temporary = tempfile.mkstemp(prefix="announcement-", dir=self.directory)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(canonical(state))
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
            directory = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def announcement(self, query=None):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        with self.lock, self.path.with_suffix(".lock").open("a+b") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            now = int(self.clock())
            state = json.loads(self.path.read_text()) if self.path.exists() else None
            if state is not None and (state["issuedAt"] > now + 300 or state["headRevision"] < 1):
                raise ValueError("publication clock/state is invalid")
            if state is None or state["fingerprint"] != self.fingerprint or state["expiresAt"] - now <= 21600:
                revision = max(int(self.config.get("minimumRevision", 1)), 1 if state is None else state["headRevision"] + 1)
                state = dict(fingerprint=self.fingerprint, headRevision=revision, issuedAt=now, expiresAt=now + 86400, responses={})
            cache_key = "feed" if query is None else canonical(query).decode()
            if cache_key not in state["responses"]:
                body = dict(version=1, applicationId=self.app, shellContractId=self.contract, channel=self.channel,
                    runtimeAbi=self.runtime, formatVersion=1, headRevision=state["headRevision"], issuedAt=state["issuedAt"], expiresAt=state["expiresAt"], status=self.status)
                role = "feed" if query is None else "head"
                if query is None:
                    body["releases"] = self.entries
                else:
                    sdk, abis = int(query["sdk"]), query["abis"].split(",") if query["abis"] else []
                    body.update(sdk=sdk, abis=abis, release=None)
                    candidates = [e["release"] for e in self.entries if sdk >= e["minSdk"] and (e["maxSdk"] == 0 or sdk <= e["maxSdk"])
                        and (not e["abis"] or set(abis).intersection(e["abis"]))]
                    if candidates:
                        version = max(r["payloadVersion"] for r in candidates)
                        best = [r for r in candidates if r["payloadVersion"] == version]
                        if any(r != best[0] for r in best):
                            raise ValueError("ambiguous release candidates")
                        body.update(status="available", release=best[0])
                    elif body["status"] == "available":
                        body["status"] = "no-compatible-release"
                encoded = canonical(body)
                signature = self.key.sign(b"paravoid/v1/" + role.encode() + b"\n" + encoded, padding.PKCS1v15(), hashes.SHA256())
                envelope = canonical(dict(keyId=self.key_id, body=base64.b64encode(encoded).decode(), signature=base64.b64encode(signature).decode()))
                if len(envelope) > 65536:
                    raise ValueError("discovery metadata exceeds protocol limit")
                state["responses"][cache_key] = envelope.decode()
                self._write(state)
            return state["responses"][cache_key].encode()
