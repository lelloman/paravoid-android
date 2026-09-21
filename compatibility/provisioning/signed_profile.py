"""Experimental exact-byte signing profile. NOT the production VPK wire format.

Python signs; Android JCA independently verifies. Private keys stay on the host.
"""
import base64
import re

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

FIELDS = {
    "grant": ("applicationId", "audience", "keyId", "key", "issued", "expires"),
    "head": ("applicationId", "contract", "channel", "revision", "releaseId",
             "payloadVersion", "issued", "expires", "size", "sha256"),
}
MAX_ENVELOPE = 4096


def new_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def public_der(key):
    return key.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)


def sign(kind, fields, key):
    if set(fields) != set(FIELDS[kind]):
        raise ValueError("Unexpected fields")
    values = {name: str(fields[name]) for name in FIELDS[kind]}
    if any(not re.fullmatch(r"[A-Za-z0-9._:/-]{1,256}", value) for value in values.values()):
        raise ValueError("Invalid field encoding")
    body = "".join(f"{name}={values[name]}\n" for name in FIELDS[kind]).encode("ascii")
    prefix = f"PARAVOID-PROBE-1\n{kind}\n".encode("ascii")
    signature = key.sign(prefix + body, padding.PKCS1v15(), hashes.SHA256())
    envelope = prefix + base64.b64encode(body) + b"\n" + base64.b64encode(signature) + b"\n"
    if len(envelope) > MAX_ENVELOPE:
        raise ValueError("Oversized envelope")
    return envelope


def verify(envelope, kind, public_key):
    if len(envelope) > MAX_ENVELOPE:
        raise ValueError("Oversized envelope")
    lines = envelope.decode("ascii").split("\n")
    if len(lines) != 5 or lines[:2] != ["PARAVOID-PROBE-1", kind] or lines[-1]:
        raise ValueError("Invalid envelope")
    body, signature = (base64.b64decode(line, validate=True) for line in lines[2:4])
    if [base64.b64encode(value).decode() for value in (body, signature)] != lines[2:4]:
        raise ValueError("Noncanonical base64")
    public_key.verify(signature, f"PARAVOID-PROBE-1\n{kind}\n".encode() + body,
                      padding.PKCS1v15(), hashes.SHA256())
    rows = body.decode("ascii").split("\n")
    if len(rows) != len(FIELDS[kind]) + 1 or rows[-1]:
        raise ValueError("Invalid field count")
    result = {}
    for name, row in zip(FIELDS[kind], rows):
        if not row.startswith(name + "=") or not re.fullmatch(r"[A-Za-z0-9._:/-]{1,256}", row[len(name) + 1:]):
            raise ValueError("Invalid field")
        result[name] = row[len(name) + 1:]
    return result
