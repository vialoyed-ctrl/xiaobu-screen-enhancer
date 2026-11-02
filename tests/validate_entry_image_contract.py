"""Validate entry/image contracts against a locally supplied host APK (no APK bundled)."""
import importlib.util, sys, zipfile, re

spec = importlib.util.spec_from_file_location('dex_inspect', sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
classes = {}
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            dex = module.Dex(apk.read(name), name)
            for cls in dex.classes:
                classes[cls['type']] = (dex, cls)

def method(owner, signature, static=None):
    dex, cls = classes[owner]
    matches = [(name, code, flags) for name, code, flags in cls['methods'] if name == owner + '->' + signature]
    assert len(matches) == 1, (owner, signature)
    if static is not None:
        assert bool(matches[0][2] & 8) == static, (owner, signature, 'static mismatch')
    return '\n'.join(dex.code(matches[0][1]))

def field(owner, signature):
    assert owner + '->' + signature in classes[owner][1]['fields'], (owner, signature)

if 'Ly34;' in classes:
    entrance = 'Lcom/coloros/colordirectservice/identify/entrance/c;'
    code = method(entrance, 'a('+entrance+'Landroid/content/Context;Lcom/coloros/colordirectservice/identify/beans/DirectStartInfo;ZLkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Object;', True)
    assert 'DirectEntranceHandler$checkBlack$1' in code
    assert 'com.android.launcher.Launcher' in method('Lxk0;', 'a()Z', True)
    code = method('Lz66;', 'a(Landroid/content/Context;)Ly66;', True)
    assert 'getVisibleWindowInfo(I)Ljava/util/List;' in code and 'checkSecureWindow' in code
    parser = 'Lcom/oplus/infocollection/collection/core/a;'
    code = method(parser, 'z(Ly34;Lig2;Ljava/util/List;Ljava/util/concurrent/CopyOnWriteArrayList;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Object;', False)
    assert 'CollectionParse$mergeImages$1' in code
    method(parser, 'D(Ljava/util/concurrent/CopyOnWriteArrayList;)V', True)
    assert 'collection/extract/a;->a(Landroid/graphics/Bitmap;Ljava/util/List;Ljava/util/concurrent/CopyOnWriteArrayList;)V' in method(parser, 'o(Landroid/graphics/Bitmap;Ljava/util/List;Ljava/util/concurrent/CopyOnWriteArrayList;)V', True)
    for owner, signature in [('Ly34;','a:Landroid/graphics/Bitmap;'), ('Lig2;','b:Ljava/util/List;'), ('Lyz5;','f:Z'), ('Lyz5;','l:Landroid/graphics/Rect;'), ('Lyz5;','o:Ljava/util/ArrayList;'), ('Ldg0;','g:Landroid/graphics/Rect;'), ('Lfl0;','a:Landroid/graphics/Rect;'), ('Lfl0;','c:I')]:
        field(owner, signature)
    print('PASS: 17.7.8 entry, scoped window precheck, visible images and native crop/sort contracts')
else:
    assert 'com.android.launcher.Launcher' in method('Lcom/oplus/infocollection/common/a;', 'a()Z', False)
    parser = 'Lcom/oplus/infocollection/collection/core/CollectionParse;'
    method(parser, 'h0(Lyi/n;Lcom/oplus/infocollection/collection/extract/c;Ljava/util/List;Ljava/util/concurrent/CopyOnWriteArrayList;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;', False)
    method(parser, 'r0(Ljava/util/concurrent/CopyOnWriteArrayList;)V', False)
    print('PASS: legacy entry and visible-image contracts retained')
