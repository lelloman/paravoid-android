#!/usr/bin/env python3
"""Bounded AAPT2 linking proof, not the automatic downstream packaging pipeline."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
PACKAGE = 'com.lelloman.paravoidcompat.resourcesplit'


def read_ids(path):
    return {name: int(value, 16) for name, value in
            (line.split(' = ') for line in path.read_text().splitlines())}


def validate_ids(a, b, pinned):
    assert a and b and pinned
    assert all(value >> 24 == 0x7f for table in (a, b, pinned) for value in table.values())
    assert all(a[key] == b[key] for key in a.keys() & b.keys()), 'Existing IDs changed'
    assert all(a[key] == value == b[key] for key, value in pinned.items()), 'Pinned ID changed'
    # AAPT2 emits the removed symbol as a reservation, not a resource value.
    assert b[PACKAGE + ':string/removed'] == a[PACKAGE + ':string/removed']
    assert b[PACKAGE + ':string/a_added'] not in set(a.values()), 'Retired ID reused'


def build(sdk, output):
    output.mkdir(parents=True, exist_ok=True)
    aapt = sdk / 'build-tools/35.0.0/aapt2'
    framework = sdk / 'platforms/android-36/android.jar'
    def run(*args):
        return subprocess.check_output([str(aapt), *map(str, args)], text=True)
    def compile_pack(name):
        archive = output / (name + '.zip')
        run('compile', '--dir', ROOT / 'packs' / name / 'res', '-o', archive)
        return archive
    compiled = {name: compile_pack(name) for name in ('pinned', 'common', 'library', 'A', 'B')}
    library = output / 'library.apk'
    run('link', '--static-lib', '-I', framework, '--manifest', ROOT / 'packs/library/AndroidManifest.xml',
        '-o', library, compiled['library'])
    hashes = {}
    for version in ('A', 'B'):
        args = ['link', '-I', framework, '--manifest', ROOT / 'packs/AndroidManifest.xml',
                '--package-id', '0x7f', '--emit-ids', output / f'{version}.ids',
                '-o', output / f'{version}.apk', compiled['pinned'], compiled['common'],
                compiled[version], '-R', library, '--auto-add-overlay', '--no-static-lib-packages']
        if version == 'A':
            args += ['--java', output / 'java', '--custom-package', PACKAGE + '.linked',
                     '--extra-packages', PACKAGE + '.library']
        else:
            args += ['--stable-ids', output / 'A.ids']
        run(*args)
        hashes[version] = hashlib.sha256((output / f'{version}.apk').read_bytes()).hexdigest()
    run('link', '-I', framework, '--manifest', ROOT / 'packs/AndroidManifest.xml',
        '--package-id', '0x7f', '--stable-ids', output / 'A.ids', '--emit-ids', output / 'pinned.ids',
        '-o', output / 'pinned.apk', compiled['pinned'])
    a, b, pinned = (read_ids(output / f'{name}.ids') for name in ('A', 'B', 'pinned'))
    validate_ids(a, b, {key: value for key, value in pinned.items() if '/shell_' in key or '/ShellTheme' in key})
    dumps = {name: run('dump', 'resources', output / f'{name}.apk') for name in ('A', 'B', 'pinned')}
    assert 'string/title' not in dumps['pinned'], 'Movable value leaked into pinned table'
    assert 'string/removed' not in dumps['B'], 'Removed value survived linking'
    assert 'string/a_added' in dumps['B'] and 'string/a_added' not in dumps['A']
    for name, dump in dumps.items():
        (output / f'{name}.dump.txt').write_text(dump)
        with zipfile.ZipFile(output / f'{name}.apk') as archive:
            assert 'resources.arsc' in archive.namelist()
            assert not any(path.endswith('.dex') for path in archive.namelist())
    (output / 'hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
    trusted = output / 'runtime-java/com/lelloman/paravoidandroid/runtime/SplitTrusted.java'
    trusted.parent.mkdir(parents=True, exist_ok=True)
    trusted.write_text('package com.lelloman.paravoidandroid.runtime;\n'
                       'final class SplitTrusted {\n' + ''.join(
                           f' static final String {key} = "{value}";\n' for key, value in hashes.items()) + '}\n')
    print('PASS linking: 0x7f app + static library, stable IDs, tombstone, pinned-only table')


if __name__ == '__main__':
    build(Path(sys.argv[1]), Path(sys.argv[2]))
