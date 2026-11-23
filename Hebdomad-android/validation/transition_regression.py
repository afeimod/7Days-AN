"""Alternate rooms through a temporary slot; restore without overwriting saves."""
import json
import device_regression as d

def main():
    d.verify_instance()
    # The native original slot has a different room from quick slots 1 and 2.
    test='files/slot101.json'
    existing=d.adb('shell','run-as',d.PACKAGE,'ls',test,check=False)
    if existing:raise RuntimeError('Quick slot 3 is occupied; diagnostic will not overwrite it')
    d.adb('shell','run-as',d.PACKAGE,'cp','files/slot1.json',test)
    results=[]
    try:
        for cycle in range(10):
            slot=3 if cycle%2==0 else 1
            d.key(139);d.click('槽位 '+str(slot));d.time.sleep(4)
            result=d.capture('room-switch-'+str(cycle+1));result['slot']=slot;results.append(result)
            if result['nonblack_fraction']<.03:raise RuntimeError('Black game image after room switch')
        (d.OUT/'room-switch-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    finally:
        d.evidence()
        d.adb('shell','run-as',d.PACKAGE,'rm',test)

if __name__=='__main__':main()
