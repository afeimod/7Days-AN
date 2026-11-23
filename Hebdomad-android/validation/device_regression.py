"""Regression on the user's second LDPlayer instance; never uninstall or erase saves."""
import argparse
import io
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'validation' / '2026-11-23'
ADB = pathlib.Path('D:/leidian/LDPlayer9/adb.exe')
CONSOLE = ADB.with_name('ldconsole.exe')
SERIAL = 'emulator-5556'
PACKAGE = 'org.hebdomad.android'

def adb(*args, check=True):
    process = subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True, timeout=60)
    if check and process.returncode:
        raise RuntimeError(process.stderr.decode(errors='replace'))
    return process.stdout

def verify_instance():
    rows = subprocess.check_output([str(CONSOLE), 'list2']).decode('gb18030', errors='replace').splitlines()
    selected = next(row.split(',') for row in rows if row.startswith('1,'))
    if '7day' not in selected[1].lower() or selected[4] != '1':
        raise RuntimeError('The second 7Days instance must be running: ' + repr(selected))
    print('Testing instance:', selected[:2], SERIAL, flush=True)
    OUT.mkdir(parents=True, exist_ok=True)

def key(code):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(.8)

def click(text):
    adb('shell', 'uiautomator', 'dump', '/sdcard/qiye-regression.xml')
    root = ET.fromstring(adb('shell', 'cat', '/sdcard/qiye-regression.xml'))
    for node in root.iter('node'):
        if node.get('text', '').startswith(text):
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(.8)
            return
    raise RuntimeError('Missing UI item: '+text)

def capture(name):
    data = adb('exec-out', 'screencap', '-p')
    (OUT/(name+'.png')).write_bytes(data)
    image = Image.open(io.BytesIO(data)).convert('RGB')
    # Ignore virtual controls and inspect the middle of the actual game image.
    w,h = image.size
    crop = image.crop((w*.3,h*.2,w*.7,h*.8))
    pixels = list(crop.resize((96,64)).getdata())
    nonblack = sum(max(pixel)>20 for pixel in pixels)/len(pixels)
    result={'name':name,'size':[w,h],'nonblack_fraction':nonblack}
    print(json.dumps(result),flush=True)
    return result

def evidence():
    for name in ('port-log.txt','engine-output.txt','engine-errors.txt'):
        (OUT/name).write_bytes(adb('shell','run-as',PACKAGE,'cat','files/'+name,check=False))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['install','status','capture','click','key','load-cycle','home-cycle','evidence'])
    parser.add_argument('value',nargs='?',default='current')
    args=parser.parse_args()
    verify_instance()
    if args.action=='install':
        backup=OUT/'pre-install-saves.tar'
        if not backup.exists():
            backup.write_bytes(adb('exec-out','run-as',PACKAGE,'tar','-cf','-','files','shared_prefs'))
        result=adb('install','-r',str(ROOT/'build/Hebdomad-experimental.apk'))
        print(result.decode(errors='replace'),flush=True)
        if b'Success' not in result:raise RuntimeError('Update rejected; existing app and saves kept.')
        adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-n',PACKAGE+'/.LauncherActivity')
        time.sleep(8)
        capture('first-launch')
        evidence()
    elif args.action=='status':
        print(adb('shell','run-as',PACKAGE,'ls','files').decode(errors='replace'))
        print(adb('shell','dumpsys','activity','activities').decode(errors='replace')[-5000:])
        evidence()
    elif args.action=='capture':capture(args.value);evidence()
    elif args.action=='click':click(args.value)
    elif args.action=='key':key(int(args.value))
    elif args.action=='evidence':evidence()
    elif args.action=='load-cycle':
        results=[]
        for cycle in range(int(args.value)):
            key(139);click('槽位 1');time.sleep(3)
            result=capture('load-'+str(cycle+1));results.append(result)
            if result['nonblack_fraction']<.03:raise RuntimeError('Black game image after load')
        (OUT/'load-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        evidence()
    elif args.action=='home-cycle':
        activities=adb('shell','dumpsys','activity','activities').decode(errors='replace')
        match=re.search(r'TaskRecord\{[^}]*#(\d+)[^}]*'+re.escape(PACKAGE),activities)
        if not match:raise RuntimeError('Game must be in foreground before Home cycling')
        current_task=int(match.group(1))
        results=[]
        for cycle in range(int(args.value)):
            key(3);adb('shell','am','task','focus',str(current_task));time.sleep(2)
            result=capture('home-'+str(cycle+1));results.append(result)
            if result['nonblack_fraction']<.03:raise RuntimeError('Black game image after Home')
        (OUT/'home-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        evidence()

if __name__=='__main__':main()
