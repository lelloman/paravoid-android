import http.client
import os
from pathlib import Path
import tempfile
import threading
import subprocess
import unittest
from urllib.parse import urlencode

from server import Catalog, Server, scope


APP = "example.app"
KEY = "A" * 43
QUERY = dict(contract="a" * 64, channel="stable", sdk="30", abis="x86_64,x86", runtime="1", format="1", protocol="1")
HEAD_PATH = "/updates/v1/apps/example.app/head?" + urlencode(QUERY)
ARCHIVE_PATH = "/updates/v1/apps/example.app/releases/r1/payload.vpk"


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "payload.vpk"
        self.payload = b"opaque transport bytes; not a signed VPK"
        self.path.write_bytes(self.payload)
        self.catalog = Catalog("apkKey", "/updates/")
        self.catalog.add_head(APP, QUERY, b"opaque signed head fixture")
        self.catalog.add_archive(APP, "r1", self.path)
        self.catalog.grant(KEY, APP, ["stable"], ["r1"])
        self.server = Server(("127.0.0.1", 0), self.catalog)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def get(self, path=HEAD_PATH, auth=True, **headers):
        if auth:
            headers["Authorization"] = "Bearer " + KEY
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_auth_before_200_and_304(self):
        self.assertEqual(401, self.get(auth=False)[0])
        status, headers, body = self.get()
        self.assertEqual(200, status)
        self.assertEqual(b"opaque signed head fixture", body)
        self.assertEqual("private, no-cache", headers["Cache-Control"])
        self.assertEqual("Authorization", headers["Vary"])
        self.assertEqual(304, self.get(**{"If-None-Match": headers["ETag"]})[0])
        self.catalog.revoke(KEY)
        self.assertEqual(403, self.get(**{"If-None-Match": headers["ETag"]})[0])
        self.assertEqual(403, self.get(ARCHIVE_PATH)[0])

    def test_public_and_unknown_scope(self):
        self.catalog.mode = "public"
        self.assertEqual(200, self.get(auth=False)[0])
        self.assertEqual(200, self.get(ARCHIVE_PATH, auth=False)[0])
        self.assertEqual(404, self.get(HEAD_PATH.replace("sdk=30", "sdk=31"), auth=False)[0])

    def test_scope_sensitive_etags(self):
        alternate = dict(QUERY, sdk="31")
        self.catalog.add_head(APP, alternate, b"different signed request scope")
        etag = self.get()[1]["ETag"]
        alternate_path = "/updates/v1/apps/example.app/head?" + urlencode(alternate)
        status, headers, _ = self.get(alternate_path, **{"If-None-Match": etag})
        self.assertEqual(200, status)
        self.assertNotEqual(etag, headers["ETag"])

    def test_resume_and_full_replacement(self):
        status, headers, body = self.get(ARCHIVE_PATH)
        self.assertEqual((200, self.payload), (status, body))
        etag = headers["ETag"]
        status, headers, body = self.get(ARCHIVE_PATH, Range="bytes=5-", **{"If-Range": etag})
        self.assertEqual((206, self.payload[5:]), (status, body))
        self.assertEqual(f"bytes 5-{len(self.payload)-1}/{len(self.payload)}", headers["Content-Range"])
        self.assertEqual(str(len(self.payload)-5), headers["Content-Length"])
        for validator in ("wrong", "W/" + etag):
            status, _, body = self.get(ARCHIVE_PATH, Range="bytes=5-", **{"If-Range": validator})
            self.assertEqual((200, self.payload), (status, body))
        self.assertEqual(200, self.get(ARCHIVE_PATH, Range="bytes=5-")[0])
        self.assertEqual(416, self.get(ARCHIVE_PATH, Range="bytes=999-", **{"If-Range": etag})[0])
        self.catalog.revoke(KEY)
        self.assertEqual(403, self.get(ARCHIVE_PATH, Range="bytes=5-", **{"If-Range": etag})[0])

    def test_wrong_application_and_entitlement(self):
        self.catalog.grant(KEY, "other.app", ["stable"], ["r1"])
        self.assertEqual(403, self.get()[0])
        self.catalog.grant(KEY, APP, ["beta"], [])
        self.assertEqual(403, self.get()[0])
        self.assertEqual(403, self.get(ARCHIVE_PATH)[0])

    def test_malformed_queries_and_ranges(self):
        for path in (HEAD_PATH + "&sdk=30", HEAD_PATH + "&extra=x", HEAD_PATH.replace("sdk=30", "sdk=-1"),
                     HEAD_PATH.replace("x86_64%2Cx86", "x86%2Cx86"), HEAD_PATH.replace("stable", "%ZZ"),
                     HEAD_PATH.replace("protocol=1", "protocol=2")):
            self.assertEqual(400, self.get(path)[0], path)
        for value in ("bytes=0-1,4-", "bytes=-5", "bytes=bad-", "items=0-"):
            self.assertEqual(400, self.get(ARCHIVE_PATH, Range=value)[0])
        self.assertEqual(400, self.get(ARCHIVE_PATH + "?token=bad")[0])

    def test_replacement_and_deletion(self):
        self.catalog.revoke(KEY)
        self.assertEqual(403, self.get()[0])
        new = "B" * 43
        self.catalog.grant(new, APP, ["stable"], ["r1"])
        self.assertEqual(200, self.get(auth=False, Authorization="Bearer " + new)[0])
        self.path.unlink()
        self.assertEqual(404, self.get(ARCHIVE_PATH, auth=False, Authorization="Bearer " + new)[0])

    @unittest.skipUnless(os.environ.get("DELIVERY_TEST_CLASSES"), "requires compiled Java transport")
    def test_java_client_interoperability(self):
        for mode in ("public", "apkKey"):
            self.catalog.mode = mode
            subprocess.run(["java", "-ea", "-cp", os.environ["DELIVERY_TEST_CLASSES"],
                            "com.lelloman.paravoidandroid.delivery.ReferenceServerTest",
                            f"http://127.0.0.1:{self.server.server_port}/updates/", mode], check=True, timeout=15)


if __name__ == "__main__":
    unittest.main()
