"""Compile real, standalone test DEX files; no APK/application sources are modified."""
from pathlib import Path
import subprocess


def compile_payloads(sdk, directory):
    result = {}
    source = Path(__file__).resolve().parent / "dex"
    for variant in ("a", "b", "failing"):
        output = Path(directory) / variant
        output.mkdir(parents=True)
        subprocess.run(["javac", "--release", "8", "-d", str(output), str(source / variant / "Entry.java")], check=True)
        subprocess.run([str(Path(sdk) / "build-tools/36.0.0/d8"), "--min-api", "30",
            "--lib", str(Path(sdk) / "platforms/android-36/android.jar"), "--output", str(output),
            str(output / "com/lelloman/paravoidremote/Entry.class")], check=True)
        result[variant] = (output / "classes.dex").read_bytes()
        assert result[variant].startswith(b"dex\n") and len(result[variant]) < 128 * 1024
    assert result["a"] != result["b"]
    return result


if __name__ == "__main__":
    import os
    import tempfile
    with tempfile.TemporaryDirectory(prefix="paravoid-dex-build-") as directory:
        payloads = compile_payloads(os.environ["ANDROID_HOME"], directory)
        print({name: len(data) for name, data in payloads.items()})
