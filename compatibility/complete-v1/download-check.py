#!/usr/bin/env python3
"""Fixed installed shell, real signed HTTP VPK, app-sandbox leases and cold selection."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def catalog(reference, fixture, device, variant, revision):
    archive = fixture.ARTIFACTS / (variant + '.vpk')
    with zipfile.ZipFile(archive) as vpk:
        manifest = vpk.read('release.json')
    release = json.loads(base64.b64decode(json.loads(manifest)['body']))
    sdk = int(device.run('shell', 'getprop', 'ro.build.version.sdk'))
    abis = device.run('shell', 'getprop', 'ro.product.cpu.abilist64').strip().split(',')
    now = int(time.time())
    body = dict(version=1, applicationId=fixture.SHELL, shellContractId=release['shellContractId'],
                channel='stable', sdk=sdk, abis=abis, runtimeAbi=1, formatVersion=1,
                headRevision=revision, issuedAt=now, expiresAt=now + 3600, status='available',
                release=dict(releaseId=release['releaseId'], payloadVersion=release['payloadVersion'],
                             manifestSha256=fixture.hashlib.sha256(manifest).hexdigest(),
                             archiveSha256=fixture.hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest(),
                             archiveSize=archive.stat().st_size))
    encoded = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
    key = serialization.load_der_private_key((ROOT / 'build/keys/head.der').read_bytes(), None)
    envelope = json.dumps(dict(keyId='head', body=base64.b64encode(encoded).decode(),
                               signature=base64.b64encode(key.sign(b'paravoid/v1/head\n' + encoded,
                                   padding.PKCS1v15(), hashes.SHA256())).decode()),
                          sort_keys=True, separators=(',', ':')).encode()
    result = reference.Catalog('public')
    result.add_archive(fixture.SHELL, release['releaseId'], archive)
    result.add_head(fixture.SHELL, dict(contract=release['shellContractId'], channel='stable',
                    sdk=str(sdk), abis=','.join(abis), runtime='1', format='1', protocol='1'), envelope)
    return result


def kill_process(device, package, suffix=''):
    assert not suffix, 'The fixture death receiver lives in the main process'
    device.run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    pid = device.run('shell', 'pidof', package + suffix).strip()
    assert pid.isdecimal(), (suffix, pid)
    device.run('shell', 'am', 'broadcast', '-a', 'probe.die', '-n',
               package + '/com.lelloman.paravoidcompat.complete.ProbeReceiver', check=False)
    device.await_(lambda: not device.run('shell', 'pidof', package + suffix, check=False).strip(), 'process death')


def run(device, fixture, reference):
    device.install('A')
    with zipfile.ZipFile(fixture.ARTIFACTS / 'A.apk') as apk:
        installed = json.loads(apk.read('assets/paravoid/shell-policy.json'))['contractId']
    server = reference.Server(('127.0.0.1', 0), catalog(reference, fixture, device, 'B', 3))
    head = next(iter(server.catalog.heads.values()))
    assert json.loads(base64.b64decode(json.loads(head.body)['body']))['shellContractId'] == installed
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    device.run('reverse', 'tcp:18765', 'tcp:' + str(server.server_address[1]))
    thread.start()
    try:
        device.launch()
        device.healthy(1)
        device.await_(lambda: device.state()['pending'] is not None, 'HTTP B pending')
        device.await_(lambda: device.markers().get('update', '').endswith('available=true'), 'app-owned update observer')
        assert device.state()['active']['version'] == 1 and device.state()['pending']['version'] == 3
        device.run('shell', 'am', 'startservice', '-n', fixture.SHELL + '/' + fixture.PACKAGE + '.ProbeService$Worker', '-a', 'hold')
        assert 'generation=A' in device.run('shell', 'content', 'query', '--uri', 'content://' + fixture.SHELL + '.worker')
        print('PASS genuine HTTP B verified and pending while installed shell runs A; worker joins A', flush=True)
        kill_process(device, fixture.SHELL)
        device.launch()
        assert device.state()['active']['version'] == 1 and device.state()['pending']['version'] == 3
        assert device.markers()['application'] == 'A'
        print('PASS main-process death does not release worker lease or activate B', flush=True)
        device.stop()  # Explicit fault injection kills both payload processes.
        device.launch()
        device.healthy(3)
        assert device.markers()['activity'].startswith('generation=B;')
        device.native_generation()
        assert device.state()['pending'] is None
        print('PASS final payload-process death permits cold B selection in unchanged installed A shell', flush=True)
    finally:
        server.shutdown()
        thread.join()
        server.server_close()
        device.run('reverse', '--remove', 'tcp:18765')
    device.stop()
    device.launch()
    device.healthy(3)
    assert device.markers()['application'] == 'B'
    print('PASS accepted B runs offline in fixed A shell after server shutdown', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    args = parser.parse_args()
    fixture = module('complete_device_fixture', ROOT / 'device-check.py')
    reference = module('complete_reference_server', ROOT.parent.parent / 'delivery/reference/server.py')
    device = fixture.Device(args.serial, args.avd)
    run(device, fixture, reference)
