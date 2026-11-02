import struct, zipfile, re, sys
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

# Usage: python validate_screenshot_contract.py <17.5.14.apk> <17.7.8.apk>
# Checks the exact reflective members used by ScreenshotButtons/SelectionToolsHook.
def verify(apk, modern):
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                classes.update((c['type'], c) for c in Dex(archive.read(name), name).classes)
    def method(owner, signature):
        key = 'L' + owner.replace('.', '/') + ';'
        expected = key + '->' + signature
        assert any(m == expected for m, _, _ in classes[key]['methods']), expected
    def field(owner, signature):
        key = 'L' + owner.replace('.', '/') + ';'
        expected = key + '->' + signature
        assert expected in classes[key]['fields'], expected
    alert = 'com.coloros.directui.ui.DirectCardAlert'
    field(alert, ('o:Ljava/util/ArrayList;' if modern else 'n:Ljava/util/List;'))
    method(alert, ('j' if modern else 'a') + '(ILandroid/view/View;)V')
    method(alert, ('v' if modern else 'Z') + '(Ljava/util/List;Z)V')
    method(alert, ('p' if modern else 'I') + '()V')
    method('wb0' if modern else 'com.oplus.infocollection.collection.common.b',
           ('a(Landroid/content/Context;Ljava/lang/String;Landroid/graphics/Rect;ZI)' if modern
            else 'c(Landroid/content/Context;Ljava/lang/String;Landroid/graphics/Rect;Z)') + 'Landroid/graphics/Bitmap;')
    popup = 'dw1' if modern else 'com.coloros.directui.ui.panel.FloatPopupList'
    item = 'cw1' if modern else popup + '$b'
    method(popup, '<init>(Landroid/content/Context;)V')
    method(item, '<init>(Ljava/lang/String;Landroid/graphics/drawable/Drawable;' +
           ('Ljava/util/ArrayList;' if modern else 'Ljava/util/List;') + ')V')
    method(popup, ('f(Ljava/util/ArrayList;)' if modern else 'p(Ljava/util/List;)') + 'V')
    method(popup, ('h' if modern else 't') + '(Landroid/view/View;)V')
    method(popup, ('d' if modern else 'n') + '()Z')
    if modern:
        field(popup, 'a:Lkotlin/jvm/functions/Function3;')
        method(popup, 'a()V')
    else:
        method(popup, 'q(Lkotlin/jvm/functions/Function3;)V')
    base = 'com.oplus.supertext.core.view.focusmenu.'
    listener = base + ('a' if modern else 'FocusMenuView$initBasicFunctions$1$1')
    method(listener, 'a(I)V' if modern else 'onItemClick(Landroid/view/View;I)V')
    field(listener, 'a:Lcom/oplus/supertext/core/view/focusmenu/FocusMenuView;')
    field(listener, 'b:Ljava/util/List;')
    method(base + 'FocusMenuView', 'setIdentificationData(Lcom/oplus/supertext/core/view/focusmenu/IdentificationData;)V')
    method(base + 'FocusMenuView', 'onDetachedFromWindow()V')
    method('com.coloros.directui.ui.CollectInfoActivity', 'onDestroy()V')
    print(('17.7.8' if modern else '17.5.14') + ': screenshot, popup, toolbar and teardown contracts passed')

if __name__ == '__main__':
    verify(sys.argv[1], False)
    verify(sys.argv[2], True)
