"""Shared adversarial archive vectors; outer discovery signs every supplied blob."""
import io
import stat
import struct
import warnings
import zipfile

from archive_profile import make_manifest, pack, sha, stored_zip, MAX_ENTRY
from signed_profile import sign


def vectors(application, contract, release_key, wrong_key):
    components = {"code/classes.dex": ("code", b"opaque DEX fixture; never load"),
        "resources/table.bin": ("resources", b"opaque resource fixture"),
        "assets/message.txt": ("asset", b"asset bytes"),
        "java/config.txt": ("java-resource", b"java resource bytes"),
        "native/x86_64/library.so": ("native", b"opaque native fixture; never load")}
    cases = [("valid five-role archive", None), ("untrusted release signer", "signature"),
        ("damaged manifest signature", "signature"), ("wrong manifest app", "manifest-scope"),
        ("wrong manifest contract", "manifest-scope"), ("wrong manifest release", "manifest-scope"),
        ("wrong manifest version", "manifest-scope"), ("unknown format", "manifest-format"),
        ("modified inventory", "inventory-integrity"), ("modified component", "component-integrity"),
        ("missing component", "component-missing"), ("unexpected component", "component-unexpected"),
        ("duplicate ZIP path", "archive-duplicate"), ("parent traversal", "archive-path"),
        ("absolute path", "archive-path"), ("backslash path", "archive-path"),
        ("symlink", "archive-feature"), ("compressed member", "archive-feature"),
        ("oversized component", "archive-bounds"), ("too many entries", "archive-count"),
        ("aggregate bound", "archive-bounds"), ("unknown role", "inventory-entry"),
        ("role path mismatch", "inventory-entry"), ("duplicate inventory path", "inventory-entry"),
        ("local central name mismatch", "archive-layout"), ("bad CRC", "archive-crc"),
        ("trailing bytes", "archive-layout"), ("truncated archive", "archive-layout"),
        ("valid recovery archive", None)]
    for index, (label, reason) in enumerate(cases, 100):
        identity = dict(applicationId=application, contract=contract, releaseId=f"archive-{index}", payloadVersion=index)
        blob = pack(identity, components, release_key)
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            entries = [(i.filename, z.read(i)) for i in z.infolist()]
        inventory = entries[1][1]
        manifest = dict(identity, format="stored-inventory-1", inventorySize=len(inventory), inventorySha256=sha(inventory))
        if label == "untrusted release signer":
            entries[0] = ("manifest.sig", sign("manifest", manifest, wrong_key))
        elif label == "damaged manifest signature":
            lines = entries[0][1].split(b"\n")
            lines[3] = (b"A" if lines[3][:1] != b"A" else b"B") + lines[3][1:]
            entries[0] = ("manifest.sig", b"\n".join(lines))
        elif label.startswith("wrong manifest") or label == "unknown format":
            field = {"wrong manifest app": "applicationId", "wrong manifest contract": "contract",
                "wrong manifest release": "releaseId", "wrong manifest version": "payloadVersion",
                "unknown format": "format"}[label]
            manifest[field] = "999" if field == "payloadVersion" else "wrong"
            entries[0] = ("manifest.sig", sign("manifest", manifest, release_key))
        elif label == "modified inventory":
            entries[1] = ("inventory.txt", inventory.replace(b"asset|", b"wrong|", 1))
        elif label == "modified component":
            entries[-1] = (entries[-1][0], b"altered bytes")
        elif label == "missing component":
            entries.pop()
        elif label == "unexpected component":
            entries.append(("assets/extra.txt", b"extra"))
        elif label == "duplicate ZIP path":
            entries.append(entries[-1])
        elif label in ("parent traversal", "absolute path", "backslash path"):
            entries.append(({"parent traversal": "../escape", "absolute path": "/escape", "backslash path": "assets\\escape"}[label], b"escape"))
        elif label == "oversized component":
            entries[-1] = (entries[-1][0], b"x" * (MAX_ENTRY + 1))
        elif label == "too many entries":
            entries += [(f"assets/extra{i}", b"x") for i in range(12)]
        elif label == "aggregate bound":
            entries = entries[:2] + [(f"assets/large{i}", b"x" * MAX_ENTRY) for i in range(5)]
        elif label in ("unknown role", "role path mismatch", "duplicate inventory path"):
            if label == "unknown role":
                inventory = inventory.replace(b"asset|", b"unknown|", 1)
            elif label == "role path mismatch":
                inventory = inventory.replace(b"asset|", b"native|", 1)
            else:
                inventory += inventory.splitlines(keepends=True)[0]
            entries[1] = ("inventory.txt", inventory)
            entries[0] = ("manifest.sig", make_manifest(identity, inventory, release_key))
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            blob = stored_zip(entries)
        data = bytearray(blob)
        directory = struct.unpack_from("<I", data, len(data) - 6)[0]
        if label == "symlink":
            struct.pack_into("<I", data, directory + 38, (stat.S_IFLNK | 0o777) << 16)
        elif label == "compressed member":
            # Unsupported-method rejection before attempting decompression.
            struct.pack_into("<H", data, 8, 8)
            struct.pack_into("<H", data, directory + 10, 8)
        elif label == "local central name mismatch":
            data[30] ^= 1
        elif label == "bad CRC":
            local_name_size = struct.unpack_from("<H", data, 26)[0]
            data[30 + local_name_size] ^= 1
        elif label == "trailing bytes":
            data += b"trailing"
        elif label == "truncated archive":
            data = data[:-1]
        yield label, identity, bytes(data), reason
