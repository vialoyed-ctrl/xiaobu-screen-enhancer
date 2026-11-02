import struct, zipfile, re, sys, json
from pathlib import Path

class Dex:
    def __init__(self, data, name):
        self.d, self.name = data, name
        self.u16=lambda p:struct.unpack_from('<H',data,p)[0]
        self.u32=lambda p:struct.unpack_from('<I',data,p)[0]
        self.strings=[]
        n,o=self.u32(56),self.u32(60)
        for i in range(n):
            p=self.u32(o+4*i); _,p=self.uleb(p)
            self.strings.append(data[p:data.index(b'\0',p)].decode('utf-8','replace'))
        n,o=self.u32(64),self.u32(68)
        self.types=[self.strings[self.u32(o+4*i)] for i in range(n)]
        self.protos=[]
        n,o=self.u32(72),self.u32(76)
        for i in range(n):
            p=o+12*i; po=self.u32(p+8)
            params=[] if not po else [self.types[self.u16(po+4+2*j)] for j in range(self.u32(po))]
            self.protos.append('('+''.join(params)+')'+self.types[self.u32(p+4)])
        n,o=self.u32(80),self.u32(84)
        self.fields=[self.types[self.u16(o+8*i)]+'->'+self.strings[self.u32(o+8*i+4)]+':'+self.types[self.u16(o+8*i+2)] for i in range(n)]
        n,o=self.u32(88),self.u32(92)
        self.methods=[self.types[self.u16(o+8*i)]+'->'+self.strings[self.u32(o+8*i+4)]+self.protos[self.u16(o+8*i+2)] for i in range(n)]
        self.classes=[]
        n,o=self.u32(96),self.u32(100)
        for i in range(n):
            p=o+32*i; typ=self.types[self.u32(p)]; src=self.u32(p+16); cd=self.u32(p+24)
            c={'type':typ,'source':self.strings[src] if src!=0xffffffff else '', 'fields':[], 'methods':[], 'super':self.types[self.u32(p+8)] if self.u32(p+8)!=0xffffffff else ''}
            if cd:
                sizes=[]
                for _ in range(4):v,cd=self.uleb(cd);sizes.append(v)
                for count in sizes[:2]:
                    idx=0
                    for _ in range(count):delta,cd=self.uleb(cd);flags,cd=self.uleb(cd);idx+=delta;c['fields'].append(self.fields[idx])
                for count in sizes[2:]:
                    idx=0
                    for _ in range(count):
                        delta,cd=self.uleb(cd);flags,cd=self.uleb(cd);co,cd=self.uleb(cd);idx+=delta
                        c['methods'].append((self.methods[idx],co,flags))
            self.classes.append(c)
    def uleb(self,p):
        v=0;s=0
        while True:
            b=self.d[p];p+=1;v|=(b&127)<<s;s+=7
            if b<128:return v,p
    def code(self,co):
        if not co:return []
        p=co+16;end=p+2*self.u32(co+12);out=[]
        widths={0x02:2,0x03:3,0x05:2,0x06:3,0x08:2,0x09:3,0x13:2,0x14:3,0x15:2,0x16:2,0x17:3,0x18:5,0x19:2,0x1a:2,0x1b:3,0x1c:2,0x1f:2,0x20:2,0x22:2,0x23:2,0x24:3,0x25:3,0x26:3,0x29:2,0x2a:3,0x2b:3,0x2c:3,0xfa:4,0xfb:4,0xfc:3,0xfd:3,0xfe:2,0xff:2}
        for op in range(0x2d,0x3e):widths[op]=2
        for op in range(0x44,0x6e):widths[op]=2
        for op in list(range(0x6e,0x73))+list(range(0x74,0x79)):widths[op]=3
        for op in range(0x90,0xb0):widths[op]=2
        for op in range(0xd0,0xe3):widths[op]=2
        while p<end:
            v=self.u16(p);op=v&255;w=widths.get(op,1);ref=''
            if op==0 and v:
                if v==0x100:w=4+2*self.u16(p+2)
                elif v==0x200:w=2+4*self.u16(p+2)
                elif v==0x300:w=4+(self.u16(p+2)*self.u32(p+4)+1)//2
            try:
                if op==0x1a:ref='STRING '+repr(self.strings[self.u16(p+2)])
                elif op==0x1b:ref='STRING '+repr(self.strings[self.u32(p+2)])
                elif op in (0x1c,0x1f,0x20,0x22,0x23,0x24,0x25):ref=self.types[self.u16(p+2)]
                elif 0x52<=op<=0x6d:ref=self.fields[self.u16(p+2)]
                elif 0x6e<=op<=0x72 or 0x74<=op<=0x78:ref=self.methods[self.u16(p+2)]
                elif op==0x12:ref='CONST '+str((v>>12)-16 if v>>12>=8 else v>>12)
                elif op==0x13:ref='CONST '+str(struct.unpack_from('<h',self.d,p+2)[0])
                elif op==0x14:ref='CONST '+str(struct.unpack_from('<i',self.d,p+2)[0])
            except IndexError:ref='INVALID_REF'
            units=' '.join(f'{self.u16(p+2*j):04x}' for j in range(min(w,8)))
            out.append(f'{(p-co-16)//2:04x} {op:02x} {units} {ref}')
            p+=w*2
        return out

if __name__=='__main__':
    apk,mode,pattern=sys.argv[1:4];rx=re.compile(pattern,re.I)
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex',name):continue
            dex=Dex(z.read(name),name)
            for c in dex.classes:
                if mode=='classes':
                    if rx.search(c['type']+' '+c['source']):print(name,c['type'],c['source'],c['super'])
                elif mode=='dump':
                    if not rx.search(c['type']):continue
                    print('\nCLASS',name,c['type'],c['source'],c['super'])
                    for f in c['fields']:print('FIELD',f)
                    for m,co,flags in c['methods']:
                        print('\nMETHOD',m,hex(flags))
                        print('\n'.join(dex.code(co)))
                elif mode=='search':
                    for m,co,flags in c['methods']:
                        lines=[l for l in dex.code(co) if rx.search(l)]
                        if lines:print('\nMETHOD',name,m,'\n'+'\n'.join(lines))
