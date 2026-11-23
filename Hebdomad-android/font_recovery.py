from PIL import Image, ImageDraw, ImageFont
import pathlib, struct
ROOT=pathlib.Path(__file__).resolve().parent
def glyphs(path):
    b=path.read_bytes(); p=4
    for i in range(struct.unpack_from('<I',b)[0]):
        w,h,n=struct.unpack_from('<III',b,p);p+=12
        yield w,h,b[p:p+n]
        p+=(n+3)//4*4
def main():
    import numpy as np
    originals=list(glyphs(ROOT/'build/system.sbn'))
    hzk=(ROOT/'build/bitmap-font-reference/font/HZK12').read_bytes()
    print('HZK size',len(hzk))
    for c,(w,h,b) in zip('确定返回是否保存读取删除默认无记录使用下一个帮助最近的档吗？不可',originals):
        a,z=c.encode('gb2312');off=((a-161)*94+z-161)*24
        bits=hzk[off:off+24]
        expected=np.array([bool(b[k//8]&(1<<(k%8))) for k in range(w*h)]).reshape(h,w)
        ref=np.array([bool(bits[y*2+x//8]&(128>>(x%8))) for y in range(12) for x in range(12)]).reshape(12,12)
        print(c,int((expected!=ref[:h,:w]).sum()))
    for size in [12,13,14,15,16]:
        f=ImageFont.truetype('C:/Windows/Fonts/simsun.ttc',size)
        distances=[]
        for c,(w,h,b) in zip('确定返回是否保存读取删除默认无记录使用下一个帮助最近的档吗？不可',originals):
            g=Image.fromarray(np.array([255 if b[k//8] & (1<<(k%8)) else 0 for k in range(w*h)],dtype=np.uint8).reshape(h,w))
            mask=f.getmask(c,mode='1');a=Image.frombytes('L',mask.size,bytes(mask))
            distances.append(min(sum(x!=y for x,y in zip(g.tobytes(),(lambda im:(im.paste(a,(dx,dy)),im)[1])(Image.new('L',(w,h))).tobytes())) for dx in range(-3,4) for dy in range(-3,4)))
        print(size,distances[:16])
    im=Image.new('RGB',(640,240),'#555555');d=ImageDraw.Draw(im)
    font=ImageFont.truetype('C:/Windows/Fonts/simsun.ttc',12)
    for i,c in enumerate('七夜物品地图文件系统'):d.text((i*20,200),c,font=font,fill='white')
    for i,(w,h,bits) in enumerate(glyphs(ROOT/'build/system.sbn')):
        if i>=32:break
        for order in range(2):
            g=Image.new('RGB',(w,h),'black')
            g.putdata([(255,255,255) if bits[k//8] & (1 << ((7-k%8) if order==0 else k%8)) else (0,0,0) for k in range(w*h)])
            im.paste(g.resize((w*2,h*2)),((i%16)*40,(i//16)*60+order*27))
    im.save(ROOT/'build/glyphs.png')
if __name__=='__main__':main()
