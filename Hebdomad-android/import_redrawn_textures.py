"""Import user redraws without changing engine texture dimensions or UVs."""
import io, json, struct, zipfile, zlib
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'redrawn-resources'
OUT.mkdir(exist_ok=True)
entries = {}
report = []

def add_signature(key, image, name):
    value = (image.width,image.height,zlib.compress(image.tobytes(),9),name)
    if key in entries and entries[key][:3] != value[:3]:
        print('Duplicate original:',name,'uses',entries[key][3])
        return
    entries[key] = value

def add(original, image, name, fmt=0x1908, typ=0x1401, dimensions=None):
    w,h = dimensions or original.size
    raw = original if isinstance(original,bytes) else original.tobytes()
    add_signature((w,h,fmt,typ,zlib.crc32(raw)), image, name)

with zipfile.ZipFile(ROOT/'logo-portraits-for-redraw.zip') as original_zip, zipfile.ZipFile('C:/Users/Administrator/Downloads/editable.zip') as incoming:
    for name in incoming.namelist():
        if not name.endswith('.png'): continue
        name = Path(name).name
        image=Image.open(io.BytesIO(incoming.read(name))).convert('RGBA')
        original=Image.open(io.BytesIO(original_zip.read('editable-png/'+name))).convert('RGBA')
        assert image.width%original.width==0 and image.height//original.height==image.width//original.width
        if name=='touxiang_02.png':
            image.putalpha(original.getchannel('A').resize(image.size,Image.Resampling.LANCZOS))
        image.save(OUT/name)
        stx=original_zip.read('original-stx/'+name.replace('.png','.stx'))
        kind,key,w,h=struct.unpack_from('<HHII',stx)
        count=(len(stx)-12-w*h)//2
        palette=struct.unpack_from('<'+'H'*count,stx,12)
        colors=[palette[i] for i in stx[12+count*2:]]
        alpha=original.getchannel('A').tobytes()
        if name=='fengmian_kuang.png':
            # The engine combines the separate alpha mask with the logo before upload.
            # Match the RGBA upload observed on the title screen.
            add_signature((w,h,0x1908,0x1401,0x7a97def0), image, name)
        else:
            for replicate in (False,True):
                rgba=bytearray()
                for p,t in zip(colors,alpha):
                    r,g,b=p>>11,(p>>5)&63,p&31
                    rgba.extend(((r<<3)|(r>>2) if replicate else r<<3,(g<<2)|(g>>4) if replicate else g<<2,(b<<3)|(b>>2) if replicate else b<<3,t))
                add(bytes(rgba),image,name,dimensions=(w,h))
            add(struct.pack('<'+'H'*len(colors),*colors),image,name,fmt=0x1907,typ=0x8363,dimensions=(w,h))
        report.append(dict(file=name,original=[w,h],replacement=list(image.size),status='imported'))

with zipfile.ZipFile('C:/Users/Administrator/Downloads/preview-png2.zip') as incoming:
    for path in incoming.namelist():
        if not path.endswith('.png'): continue
        name=Path(path).name
        original=Image.open(ROOT/'build/text-captures'/name.replace('.png','.tga')).convert('RGBA')
        image=Image.open(io.BytesIO(incoming.read(path))).convert('RGBA')
        assert image.width%original.width==0 and image.width//original.width==image.height//original.height
        changed=image.size!=original.size or image.tobytes()!=original.tobytes()
        report.append(dict(file=name,original=list(original.size),replacement=list(image.size),status='imported' if changed else 'unchanged'))
        image.save(OUT/name)
        if changed: add(original,image,name)

with (ROOT/'assets/texture-overrides.dat').open('wb') as out:
    out.write(struct.pack('<II',0x31584448,len(entries)))
    for key,(w,h,data,name) in entries.items():
        label=name.encode()
        out.write(struct.pack('<9I',*key,w,h,len(data),len(label)))
        out.write(label);out.write(data)
(OUT/'import-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('Imported',sum(e['status']=='imported' for e in report),'images;',len(entries),'matching signatures')
