"""Fail-closed, read-only gates for optional physical fixture acceptance."""
import re

PACKAGES = ('com.lelloman.paravoidcompat.complete',
            'com.lelloman.paravoidcompat.complete.paravoid')
CONSENT = ','.join(PACKAGES)


def validate_apk_badging(badging, expected_package):
    match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not match or match.group(1) != expected_package:
        raise ValueError('APK package does not match dedicated fixture package')
    if re.search(r'^application-debuggable', badging, re.MULTILINE):
        raise ValueError('Physical acceptance requires a non-debuggable release APK')


def preflight(adb, serial, consent):
    """adb must return stdout/stderr and raise on command failure. No writes here."""
    if not serial or serial.startswith('-') or re.search(r'\s', serial):
        raise ValueError('Explicit valid adb serial required')
    if serial.startswith('emulator-'):
        raise ValueError('Physical ARM64 acceptance cannot use an emulator serial')
    if consent != CONSENT:
        raise ValueError('Explicit --allow-fixture-install ' + CONSENT + ' required')
    if adb('get-state').strip() != 'device':
        raise ValueError('Device is not ready')
    for key in ('ro.kernel.qemu', 'ro.boot.qemu'):
        if adb('shell', 'getprop', key).strip() not in ('', '0'):
            raise ValueError('Emulator detected')
    if adb('shell', 'getprop', 'ro.product.cpu.abi').strip() != 'arm64-v8a':
        raise ValueError('Primary ABI must be arm64-v8a (translated ABI lists are insufficient)')
    if adb('shell', 'uname', '-m').strip() not in ('aarch64', 'arm64'):
        raise ValueError('ARM64 kernel required')
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    if sdk < 30:
        raise ValueError('Release acceptance fixture requires API 30 or newer')
    users = adb('shell', 'pm', 'list', 'users')
    if re.findall(r'UserInfo\{(\d+):', users) != ['0']:
        raise ValueError('Use a dedicated single-user device; other users/profiles are not supported')
    # -u includes packages uninstalled with retained data. Refuse even if the
    # caller consented: this option authorizes NEW dedicated fixtures only.
    packages = adb('shell', 'pm', 'list', 'packages', '-u')
    if not packages.strip() or any(not line.startswith('package:') for line in packages.splitlines()):
        raise ValueError('Cannot safely enumerate installed/retained packages')
    installed = {line.removeprefix('package:').strip() for line in packages.splitlines()}
    if installed.intersection(PACKAGES):
        raise ValueError('Fixture package/data already exists; refusing replacement or removal')
    mappings = adb('reverse', '--list')
    if any('tcp:18765' in line.split()[1:2] for line in mappings.splitlines()):
        raise ValueError('Device tcp:18765 already has a reverse mapping')
    return sdk
