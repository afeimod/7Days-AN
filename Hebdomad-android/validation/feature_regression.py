"""Interactive regression on LDPlayer index 1 only; preserve user saves."""
import json
import device_regression as d

def settings(item):
    d.adb('shell','input','tap','2304','96');d.time.sleep(.5)
    d.adb('shell','input','swipe','1300','800','1300','300','400');d.time.sleep(.5)
    d.click(item)

def main():
    d.verify_instance()
    d.key(139);d.click('槽位 1');d.time.sleep(4)
    results=[]
    outfits=['普通服装','黑色服装','白色面具服装','普通服装（B 款）','黑色服装（B 款）','白色面具服装（B 款）','邪恶 Ken','邪恶 Ken（黑白）']
    for index,name in enumerate(outfits):
        settings('服装与隐藏外观');d.click(name);d.time.sleep(3)
        result=d.capture('verified-costume-'+str(index));result['costume']=name;results.append(result)
        if result['nonblack_fraction']<.03:raise RuntimeError('Black image after costume change')
    for index,name in enumerate(['空手','小刀','西洋剑','手枪','沙鹰','黄金沙鹰（新增）']):
        settings('武器选择');d.click(name);d.time.sleep(3)
        result=d.capture('verified-weapon-'+str(index));result['weapon']=name;results.append(result)
        if result['nonblack_fraction']<.03:raise RuntimeError('Black image after weapon change')
    # Return to the ordinary appearance after validating the alternate textures.
    settings('服装与隐藏外观');d.click('普通服装');d.time.sleep(2)
    (d.OUT/'feature-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    d.evidence()

if __name__=='__main__':main()
