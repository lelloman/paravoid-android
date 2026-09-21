"""Bounded stored-ZIP inventory experiment; NOT a complete/frozen VPK format."""
import hashlib
import io
import re
import stat
import zipfile

from signed_profile import sign, verify

MAX_ENTRY = 128 * 1024
MAX_TOTAL = 512 * 1024
ROLES = {"code": "code/", "resources": "resources/", "asset": "assets/",
         "java-resource": "java/", "native": "native/"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe(path):
    return (len(path) <= 96 and re.fullmatch(r"[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*", path)
            and all(part not in ("", ".", "..") for part in path.split("/")))


def stored_zip(entries):
    """Also used to construct adversarial vectors; deliberately permits duplicates."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as archive:
        for path, data in entries:
            info = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, data)
    return out.getvalue()


def make_manifest(identity, inventory, key):
    return sign("manifest", dict(identity, format="stored-inventory-1",
        inventorySize=len(inventory), inventorySha256=sha(inventory)), key)


def pack(identity, components, key):
    if not 1 <= len(components) <= 16:
        raise ValueError("Component count")
    rows = []
    total = 0
    for path, (role, data) in sorted(components.items()):
        if not safe(path) or role not in ROLES or not path.startswith(ROLES[role]):
            raise ValueError("Component path/role")
        if not 0 < len(data) <= MAX_ENTRY:
            raise ValueError("Component size")
        total += len(data)
        rows.append(f"{role}|{path}|{len(data)}|{sha(data)}\n")
    inventory = "".join(rows).encode("ascii")
    if total > MAX_TOTAL or len(inventory) > 4096:
        raise ValueError("Inventory size")
    return stored_zip([("manifest.sig", make_manifest(identity, inventory, key)),
                       ("inventory.txt", inventory)] + [(p, v[1]) for p, v in sorted(components.items())])


def verify_archive(blob, identity, root):
    """Independent host verifier using zipfile; Android checks ZIP structure itself."""
    if len(blob) > 1024 * 1024:
        raise ValueError("Archive bound")
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        entries = archive.infolist()
        paths = [entry.filename for entry in entries]
        if not 3 <= len(entries) <= 18 or len(set(paths)) != len(paths):
            raise ValueError("Entry count/duplicates")
        for entry in entries:
            if (not safe(entry.filename) or entry.compress_type != zipfile.ZIP_STORED or entry.flag_bits
                    or entry.extra or entry.comment or entry.create_system != 3
                    or entry.external_attr != (stat.S_IFREG | 0o644) << 16
                    or not 0 < entry.file_size <= MAX_ENTRY):
                raise ValueError("Unsupported entry")
        if sum(e.file_size for e in entries) > MAX_TOTAL + 8192:
            raise ValueError("Aggregate bound")
        manifest = verify(archive.read("manifest.sig"), "manifest", root)
        if any(manifest.get(k) != str(v) for k, v in identity.items()) or manifest["format"] != "stored-inventory-1":
            raise ValueError("Manifest scope/format")
        inventory = archive.read("inventory.txt")
        if len(inventory) > 4096 or str(len(inventory)) != manifest["inventorySize"] or sha(inventory) != manifest["inventorySha256"]:
            raise ValueError("Inventory integrity")
        rows = inventory.decode("ascii").split("\n")
        if rows[-1] or not 1 <= len(rows) - 1 <= 16:
            raise ValueError("Inventory count")
        expected = {"manifest.sig", "inventory.txt"}
        previous, total = "", 0
        for row in rows[:-1]:
            role, path, size, digest = row.split("|")
            if (role not in ROLES or not safe(path) or not path.startswith(ROLES[role]) or path <= previous
                    or not re.fullmatch(r"[1-9][0-9]{0,5}", size) or int(size) > MAX_ENTRY
                    or not re.fullmatch(r"[0-9a-f]{64}", digest)):
                raise ValueError("Inventory entry")
            previous = path
            data = archive.read(path)
            if len(data) != int(size) or sha(data) != digest:
                raise ValueError("Component integrity")
            total += len(data)
            expected.add(path)
        if set(paths) != expected or total > MAX_TOTAL:
            raise ValueError("Inventory coverage")
        return len(rows) - 1
