import hashlib
from contextlib import closing
import http.client
import io
import json
from pathlib import Path
import struct
import tempfile
import threading
import unittest
import zipfile

import apk_record
from server import Server, MAX_ARTIFACT


class RecordTest(unittest.TestCase):
    def setUp(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zip_file:
            zip_file.writestr("classes.dex", b"fixture bytes")
        original = archive.getvalue()
        end = len(original) - 22
        directory = struct.unpack_from("<I", original, end + 16)[0]
        body = struct.pack("<QI", 9, 0x7109871A) + b"dummy"
        size = 24 + len(body)
        block = struct.pack("<Q", size) + body + struct.pack("<Q", size) + apk_record.MAGIC
        tail = bytearray(original[directory:])
        struct.pack_into("<I", tail, end - directory + 16, directory + len(block))
        self.apk = original[:directory] + block + tail
        self.record = dict(version=1, applicationId="example.app", keyId="a", key="fixture-key")

    def test_preserves_zip_and_signature_pair_and_alignment(self):
        patched = apk_record.personalize(self.apk, self.record)
        old_start, old_dir, _, before = apk_record.inspect(self.apk)
        new_start, new_dir, _, after = apk_record.inspect(patched)
        self.assertEqual(old_start, new_start)
        self.assertEqual((new_dir - old_dir) % 4096, 0)
        self.assertEqual(before[0x7109871A], after[0x7109871A])
        self.assertEqual(json.loads(after[apk_record.RECORD_ID]), self.record)
        with zipfile.ZipFile(io.BytesIO(patched)) as archive:
            self.assertEqual(archive.read("classes.dex"), b"fixture bytes")
        # This synthetic signature is structural only; real verification is a device-runner gate.

    def test_rejects_repersonalization(self):
        with self.assertRaises(ValueError):
            apk_record.personalize(apk_record.personalize(self.apk, self.record), self.record)

    def test_rejects_bad_layout_and_oversized_record(self):
        for data in (self.apk + b"trailing", self.apk[:-1], b"not an APK"):
            with self.assertRaises(ValueError):
                apk_record.personalize(data, self.record)
        with self.assertRaises(ValueError):
            apk_record.personalize(self.apk, dict(self.record, key="x" * 4096))


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.artifact = root / "artifact.bin"
        self.artifact.write_bytes(b"not executable: fixture artifact\n")
        self.config = root / "server.json"
        self.state = {"apps": {app: dict(mode=mode, keys={"a": hashlib.sha256(b"test-secret").hexdigest()},
                     artifact=str(self.artifact), release="fixture-a")
                     for app, mode in (("example.private", "key"), ("example.public", "public"))}}
        self.save()
        self.server = Server(("127.0.0.1", 0), self.config)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.auth = {"X-Paravoid-Key-Id": "a", "Authorization": "Bearer test-secret"}

    def save(self):
        self.config.write_text(json.dumps(self.state))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, path="head", headers=None, app="example.private", method="GET"):
        with closing(http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)) as connection:
            connection.request(method, f"/probe/v1/apps/{app}/{path}", headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def test_public_and_private_head(self):
        self.assertEqual(self.request()[0], 401)
        self.assertEqual(self.request(headers=self.auth)[0], 200)
        self.assertEqual(self.request(app="example.public")[0], 200)
        self.assertEqual(self.request(headers=dict(self.auth, Authorization="Bearer wrong"))[0], 401)

    def test_revocation_covers_conditional_head_range_and_download(self):
        status, headers, _ = self.request(headers=self.auth)
        conditional = dict(self.auth, **{"If-None-Match": headers["ETag"]})
        self.assertEqual(self.request(headers=conditional)[0], 304)
        self.state["apps"]["example.private"]["keys"] = {}
        self.save()
        self.assertEqual(self.request(headers=conditional)[0], 401)
        self.assertEqual(self.request(headers=self.auth, method="HEAD")[0], 401)
        self.assertEqual(self.request("releases/fixture-a/artifact", self.auth)[0], 401)
        self.assertEqual(self.request("releases/fixture-a/artifact", dict(self.auth, Range="bytes=0-5"))[0], 401)
        self.assertEqual(self.request(app="example.public")[0], 200)

    def test_ranges_and_full_response_on_validator_mismatch(self):
        path = "releases/fixture-a/artifact"
        status, _, body = self.request(path, dict(self.auth, Range="bytes=4-8"))
        self.assertEqual((status, body), (206, self.artifact.read_bytes()[4:9]))
        self.assertEqual(self.request(path, dict(self.auth, Range="bytes=999-"))[0], 416)
        status, _, body = self.request(path, dict(self.auth, Range="bytes=4-", **{"If-Range": '"wrong"'}))
        self.assertEqual((status, body), (200, self.artifact.read_bytes()))

    def test_new_key_succeeds_old_key_stays_revoked(self):
        self.state["apps"]["example.private"]["keys"] = {"b": hashlib.sha256(b"replacement").hexdigest()}
        self.save()
        self.assertEqual(self.request(headers=self.auth)[0], 401)
        self.assertEqual(self.request(headers={"X-Paravoid-Key-Id": "b", "Authorization": "Bearer replacement"})[0], 200)

    def test_identity_is_immutable_during_server_lifetime(self):
        self.assertEqual(self.request(headers=self.auth)[0], 200)
        self.artifact.write_bytes(b"changed")
        self.assertEqual(self.request(headers=self.auth)[0], 503)
        self.state["apps"]["example.private"]["release"] = "fixture-b"
        self.save()
        self.assertEqual(self.request(headers=self.auth)[0], 200)

    def test_bounded_artifacts_and_bad_configuration_fail_closed(self):
        self.artifact.write_bytes(bytes(MAX_ARTIFACT + 1))
        self.assertEqual(self.request(headers=self.auth)[0], 503)
        self.config.write_text("broken")
        self.assertEqual(self.request(headers=self.auth)[0], 503)

    def test_unknown_paths_and_releases(self):
        self.assertEqual(self.request("../head", self.auth)[0], 404)
        self.assertEqual(self.request("releases/absent/artifact", self.auth)[0], 404)
        self.assertEqual(self.request(app="unknown")[0], 404)

    def test_presigned_head_is_opaque_but_still_authorized(self):
        head = Path(self.directory.name) / "head.sig"
        head.write_bytes(b"deliberately invalid: the client must verify this")
        self.state["apps"]["example.private"]["signedHead"] = str(head)
        self.save()
        self.assertEqual(self.request()[0], 401)
        status, headers, body = self.request(headers=self.auth)
        self.assertEqual((status, body), (200, head.read_bytes()))
        self.assertEqual(headers["Content-Type"], "application/octet-stream")
        conditional = dict(self.auth, **{"If-None-Match": headers["ETag"]})
        self.assertEqual(self.request(headers=conditional)[0], 304)
        self.state["apps"]["example.private"]["keys"] = {}
        self.save()
        self.assertEqual(self.request(headers=conditional)[0], 401)

    def test_presigned_head_bounds(self):
        head = Path(self.directory.name) / "head.sig"
        head.write_bytes(b"x" * 4097)
        self.state["apps"]["example.private"]["signedHead"] = str(head)
        self.save()
        self.assertEqual(self.request(headers=self.auth)[0], 503)


if __name__ == "__main__":
    unittest.main()
