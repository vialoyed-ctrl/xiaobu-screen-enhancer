import sys,zipfile
from dex_inspect import Dex

def verify(path, modern):
    classes={}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                d=Dex(z.read(n),n)
                classes.update({c["type"][1:-1].replace("/","."):c for c in d.classes})
    checks=0
    def require(name, methods=(), fields=()):
        nonlocal checks
        c=classes[name]
        for signature in methods:
            assert any(m.split("->")[1]==signature for m,co,f in c["methods"]),(name,signature)
            checks+=1
        for signature in fields:
            assert any(f.split("->")[1]==signature for f in c["fields"]),(name,signature)
            checks+=1
    base="com.oplus.supertext.core."
    model=base+"view.supertext."+("b" if modern else "SuperTextModel")
    md="L"+model.replace(".","/")+";"
    require(model,[("l" if modern else "G0")+"()Z",("f" if modern else "getSelectedText")+"()Ljava/lang/String;",("j" if modern else "b0")+"(I)Z",("v" if modern else "M")+"(Landroid/graphics/PointF;Z)I",("i" if modern else "a0")+"(Landroid/graphics/PointF;)V",("s" if modern else "q")+"()V"],["h:Lcom/oplus/supertext/core/data/SuperTextData;","i:I","j:I"])
    require("ob5" if modern else base+"view.supertext.c0",[("j" if modern else "a")+"(Landroid/view/MotionEvent;)Z"],["b:"+(md if modern else "Lcom/oplus/supertext/core/view/supertext/v;"),"g:Landroid/graphics/PointF;"])
    require(base+"view.supertext.SuperTextView",[("D" if modern else "e1")+"(ZZ)V","setLimitTextToolShow(Z)V"],["d:"+md] if modern else [])
    require("tc5" if modern else base+"view.supertext.SuperTextRender",[],[("b" if modern else "c")+":"+(md if modern else "Lcom/oplus/supertext/core/view/supertext/v;"),"l:Landroid/graphics/Paint;"])
    require("lb5" if modern else base+"view.supertext.w",["getSuperTextEventListeners()Ljava/util/List;",("a" if modern else "b")+"(Z)V"])
    require(base+"data.SuperTextData",["getOriginalData()Ljava/util/ArrayList;","getSwipedMask()[Z","updateSwipedArea(ZZ)V","buildAntPath()V"],["mTextSwipedMaskTop:[Z","mTextSwipedMaskBottom:[Z","mTmpMask:[Z"])
    require(base+"data.LineTextData",["getTextPointDataList()Ljava/util/ArrayList;","isImageMark()Z"])
    require(base+"data.TextPointData",["getMinX()F","getMinY()F","getMaxX()F","getMaxY()F","getText()Ljava/lang/String;","getTextIndex()I","isImageMark()Z"])
    if not modern:require(model,["c0(II)V"])
    print(path, "PASS",checks,"selection contract members")
verify(sys.argv[1],True)
verify(sys.argv[2],False)
