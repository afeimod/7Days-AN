"""Local reverse-engineering helper for the bundled, hash-pinned ARM engine."""
import pathlib, re, sys, struct
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM
ROOT = pathlib.Path(__file__).resolve().parent
def main():
    mode, *args = sys.argv[1:]
    if mode == 'func':
        source=(ROOT.parent/'reverse-engineering/recovered.c').read_text()
        for name in args:
            m=re.search(r'/\* Address: '+name.zfill(8)+r' \*/', source)
            if m:
                end=source.find('/* Address:',m.end())
                print(source[m.start():end])
        return
    with (ROOT/'assets/engine.arm').open('rb') as f:
        elf=ELFFile(f)
        def read(addr, size):
            for seg in elf.iter_segments():
                if seg['p_vaddr']<=addr<seg['p_vaddr']+seg['p_filesz']:
                    f.seek(seg['p_offset']+addr-seg['p_vaddr']);return f.read(size)
            raise ValueError(hex(addr))
        if mode=='asm':
            start=int(args[0],16);size=int(args[1],16)
            decoder=Cs(CS_ARCH_ARM,CS_MODE_ARM);decoder.skipdata=True
            for i in decoder.disasm(read(start,size),start):
                print(f'{i.address:08x}: {i.mnemonic} {i.op_str}')
        elif mode=='words':
            for addr in args:
                a=int(addr,16);b=read(a,4);print(addr,hex(struct.unpack('<I',b)[0]))
if __name__=='__main__':main()
