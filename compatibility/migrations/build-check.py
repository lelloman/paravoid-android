#!/usr/bin/env python3
"""Build two real code versions and one immutable, dual-bundle fixture shell."""
from pathlib import Path
import hashlib
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'build/experiment'
ASSETS = ROOT / 'build/staged-assets/migration'


def build(version, *tasks):
    subprocess.run([str(ROOT.parent.parent / 'gradlew'), '-p', str(ROOT),
                    f'-PdatabaseVersion={version}', *tasks, '--console=plain'], check=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for version in (1, 2):
        build(version, 'assembleNormalDebug', 'assembleParavoidAndroidDebug',
              'lintNormalDebug', 'lintParavoidAndroidDebug')
        normal = ROOT / 'build/outputs/apk/normal/debug/migration-compatibility-normal-debug.apk'
        shell = ROOT / 'build/outputs/apk/paravoidAndroid/debug/migration-compatibility-paravoidAndroid-debug.apk'
        shutil.copyfile(normal, OUT / f'normal-v{version}.apk')
        with zipfile.ZipFile(shell) as apk:
            # Generated build artifacts, not a mutation of the production loader.
            (ASSETS / f'v{version}.zip').write_bytes(apk.read('assets/paravoid/module.zip'))
    assert (ASSETS / 'v1.zip').read_bytes() != (ASSETS / 'v2.zip').read_bytes()
    build(2, 'assembleParavoidAndroidDebug')
    shell = ROOT / 'build/outputs/apk/paravoidAndroid/debug/migration-compatibility-paravoidAndroid-debug.apk'
    shutil.copyfile(shell, OUT / 'shell.apk')
    with zipfile.ZipFile(shell) as apk:
        for version in (1, 2):
            assert apk.read(f'assets/migration/v{version}.zip') == (ASSETS / f'v{version}.zip').read_bytes()
    for name in ('normal-v1.apk', 'normal-v2.apk', 'shell.apk'):
        print(name, hashlib.sha256((OUT / name).read_bytes()).hexdigest(), flush=True)


if __name__ == '__main__':
    main()
