#!/usr/bin/env python3
"""Build real AGP splits, pin their hashes in fixture code, assemble a debug-only test shell."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
BUILD = ROOT / 'build'
APP = 'com.lelloman.paravoidcompat.automaticresources'


def run(*args):
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)


def gradle(*tasks, baseline=False):
    run(REPO / 'gradlew', '-p', ROOT, *tasks, '--console=plain', '--max-workers=2',
        *([f'-Pbaseline={BUILD / "baseline"}'] if baseline else []))


def outputs(version):
    return BUILD / f'outputs/paravoid/paravoidAndroid{version}Debug'


def apk(mode, version='A'):
    directory = BUILD / f'outputs/apk/{mode}{version}/debug'
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    assert len(metadata['elements']) == 1, 'Fixture requires one standalone APK'
    return directory / metadata['elements'][0]['outputFile']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strip_installed_resources(original, resource_apk, destination):
    """Fixture assembly only: no resource selection/relinking lives in this helper."""
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(resource_apk) as resources:
        assert source.read('AndroidManifest.xml') == resources.read('AndroidManifest.xml')
        entries = {}
        for info in source.infolist():
            name = info.filename
            if info.is_dir() or name == 'resources.arsc' or name.startswith('res/'):
                continue
            if name.startswith('assets/') and name != 'assets/paravoid/module.zip':
                continue
            if name.startswith('META-INF/') and (name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) or name == 'META-INF/MANIFEST.MF'):
                continue
            entries[name] = (source.read(name), info.compress_type)
        for info in resources.infolist():
            if not info.is_dir():
                entries[info.filename] = (resources.read(info.filename), info.compress_type)
    with zipfile.ZipFile(destination, 'w') as output:
        for name, (data, method) in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = method
            output.writestr(info, data)


def inspect(sdk):
    aapt = sdk / 'build-tools/35.0.0/aapt2'
    shell = BUILD / 'validated/shell.apk'
    dump = subprocess.check_output([str(aapt), 'dump', 'resources', str(shell)], text=True)
    assert 'string/shell_label' in dump and 'style/ShellTheme' in dump
    assert 'string/title' not in dump and 'layout/panel' not in dump and 'string/library_message' not in dump
    with zipfile.ZipFile(shell) as archive:
        assert 'assets/paravoid/module.zip' in archive.namelist()
        assert 'classes.dex' in archive.namelist()
        assert 'assets/content.txt' not in archive.namelist()
        assert not any(name.startswith('res/') for name in archive.namelist())
    for version in ('A', 'B'):
        path = BUILD / f'validated/{version}.apk'
        dump = subprocess.check_output([str(aapt), 'dump', 'resources', str(path)], text=True)
        assert 'string/title' in dump and 'layout/panel' in dump and 'attr/probeLabel' in dump
        assert ('string/removed' in dump) == (version == 'A')
        assert ('string/a_added' in dump) == (version == 'B')
        with zipfile.ZipFile(path) as archive:
            assert archive.read('assets/content.txt').decode().strip() == 'Asset ' + version
    print('PASS artifacts: actual generated pinned-only shell, complete A/B resources/assets, no installed fallback', flush=True)


def build():
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools/35.0.0'
    BUILD.mkdir(exist_ok=True)
    key = BUILD / 'fixture.jks'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'fixture', '-dname', 'CN=Paravoid resource fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
    trusted = BUILD / 'trusted.properties'
    trusted.write_text('A=' + '0' * 64 + '\nB=' + '0' * 64 + '\n')
    gradle('exportParavoidAndroidADebugParavoidResourceLedger', 'analyzeParavoidAndroidADebugParavoidResources',
           'splitParavoidAndroidADebugParavoidResources')
    for version in ('A', 'B'):
        directory = BUILD / f'baseline/paravoidAndroid{version}Debug'
        directory.mkdir(parents=True, exist_ok=True)
        for name in ('resource-ledger.json', 'resource-boundary.json'):
            shutil.copyfile(outputs('A') / 'baseline-candidate' / name, directory / name)
    gradle('splitParavoidAndroidBDebugParavoidResources', baseline=True)
    hashes = {v: digest(outputs(v) / 'resources/payload-resources.apk') for v in ('A', 'B')}
    trusted.write_text(''.join(f'{v}={hashes[v]}\n' for v in ('A', 'B')))
    # Hash constants change DEX, not resource/asset bytes. Prove that rather than assuming it.
    gradle('assembleNormalADebug', 'splitParavoidAndroidADebugParavoidResources',
           'splitParavoidAndroidBDebugParavoidResources', 'lintNormalADebug', 'lintParavoidAndroidADebug', baseline=True)
    validated = BUILD / 'validated'
    validated.mkdir(exist_ok=True)
    for version in ('A', 'B'):
        payload = outputs(version) / 'resources/payload-resources.apk'
        assert digest(payload) == hashes[version], 'Trusted-resource build became self-referential'
        shutil.copyfile(payload, validated / f'{version}.apk')
    assert (outputs('A') / 'resources/shell-resources.apk').read_bytes() == (outputs('B') / 'resources/shell-resources.apk').read_bytes()
    shutil.copyfile(apk('normal'), validated / 'normal.apk')
    strip_installed_resources(apk('paravoidAndroid'), outputs('A') / 'resources/shell-resources.apk', validated / 'shell-unaligned.apk')
    run(tools / 'zipalign', '-f', '4', validated / 'shell-unaligned.apk', validated / 'shell-aligned.apk')
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--out', validated / 'shell.apk', validated / 'shell-aligned.apk')
    run(tools / 'apksigner', 'verify', validated / 'shell.apk')
    run(tools / 'zipalign', '-c', '4', validated / 'shell.apk')
    (validated / 'hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
    inspect(sdk)


if __name__ == '__main__':
    build()
