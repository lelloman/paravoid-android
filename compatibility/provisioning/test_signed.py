import unittest

from cryptography.exceptions import InvalidSignature
from signed_profile import new_key, sign, verify


class SignedProfileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = new_key()
        cls.other = new_key()

    def setUp(self):
        self.fields = dict(applicationId="example.app", audience="fixture-store", keyId="a",
                           key="test_key", issued=1, expires=2)
        self.envelope = sign("grant", self.fields, self.key)

    def test_round_trip(self):
        self.assertEqual(verify(self.envelope, "grant", self.key.public_key()),
                         {name: str(value) for name, value in self.fields.items()})

    def test_untrusted_key_and_modified_signature(self):
        with self.assertRaises(InvalidSignature):
            verify(self.envelope, "grant", self.other.public_key())
        lines = self.envelope.split(b"\n")
        lines[3] = (b"A" if lines[3][:1] != b"A" else b"B") + lines[3][1:]
        with self.assertRaises(InvalidSignature):
            verify(b"\n".join(lines), "grant", self.key.public_key())

    def test_domain_separation_and_framing(self):
        with self.assertRaises(ValueError):
            verify(self.envelope, "head", self.key.public_key())
        for value in (self.envelope + b"\n", self.envelope.replace(b"\n", b"\r\n"), b"x" * 4097):
            with self.assertRaises(ValueError):
                verify(value, "grant", self.key.public_key())

    def test_unknown_fields_and_ambiguous_values(self):
        for fields in (dict(self.fields, extra="unknown"), dict(self.fields, key="a\nkeyId=b")):
            with self.assertRaises(ValueError):
                sign("grant", fields, self.key)


if __name__ == "__main__":
    unittest.main()
