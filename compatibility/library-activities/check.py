#!/usr/bin/env python3
"""Inspect actual AAR contributions, generated manifest, and host/payload DEX."""
from pathlib import Path
import io
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
ANDROID = '{http://schemas.android.com/apk/res/android}'
ACTIVITIES = (
    'net.openid.appauth.AuthorizationManagementActivity',
    'com.lelloman.androidoscopy.ui.DashboardActivity',
    'com.lelloman.androidoscopy.ui.SessionActivity',
)


def dex(archive):
    return b''.join(archive.read(name) for name in archive.namelist()
                    if name.startswith('classes') and name.endswith('.dex'))


def check():
    manifest = ROOT / 'build/intermediates/merged_manifest/paravoidAndroidDebug/prepareParavoidAndroidDebugParavoidManifest/AndroidManifest.xml'
    activities = {node.get(ANDROID + 'name'): node for node in ET.parse(manifest).iter('activity')}
    assert 'net.openid.appauth.RedirectUriReceiverActivity' not in activities
    for name in ACTIVITIES:
        assert name in activities, name
        assert activities[name].get(ANDROID + 'exported') == 'false', name
        assert activities[name].get(ANDROID + 'theme'), name
    assert activities[ACTIVITIES[0]].get(ANDROID + 'launchMode') == 'singleTask'
    for mode in ('normal', 'paravoidAndroid'):
        apk = ROOT / f'build/outputs/apk/{mode}/debug/library-activities-{mode}-debug.apk'
        with zipfile.ZipFile(apk) as archive:
            host = dex(archive)
            if mode == 'paravoidAndroid':
                with zipfile.ZipFile(io.BytesIO(archive.read('assets/paravoid/module.zip'))) as payload:
                    implementation = dex(payload)
            else:
                implementation = host
            for name in ACTIVITIES:
                descriptor = ('L' + name.replace('.', '/') + ';').encode()
                assert descriptor in implementation, (mode, name)
                if mode == 'paravoidAndroid': assert descriptor not in host, name
    print('PASS: real AppAuth/Androidoscopy declarations in shell, implementations only in payload; normal control intact')


if __name__ == '__main__': check()
