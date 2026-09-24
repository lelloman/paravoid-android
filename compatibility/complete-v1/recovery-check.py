#!/usr/bin/env python3
"""Crash recovery acceptance. Only the explicitly named disposable emulator is supported."""
import argparse
import json
import re
import shutil
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import importlib.util
import sys

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent.parent
sys.dont_write_bytecode=True

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec); sys.modules[name]=result; spec.loader.exec_module(result); return result

fixture=module('recovery_device_fixture',ROOT/'device-check.py')
download=module('recovery_download_fixture',ROOT/'download-check.py')
reference=module('recovery_reference_server',REPO/'delivery/reference/server.py')


def screen(device):
    device.run('shell','uiautomator','dump','/sdcard/paravoid-recovery.xml')
    return ET.fromstring(device.run('shell','cat','/sdcard/paravoid-recovery.xml'))


def tap(device,text):
    for node in screen(device).iter('node'):
        if node.get('text','').lower()==text.lower() and node.get('clickable')=='true':
            assert node.get('enabled')=='true',text
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            device.run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); return
    raise AssertionError('Missing button: '+text)


def text(device):
    return '\n'.join(n.get('text','') for n in screen(device).iter('node'))


def recovery(device):
    device.await_(lambda: bool(device.run('shell','pidof',fixture.SHELL+':paravoid_recovery',check=False).strip()),'recovery process')
    assert 'App recovery' in text(device)
    pid=device.run('shell','pidof',fixture.SHELL+':paravoid_recovery').strip()
    maps=device.run('shell','run-as',fixture.SHELL,'cat',f'/proc/{pid}/maps')
    assert '/generations/' not in maps,'Recovery mapped payload code'


def build(updater,minify=False):
    fixture.command(['python3',ROOT/'prepare.py'])
    fixture.ARTIFACTS.mkdir(parents=True,exist_ok=True)
    for variant,version,fault in [('crash',1,'activity-create'),('repair',2,'none')]:
        args=[REPO/'gradlew','-p',ROOT,'assembleParavoidAndroidDebug','--offline','--max-workers=2',
              '-PcrashRecovery=true',f'-PrecoveryUpdater={updater}',f'-PpayloadVersion={version}',f'-PstartupFault={fault}',f'-Pgeneration={variant}']
        if version==2: args.append('-Pbaseline')
        if minify: args.append('-PminifyPayload=true')
        output=fixture.command(args,cwd=REPO,timeout=600)
        (fixture.ARTIFACTS/f'recovery-{updater}-{variant}.log').write_text(output)
        source=ROOT/'build/outputs/paravoid/paravoidAndroidDebug'
        for extension,source_name in [('apk','shell.apk'),('vpk','payload.vpk')]:
            shutil.copyfile(source/source_name,fixture.ARTIFACTS/f'{variant}.{extension}')
        if version==1:
            shutil.copytree(source/'baseline-candidate',ROOT/'build/accepted/paravoidAndroidDebug',dirs_exist_ok=True)
    print('BUILT recovery '+updater,flush=True)


def run(device,updater,minify=False):
    build(updater,minify)
    device.install('crash')
    device.run('shell','run-as',fixture.SHELL,'mkdir','-p','files')
    device.run('shell','run-as',fixture.SHELL,'sh','-c','"echo saved-user-data > files/recovery-sentinel"')
    device.launch(wait=False)
    device.await_(lambda: '.pending' in device.run('shell','run-as',fixture.SHELL,'ls','no_backup/paravoid-crash-recovery',check=False),'startup crash marker')
    device.await_(lambda: not device.run('shell','pidof',fixture.SHELL,check=False).strip(),'startup process termination')
    device.stop(); device.launch(); recovery(device)
    assert device.state()['quarantined']
    print('PASS startup crash, durable next-launch gate, separate process and no payload mapping',flush=True)
    previous=device.run('shell','run-as',fixture.SHELL,'ls','no_backup/paravoid-crash-recovery')
    tap(device,'Retry app'); tap(device,'Restart')
    device.await_(lambda:device.run('shell','run-as',fixture.SHELL,'ls','no_backup/paravoid-crash-recovery')!=previous,'retry recorded')
    device.await_(lambda: not device.run('shell','pidof',fixture.SHELL,check=False).strip(),'retry crashes safely')
    device.stop(); device.launch(); recovery(device)
    print('PASS explicit retry retains crash recovery after repeated failure',flush=True)
    if updater=='custom':
        device.stop()
        device.run('shell','run-as',fixture.SHELL,'mkdir','-p','no_backup/paravoid-delivery/shared-v1/provider')
        device.run('shell','run-as',fixture.SHELL,'touch','no_backup/paravoid-delivery/shared-v1/provider/kill-provider')
        device.launch()
        device.await_(lambda: not device.run('shell','pidof',fixture.SHELL+':paravoid_recovery',check=False).strip(),'provider process exit')
        device.stop(); device.launch(); recovery(device)
        assert 'previous update provider was interrupted' in text(device)
        device.run('shell','run-as',fixture.SHELL,'rm','no_backup/paravoid-delivery/shared-v1/provider/kill-provider')
        print('PASS provider crash guard prevents automatic restart loop',flush=True)
    server=reference.Server(('127.0.0.1',0),download.catalog(reference,fixture,device,'repair',2))
    thread=threading.Thread(target=server.serve_forever,daemon=True)
    device.run('reverse','tcp:18765','tcp:'+str(server.server_address[1])); thread.start()
    try:
        if updater=='custom': tap(device,'Check again')
        else: device.stop(); device.launch(); recovery(device)
        device.await_(lambda:'Update available: 2' in text(device),'automatic verified check')
        assert device.state()['pending'] is None,'Recovery automatically downloaded without consent'
        pid=device.run('shell','pidof',fixture.SHELL+':paravoid_recovery').strip()
        device.run('shell','settings','put','system','accelerometer_rotation','0')
        device.run('shell','settings','put','system','user_rotation','1')
        assert 'Update available: 2' in text(device)
        assert device.run('shell','pidof',fixture.SHELL+':paravoid_recovery').strip()==pid
        device.run('shell','settings','put','system','user_rotation','0')
        tap(device,'Update')
        device.await_(lambda:device.state()['pending'] is not None,'signed VPK staged')
        assert 'Update ready' in text(device)
        tap(device,'Restart'); tap(device,'Restart')
        device.healthy(2)
        assert device.run('shell','run-as',fixture.SHELL,'cat','files/recovery-sentinel').strip()=='saved-user-data'
        print('PASS '+updater+' signed VPK repair in unchanged shell; explicit download/restart; saved data retained',flush=True)
    finally:
        server.shutdown(); thread.join(); server.server_close(); device.run('reverse','--remove','tcp:18765')
    for label in ['Crash main thread','Crash worker thread']:
        tap(device,label)
        device.await_(lambda:not device.run('shell','pidof',fixture.SHELL,check=False).strip(),'fatal thread termination')
        device.stop(); device.launch(); recovery(device)
        tap(device,'Retry app'); tap(device,'Restart'); device.healthy(2)
        device.await_(lambda:'CRASH MAIN THREAD' in text(device),'retried payload UI')
        print('PASS healthy-generation '+label.lower(),flush=True)
    device.run('shell','am','startservice','-n',fixture.SHELL+'/'+fixture.PACKAGE+'.ProbeService$Worker','-a','crash')
    device.await_(lambda:not device.run('shell','pidof',fixture.SHELL+':worker',check=False).strip(),'secondary process crash')
    device.stop(); device.launch(); recovery(device)
    print('PASS secondary-process crash routes next launch to recovery',flush=True)
    device.stop()

def disabled(device):
    output=fixture.command([REPO/'gradlew','-p',ROOT,'assembleParavoidAndroidDebug','--offline','--max-workers=2',
                            '-PcrashRecovery=false','-PstartupFault=activity-create'],cwd=REPO,timeout=600)
    (fixture.ARTIFACTS/'recovery-disabled.log').write_text(output)
    shutil.copyfile(ROOT/'build/outputs/paravoid/paravoidAndroidDebug/shell.apk',fixture.ARTIFACTS/'disabled.apk')
    device.install('disabled'); device.launch(wait=False)
    device.await_(lambda:'paravoid-v1' in device.run('shell','run-as',fixture.SHELL,'ls','no_backup',check=False),'disabled bootstrap')
    device.await_(lambda:device.state()['quarantined'],'disabled startup quarantine')
    device.await_(lambda:not device.run('shell','pidof',fixture.SHELL,check=False).strip(),'disabled crash termination')
    device.launch(); device.recovery()
    assert 'paravoid-crash-recovery' not in device.run('shell','run-as',fixture.SHELL,'ls','no_backup')
    assert 'CHECK NOW' in text(device)
    device.stop()
    print('PASS disabled recovery preserves original startup quarantine and shell controls',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True); parser.add_argument('--avd',required=True)
    parser.add_argument('--updater',choices=['default','custom','both'],default='both')
    parser.add_argument('--minify',action='store_true')
    args=parser.parse_args(); device=fixture.Device(args.serial,args.avd)
    for updater in ['default','custom'] if args.updater=='both' else [args.updater]: run(device,updater,args.minify)
    if args.updater=='both': disabled(device)
