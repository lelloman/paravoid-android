import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from apk_personalize import GRANT_ID, developer_signatures, inspect, insert, personalize, verify_grant, verify_pinned_grant


class PersonalizeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "/home/lelloman/Android/Sdk")))
        cls.apksigner = sdk / "build-tools/36.0.0/apksigner"
        aapt = sdk / "build-tools/36.0.0/aapt2"
        cls.classes = os.environ.get("DELIVERY_TEST_CLASSES")
        if not cls.apksigner.is_file() or not aapt.is_file() or not cls.classes:
            raise unittest.SkipTest("requires Android build-tools 36 and compiled shared verifier")
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.vectors = Path("../paravoid-contract/src/test/resources/metadata-vectors").resolve()
        manifest = cls.root / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="example.app.paravoid" android:versionCode="1" android:versionName="1">'
                            '<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>'
                            '<application android:label="Carrier host fixture"/></manifest>')
        cls.source = cls.root / "signed.apk"
        commands = [
            [str(aapt), "link", "--manifest", str(manifest), "-I", str(sdk / "platforms/android-36/android.jar"),
             "-o", str(cls.root / "unsigned.apk")],
            ["keytool", "-genkeypair", "-keystore", str(cls.root / "test.p12"), "-storepass", "testpassword",
             "-keypass", "testpassword", "-alias", "test", "-dname", "CN=Ephemeral carrier host test",
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "2", "-noprompt"],
            [str(cls.apksigner), "sign", "--ks", str(cls.root / "test.p12"), "--ks-key-alias", "test",
             "--ks-pass", "pass:testpassword", "--key-pass", "pass:testpassword", "--v1-signing-enabled", "false",
             "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
             "--out", str(cls.source), str(cls.root / "unsigned.apk")],
        ]
        for command in commands:
            subprocess.run(command, check=True, capture_output=True, timeout=60)
        cls.pinned = cls.root / "pinned.apk"
        cls.pinned_grant = cls.root / "pinned-grant.json"
        subprocess.run(["java", "-cp", cls.classes, "com.lelloman.paravoidandroid.contract.PolicyFixtures",
                        str(cls.root / "assets"), str(cls.pinned_grant)], check=True, capture_output=True, timeout=60)
        subprocess.run([str(aapt), "link", "--manifest", str(manifest), "-I", str(sdk / "platforms/android-36/android.jar"),
                        "-A", str(cls.root / "assets"), "-o", str(cls.root / "pinned-unsigned.apk")], check=True, capture_output=True, timeout=60)
        subprocess.run([str(cls.apksigner), "sign", "--ks", str(cls.root / "test.p12"), "--ks-key-alias", "test",
                        "--ks-pass", "pass:testpassword", "--key-pass", "pass:testpassword", "--v1-signing-enabled", "false",
                        "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                        "--out", str(cls.pinned), str(cls.root / "pinned-unsigned.apk")], check=True, capture_output=True, timeout=60)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def verifier(self, mode, path):
        verify_grant(self.classes, self.vectors / "trust.json", "a" * 64,
                     "https://updates.example.test/", "stable", mode, path)

    def test_actual_v2_v3_signatures_and_shared_grant_readback(self):
        output = self.root / "personalized.apk"
        before = self.source.read_bytes()
        personalize(self.source, output, self.vectors / "grant.json", self.apksigner, self.verifier)
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(developer_signatures(self.apksigner, self.source), developer_signatures(self.apksigner, output))
        after = output.read_bytes()
        self.assertEqual((len(after) - len(before)) % 4096, 0)
        self.assertEqual(inspect(after)[3][GRANT_ID], (self.vectors / "grant.json").read_bytes())
        self.assertEqual(hashlib.sha256(after).hexdigest() + "\n", Path(str(output) + ".sha256").read_text())
        self.assertFalse(Path(str(output) + ".idsig").exists())
        with self.assertRaises(ValueError):
            personalize(self.source, output, self.vectors / "grant.json", self.apksigner, self.verifier)
        with self.assertRaises(ValueError):
            insert(after, (self.vectors / "grant.json").read_bytes())

    def test_wrong_audience_and_malformed_grants_never_publish(self):
        for name in ("grant-wrong-audience.json", "head-a.json"):
            output = self.root / (name + ".apk")
            with self.assertRaises(ValueError):
                personalize(self.source, output, self.vectors / name, self.apksigner, self.verifier)
            self.assertFalse(output.exists())

    def test_unsupported_and_malformed_inputs_fail(self):
        for data in (b"", b"not an APK", self.source.read_bytes()[:-1]):
            with self.assertRaises(ValueError):
                insert(data, b"opaque")
        with self.assertRaises(ValueError):
            insert(self.source.read_bytes(), b"x" * 16385)
        duplicate = self.root / "with-v4.apk"
        shutil.copyfile(self.source, duplicate)
        Path(str(duplicate) + ".idsig").write_bytes(b"old sidecar")
        output = self.root / "v4-output.apk"
        with self.assertRaises(ValueError):
            personalize(duplicate, output, self.vectors / "grant.json", self.apksigner, self.verifier)
        self.assertFalse(output.exists())

    def test_production_cli_uses_only_apk_pinned_policy(self):
        output = self.root / "pinned-personalized.apk"
        env = dict(os.environ, PARAVOID_GRANT_TOOL_CLASSES=self.classes)
        result = subprocess.run(["python3", "tools/apk_personalize.py", str(self.pinned), str(output),
                                 "--grant", str(self.pinned_grant), "--apksigner", str(self.apksigner)],
                                env=env, capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        verify_pinned_grant(self.classes, self.pinned, "apk", output)
        self.assertEqual(developer_signatures(self.apksigner, self.pinned), developer_signatures(self.apksigner, output))
        with self.assertRaises(ValueError):
            verify_pinned_grant(self.classes, self.source, "envelope", self.pinned_grant)


if __name__ == "__main__":
    unittest.main()
