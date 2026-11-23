from pathlib import Path
import sys,json,struct
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from font_recovery import glyphs
unique={}
for path in sorted((ROOT/'build').glob('*.sbn')):
    for w,h,b in glyphs(path):
        if w==12 and h==12:unique[(struct.pack('<II',w,h)+b).hex()]=(w,h,b)
keys=list(unique)
(ROOT/'build/ocr-keys.json').write_text(json.dumps(keys))
for batch in range((len(keys)+399)//400):
    im=Image.new('RGB',(960,1280),'white')
    for i,key in enumerate(keys[batch*400:(batch+1)*400]):
        w,h,b=unique[key];g=Image.new('L',(w,h));g.putdata([0 if b[k//8]&(1<<(k%8)) else 255 for k in range(w*h)])
        im.paste(g.resize((48,48),Image.Resampling.NEAREST),((i%20)*48,(i//20)*64))
    im.save(ROOT/f'build/ocr-{batch}.png')
print(len(keys))

