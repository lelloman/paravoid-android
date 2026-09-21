#!/usr/bin/env python3
"""Experimental APK signing-block personalization. Never edits the input APK.

The record is NOT authenticated by the APK signature and is NOT confidential.
Output requires fresh whole-file checksums and ordinary (non-v4) installation.
"""
import argparse
import json
from pathlib import Path
import struct

MAGIC = b"APK Sig Block 42"
RECORD_ID = 0x50564131  # Fixture-only ID, not a registered Paravoid format.
PADDING_ID = 0x42726577
MAX_RECORD = 4096
MAX_BLOCK = 16 * 1024 * 1024


def inspect(apk):
    start = max(0, len(apk) - 65557)
    candidates = [p for p in range(start, len(apk) - 21)
                  if apk[p:p + 4] == b"PK\x05\x06"
                  and p + 22 + struct.unpack_from("<H", apk, p + 20)[0] == len(apk)]
    if len(candidates) != 1:
        raise ValueError("Expected one terminal ZIP end record")
    end = candidates[0]
    disk, directory_disk, disk_count, count, size, directory = struct.unpack_from("<HHHHII", apk, end + 4)
    if disk or directory_disk or disk_count != count or count == 65535 or directory + size != end:
        raise ValueError("Unsupported ZIP layout (including ZIP64/multi-disk)")
    if directory < 32 or apk[directory - 16:directory] != MAGIC:
        raise ValueError("APK signing block missing")
    length = struct.unpack_from("<Q", apk, directory - 24)[0]
    block_start = directory - length - 8
    if length < 24 or length > MAX_BLOCK or block_start < 0:
        raise ValueError("Invalid signing block size")
    if struct.unpack_from("<Q", apk, block_start)[0] != length:
        raise ValueError("Signing block sizes disagree")
    cursor = block_start + 8
    pairs = {}
    while cursor < directory - 24:
        if cursor + 8 > directory - 24:
            raise ValueError("Truncated entry")
        pair_length = struct.unpack_from("<Q", apk, cursor)[0]
        cursor += 8
        if pair_length < 4 or cursor + pair_length > directory - 24:
            raise ValueError("Invalid entry size")
        entry_id = struct.unpack_from("<I", apk, cursor)[0]
        if entry_id in pairs:
            raise ValueError("Duplicate entry ID")
        pairs[entry_id] = apk[cursor + 4:cursor + pair_length]
        cursor += pair_length
    return block_start, directory, end, pairs


def record_bytes(record):
    if set(record) != {"version", "applicationId", "keyId", "key"} or record["version"] != 1:
        raise ValueError("Invalid fixture provisioning fields")
    for field in ("applicationId", "keyId", "key"):
        value = record[field]
        if not isinstance(value, str) or not value or not value.isascii() or any(ord(c) < 33 for c in value):
            raise ValueError("Invalid provisioning field")
    encoded = json.dumps(record, sort_keys=True, separators=(",", ":")).encode()
    if len(encoded) > MAX_RECORD:
        raise ValueError("Provisioning record too large")
    return encoded


def personalize(apk, record):
    block_start, directory, end, pairs = inspect(apk)
    if RECORD_ID in pairs:
        raise ValueError("Already provisioned; personalize the original signed APK")
    if not ({0x7109871A, 0xF05368C0} & pairs.keys()):
        raise ValueError("Expected a v2 or v3 signature block")
    pairs.pop(PADDING_ID, None)
    pairs[RECORD_ID] = record_bytes(record)
    body = b"".join(struct.pack("<QI", 4 + len(value), key) + value for key, value in pairs.items())
    # Keep the directory shift a multiple of 4096, preserving existing alignment.
    target = directory - block_start
    while target - (32 + len(body)) < 12:
        target += 4096
    padding = target - 32 - len(body) - 12
    body += struct.pack("<QI", 4 + padding, PADDING_ID) + bytes(padding)
    length = 24 + len(body)
    block = struct.pack("<Q", length) + body + struct.pack("<Q", length) + MAGIC
    new_directory = block_start + len(block)
    tail = bytearray(apk[directory:])
    struct.pack_into("<I", tail, end - directory + 16, new_directory)
    return apk[:block_start] + block + tail


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--record", required=True, type=Path, help="Private JSON file; do not put keys in argv")
    args = parser.parse_args()
    result = personalize(args.input.read_bytes(), json.loads(args.record.read_text()))
    with args.output.open("xb") as output:
        output.write(result)
    print("Personalized fixture APK written; verify with apksigner before installation.")
