#!/usr/bin/env python3
"""Check the payload bytes and R8 mapping emitted by check.sh."""

import json
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "build/outputs/paravoid/paravoidAndroidDebug"
BASELINE = ROOT / "build/minification-baseline.json"
PREFIX = "Lcom/lelloman/paravoidcompat/minification/"


def payload():
    module = OUTPUT / "module.zip"
    with ZipFile(module) as archive:
        dex = archive.read("classes.dex")
    return module.stat().st_size, dex


def defined_classes(dex):
    assert dex[:4] == b"dex\n", "payload is not a DEX file"
    read_u32 = lambda offset: struct.unpack_from("<I", dex, offset)[0]
    strings_size, strings_offset = read_u32(56), read_u32(60)
    types_size, types_offset = read_u32(64), read_u32(68)
    classes_size, classes_offset = read_u32(96), read_u32(100)
    strings = []
    for index in range(strings_size):
        offset = read_u32(strings_offset + index * 4)
        while dex[offset] & 0x80:  # skip ULEB128 UTF-16 length
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(0, offset)].decode("ascii", errors="replace"))
    types = [strings[read_u32(types_offset + index * 4)] for index in range(types_size)]
    return {types[read_u32(classes_offset + index * 32)] for index in range(classes_size)}


def main(mode):
    module_size, dex = payload()
    classes = defined_classes(dex)
    if mode == "baseline":
        assert PREFIX + "UnusedProbe;" in classes, "unshrunk payload lacks the dead-code probe"
        BASELINE.write_text(json.dumps({"module": module_size, "dex": len(dex)}))
        print(f"Unshrunk payload: DEX {len(dex)} bytes, module {module_size} bytes")
        return
    if mode != "compare":
        raise SystemExit("Usage: verify.py baseline|compare")

    before = json.loads(BASELINE.read_text())
    mapping = (OUTPUT / "payload-mapping.txt").read_text()
    for name in ("MainActivity", "ProbeApplication", "ProbeReceiver", "ProbeProvider",
                 "ReflectiveProbe", "Greeting", "GreetingProvider"):
        assert PREFIX + name + ";" in classes, f"required class missing: {name}"
    assert PREFIX + "UnusedProbe;" not in classes, "unused class survived R8"
    renamed = re.search(r"^com\.lelloman\.paravoidcompat\.minification\.RenameProbe -> ([^:]+):$",
                        mapping, re.MULTILINE)
    assert renamed and renamed.group(1) != "com.lelloman.paravoidcompat.minification.RenameProbe", \
        "live class was not obfuscated"
    assert len(dex) < before["dex"], "R8 did not shrink the DEX"
    assert module_size < before["module"], "R8 did not shrink module.zip"
    print(f"Minified payload: DEX {len(dex)} bytes, module {module_size} bytes")
    print(f"DEX reduction: {(1 - len(dex) / before['dex']) * 100:.1f}%")
    print(f"Mapping: {OUTPUT / 'payload-mapping.txt'}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) == 2 else "")
