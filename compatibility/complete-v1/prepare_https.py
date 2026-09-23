"""Ignored test-only TLS CA/server and APK signing material. No production credentials."""
import datetime
import ipaddress
from pathlib import Path
import subprocess
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
from prepare import prepare

prepare()
root = Path(__file__).resolve().parent
keys = root / 'build/keys'
resources = root / 'build/https-res/raw'
resources.mkdir(parents=True, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc)
ca_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Paravoid disposable acceptance CA')])
ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
      .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
      .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=30))
      .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
      .sign(ca_key, hashes.SHA256()))
(resources / 'paravoid_fixture_ca.pem').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
for name, trusted in [('server', True), ('untrusted', False)]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, '127.0.0.1')])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(ca_name if trusted else subject)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=30))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(ca_key if trusted else key, hashes.SHA256()))
    (keys / (name + '.pem')).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    secret = keys / (name + '-key.pem')
    secret.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    secret.chmod(0o600)
keystore = keys / 'acceptance.p12'
if not keystore.exists():
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
                    '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-alias', 'fixture',
                    '-keyalg', 'RSA', '-keysize', '3072', '-validity', '365',
                    '-dname', 'CN=Paravoid disposable acceptance fixture'], check=True)
    keystore.chmod(0o600)
print('Generated throwaway HTTPS fixture material; rebuild release APKs to pin this CA.')
