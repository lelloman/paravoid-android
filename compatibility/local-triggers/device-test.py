#!/usr/bin/env python3
"""Opt-in production shell/Store relay gate on an explicitly selected disposable emulator.
Catalog and preferences are fixture inputs to the Store instrumentation entry point;
Binder, WebSocket listener, signed head/VPK verification, staging and restart are production code.
"""
import argparse, base64, hashlib, json, os, re, shutil, subprocess, sys, threading, time, zipfile
from pathlib import Path
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
sys.path.insert(0, str(REPO / 'delivery/reference'))
from server import Catalog, Server, Handler
PREFIX = 'com.lelloman.paravoidfixture.trigger.'

def run(args, timeout=300, check=True):
    result = subprocess.run(list(map(str,args)),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    text = result.stdout.decode(errors='replace')
    if check and result.returncode: raise RuntimeError(f'{args}\n{text}')
    return text

def wait(test, label, seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if test(): return
        time.sleep(.5)
    raise AssertionError('Timed out: '+label)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--store-root',type=Path,default=REPO.parent/'lellostore')
    p.add_argument('--skip-build',action='store_true')
    p.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk'))))
    args=p.parse_args()
    os.environ['ANDROID_HOME']=str(args.sdk)
    if not re.fullmatch(r'emulator-\d+',args.serial): p.error('Only a disposable emulator is allowed')
    def adb(*parts,**kw): return run(['adb','-s',args.serial,*parts],**kw)
    sdk=int(adb('shell','getprop','ro.build.version.sdk').strip()); assert sdk in (30,36)
    assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
    artifacts=ROOT/'build/device'; artifacts.mkdir(parents=True,exist_ok=True)
    store=args.store_root/'android/app/build/outputs/apk/debug/app-debug.apk'
    tests=args.store_root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    caller=json.loads((store.parent/'output-metadata.json').read_text())['applicationId']
    test_package=json.loads((tests.parent/'output-metadata.json').read_text())['applicationId']
    cert=run([args.sdk/'build-tools/36.0.0/apksigner','verify','--print-certs',store])
    pin=re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})',cert)[1]
    cases={'one':[], 'two':[], 'discover':['-PdownloadsDisabled'], 'rejected':[]}
    keys=ROOT/'build/keys'; keys.mkdir(parents=True,exist_ok=True)
    trust={'version':1,'minimumPayloadVersion':1,'minimumHeadRevision':1}
    for role in ('release','head','grant'):
        path=keys/(role+'.der')
        if not path.exists():
            key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
            path.write_bytes(key.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));path.chmod(0o600)
        key=serialization.load_der_private_key(path.read_bytes(),None)
        trust[role+'Keys']={role:base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()}
    if not args.skip_build:
        for case,flags in cases.items():
            package=PREFIX+case
            trust['applicationId']=package+'.paravoid'; (keys/'trust.json').write_text(json.dumps(trust))
            for version in (1,2,3):
                dest=artifacts/case/str(version); dest.mkdir(parents=True,exist_ok=True)
                command=[REPO/'gradlew','-p',ROOT,'assembleParavoidAndroidDebug','--offline','--max-workers=2','--console=plain',
                    '-PfixturePackage='+package,'-PcallerPackage='+caller,'-PcallerPin='+('0'*64 if case=='rejected' else pin),
                    '-Pgeneration='+str(version),'-PpayloadVersion='+str(version),*flags]
                if version>1: command+=['-Pbaseline']
                (dest/'build.log').write_text(run(command,timeout=600))
                source=ROOT/'build/outputs/paravoid/paravoidAndroidDebug'
                for name in ('shell.apk','payload.vpk','release.json'):
                    shutil.copyfile(source/name,dest/name)
                if version==1: shutil.copytree(source/'baseline-candidate',ROOT/'build/accepted/paravoidAndroidDebug',dirs_exist_ok=True)
                print('BUILT',case,version,flush=True)
    abis=adb('shell','getprop','ro.product.cpu.abilist64').strip().split(',')
    headkey=serialization.load_der_private_key((keys/'head.der').read_bytes(),None)
    def catalog(version):
        c=Catalog()
        for case in cases:
            dest=artifacts/case/str(version)
            with zipfile.ZipFile(artifacts/case/'1/shell.apk') as z: policy=json.loads(z.read('assets/paravoid/shell-policy.json'))
            updates=policy['descriptor']['distribution']['updates']
            assert updates['pushEnabled']=='false'
            envelope=(dest/'release.json').read_bytes(); release=json.loads(base64.b64decode(json.loads(envelope)['body']))
            assert release['shellContractId']==policy['contractId']
            archive=dest/'payload.vpk'; package=PREFIX+case+'.paravoid'; now=int(time.time())
            body=dict(version=1,applicationId=package,shellContractId=policy['contractId'],channel='stable',sdk=sdk,abis=abis,
                runtimeAbi=1,formatVersion=1,headRevision=version,issuedAt=now-1,expiresAt=now+3600,status='available',
                release=dict(releaseId=release['releaseId'],payloadVersion=version,manifestSha256=hashlib.sha256(envelope).hexdigest(),
                    archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),archiveSize=archive.stat().st_size))
            encoded=json.dumps(body,sort_keys=True,separators=(',',':')).encode()
            signed=json.dumps(dict(keyId='head',body=base64.b64encode(encoded).decode(),signature=base64.b64encode(headkey.sign(b'paravoid/v1/head\n'+encoded,padding.PKCS1v15(),hashes.SHA256())).decode())).encode()
            c.add_head(package,dict(contract=policy['contractId'],channel='stable',sdk=str(sdk),abis=','.join(abis),runtime='1',format='1',protocol='1'),signed)
            c.add_archive(package,release['releaseId'],archive)
        return c
    requests=[]
    class Observed(Handler):
        def do_GET(self):
            requests.append(self.path)
            super().do_GET()
    server=Server(('127.0.0.1',18765),catalog(1));server.RequestHandlerClass=Observed
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    def stop_payload(package):
        adb('shell','input','keyevent','KEYCODE_HOME')
        # am kill is advisory and may retain a recently visible/bound process.
        # Kill only this fixture's main PID, preserving its jobs and stopped flag.
        time.sleep(.5)
        for pid in adb('shell','pidof',package,check=False).split():
            assert pid.isdigit()
            result=adb('shell',f"run-as {package} sh -c 'kill -9 {pid}'",check=False)
            if any(error in result.lower() for error in ('not permitted', 'permission denied')) and sdk==30:
                assert adb('shell','getprop','ro.build.type').strip()=='userdebug'
                adb('root');adb('wait-for-device')
                adb('reverse','tcp:18765','tcp:18765')
                adb('shell','kill','-9',pid,check=False)
        wait(lambda: not adb('shell','pidof',package,check=False).strip(),'fixture payload exit')
    def state(case):
        return adb('shell','run-as',PREFIX+case+'.paravoid','cat','no_backup/paravoid-updates-v1/operations.properties',check=False)
    def marker(case):
        return adb('shell','run-as',PREFIX+case+'.paravoid','cat','shared_prefs/payload-probe.xml',check=False)
    try:
        adb('reverse','tcp:18765','tcp:18765')
        adb('install','-r',store);adb('install','-r',tests)
        for case in cases:
            package=PREFIX+case+'.paravoid'
            adb('install','-r',artifacts/case/'1/shell.apk');adb('shell','pm','clear',package)
            adb('shell','am','start','-W','--activity-clear-task','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',package+'/com.lelloman.paravoidandroid.runtime.LauncherActivity')
            wait(lambda: 'lastCheck=0' not in state(case) and 'kind=NONE' in state(case) and 'available.version=1' in state(case),'initial current check '+case)
            stop_payload(package)
        before={case:state(case) for case in cases}
        def relay(mode):
            out=adb('shell','am','instrument','-w','-r','-e','class','com.lelloman.store.e2e.ParavoidTriggerDeviceTest',
                '-e','localTriggers','true','-e','triggerMode',mode,'-e','triggerPackages',','.join(PREFIX+x+'.paravoid' for x in cases),
                test_package+'/com.lelloman.store.HiltTestRunner',timeout=120)
            (artifacts/(f'{sdk}-{mode}-instrument.txt')).write_text(out)
            assert 'OK (1 test)' in out,out
        for version,mode in ((2,'event'),(3,'poll')):
            server.catalog=catalog(version)
            relay(mode)
            for case in ('one','two'):
                wait(lambda: 'phase=READY' in state(case) and f'available.version={version}' in state(case),'automatic stage '+case,120)
                assert not adb('shell','pidof',PREFIX+case+'.paravoid',check=False).strip(), 'Payload started during background update'
                assert f'>{version-1}<' in marker(case),marker(case)
            wait(lambda: f'available.version={version}' in state('discover'),'check-only offer',120)
            assert 'phase=AVAILABLE' in state('discover')
            assert state('rejected')==before['rejected'],'Rejected caller changed update state'
            for case in ('one','two'):
                package=PREFIX+case+'.paravoid'
                adb('shell','am','start','-W','--activity-clear-task','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',package+'/com.lelloman.paravoidandroid.runtime.LauncherActivity')
                wait(lambda: f'>{version}<' in marker(case),'activation '+case)
                wait(lambda: package+'/' in adb('shell',"dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'",check=False),'foreground activity '+case)
                stop_payload(package)
            print('PASS',sdk,mode,'two shells staged; check-only respected; untrusted pin rejected; payload stayed stopped until foreground',flush=True)
        (artifacts/f'{sdk}-requests.json').write_text(json.dumps(requests,indent=2))
    finally:
        server.shutdown();server.server_close();adb('reverse','--remove','tcp:18765',check=False)
if __name__=='__main__': main()
