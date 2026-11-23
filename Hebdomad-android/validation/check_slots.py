"""Exercise the six added slots on the explicitly selected LDPlayer."""
import pathlib, subprocess, time, json, re, xml.etree.ElementTree as ET
ADB = 'D:/leidian/LDPlayer9/adb.exe'
OUT = pathlib.Path(__file__).resolve().parent
def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args])
def key(code):
    adb('shell', 'input', 'keyevent', str(code)); time.sleep(.7)
def tap(y):
    adb('shell', 'input', 'tap', '1000', str(y)); time.sleep(1.2)
def screenshot(name):
    (OUT/name).write_bytes(adb('exec-out', 'screencap', '-p'))
def click_text(prefix):
    adb('shell','uiautomator','dump','/sdcard/slot-test.xml')
    root=ET.fromstring(adb('shell','cat','/sdcard/slot-test.xml'))
    for node in root.iter('node'):
        if node.get('text','').startswith(prefix):
            x1,y1,x2,y2=map(int,re.findall(r'[0-9]+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1.2);return
    raise AssertionError('Missing dialog item: '+prefix)
def main():
    # Slot 1 is the earlier test save; slots 2..6 are new test saves.
    for slot in range(3,7):
        key(135); click_text('槽位 '+str(slot))
    key(139); screenshot('slots.png'); key(4)
    saves={}
    for slot in range(6):
        data=adb('shell','run-as','org.hebdomad.android','cat',f'files/slot{99+slot}.json')
        saves[str(slot+1)]={'bytes':len(data),'valid_json':isinstance(json.loads(data),dict)}
    for slot in range(1,7):
        key(139); click_text('槽位 '+str(slot))
    screenshot('scene.png')
    (OUT/'slots-log.txt').write_bytes(adb('shell','run-as','org.hebdomad.android','cat','files/port-log.txt'))
    (OUT/'slots-result.json').write_text(json.dumps(saves,indent=2),encoding='utf-8')
    print(json.dumps(saves))
if __name__=='__main__': main()
