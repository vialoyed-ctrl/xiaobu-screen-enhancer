"""Verify startup/OCR hook contracts against real host DEX, without executing host code.
Usage: python tests/check_startup_apk.py APK /path/to/dex_inspect.py
The inspector is the dependency-free DEX reader used for APK migration analysis.
"""
import importlib.util
import re
import sys
import zipfile

spec = importlib.util.spec_from_file_location('host_dex', sys.argv[2])
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)
classes = {}
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            dex = inspector.Dex(apk.read(name), name)
            for cls in dex.classes:
                classes[cls['type']] = (dex, cls)


def method(owner, signature):
    dex, cls = classes['L' + owner.replace('.', '/') + ';']
    target = cls['type'] + '->' + signature
    found = [entry for entry in cls['methods'] if entry[0] == target]
    assert len(found) == 1, target
    return '\n'.join(dex.code(found[0][1]))


def field(owner, signature):
    cls = classes['L' + owner.replace('.', '/') + ';'][1]
    assert cls['type'] + '->' + signature in cls['fields'], (owner, signature)


if 'Lcom/oplus/aiunit/ocr/client/schedule/CloudResultObserver;' in classes:
    wait = method('com.oplus.aiunit.ocr.client.schedule.CloudResultObserver', 'c(J)V')
    assert 'CountDownLatch;->await(JLjava/util/concurrent/TimeUnit;)Z' in wait
    assert 'TimeUnit;->SECONDS:' in wait
    for signature in ('v(Lkotlin/jvm/functions/Function0;)V',
                      'w(Lkotlin/jvm/functions/Function0;)V',
                      'n(JLkotlin/jvm/functions/Function0;)V'):
        method('com.oplus.infocollection.floating.CollectAnimator', signature)
    method('com.coloros.directui.ui.CollectionInfoFragment', 'D()V')
    method('com.coloros.colordirectservice.repository.scenerule.QueryRuleExtDataSource',
           'd(Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;')
    method('com.coloros.colordirectservice.repository.scenerule.QueryRuleExtDataSource',
           'g(Ljava/lang/String;Ljava/lang/String;Lcom/coloros/commons/repository/net/ResponseBean;)V')
    print('PASS: 17.5.14 cloud, native scan completion, fragment and rule cache contracts')
else:
    wait = method('com.oplus.aiunit.ocr.client.schedule.a', 'b(J)V')
    field('com.oplus.aiunit.ocr.client.schedule.a', 'a:Ljava/util/concurrent/CountDownLatch;')
    assert 'CountDownLatch;->await(JLjava/util/concurrent/TimeUnit;)Z' in wait
    assert 'TimeUnit;->SECONDS:' in wait
    entry = method('com.coloros.directui.ui.CollectionInfoFragment', 'h()V')
    assert "'playEnterAnim: '" in entry and 'ValueAnimator;->start()V' in entry
    method('com.oplus.infocollection.floating.a',
           'd(Lcom/coloros/directui/ui/CollectInfoActivity;Lcom/oplus/infocollection/data/CollectionStartInfo;)V')
    completion = method('pp0', 'run()V')
    for reference in ('Lyw1;', 'Lxw1;', 'Lbk0;->c:J',
                      'COEEngine;->stopAllAnim()V', 'Lil5;->c(JLjava/lang/Runnable;)V',
                      "'playCompleteAnimMayWait gap : '", '04b0', '0032'):
        assert reference in completion, reference
    assert 'Lvt;->invoke()Ljava/lang/Object;' in method('ui0', 'run()V')
    field('ui0', 'b:Ljava/lang/Object;')
    assert 'Handler;->postDelayed(Ljava/lang/Runnable;J)Z' in method('il5', 'c(JLjava/lang/Runnable;)V')
    rule = method('com.coloros.colordirectservice.repository.scenerule.c',
                  'a(Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Object;')
    assert "'ensureLiveExtractionRules: use local first, async sync '" in rule
    assert 'Lkotlin/Unit;->INSTANCE:' in rule
    response = method('r00', 'onResponse(Lretrofit2/Call;Lretrofit2/Response;)V')
    for reference in ('Lr00;->a:Ljava/lang/Object;', 'Lr00;->b:Ljava/lang/Object;',
                      'Response;->isSuccessful()Z', 'ResponseBean;->getCode()I',
                      'CONST -1401', "'handleHttpResponse: no matched rule for '"):
        assert reference in response, reference
    field('bk0', 'm:Lbk0;')
    field('bk0', 'a:Lcom/oplus/vfxsdk/naive/COEEngine;')
    playback = method('d0', 'run()V')
    for reference in ('Lbk0;->a:Lcom/oplus/vfxsdk/naive/COEEngine;',
                      "STRING 'Start'", "STRING 'SelectStart'", 'CONST 1058642330',
                      'Animator;->setSpeed(F)V', 'COEEngine;->playAnim(Ljava/lang/String;)V'):
        assert reference in playback, reference
    loop = method('yj0', 'invoke()Ljava/lang/Object;')
    assert "STRING 'Loop'" in loop and 'Lbk0;->a:' in loop
    assert 'COEEngine;->playAnim(Ljava/lang/String;)V' in loop
    play = method('com.oplus.vfxsdk.naive.COEEngine', 'playAnim(Ljava/lang/String;)V')
    assert 'COEEngine;->getAnimator(Ljava/lang/String;)' in play
    assert 'Animator;->play(Z)V' in play
    speed = method('com.oplus.vfxsdk.common.Animator', 'setSpeed(F)V')
    assert 'Animator;->mAnimSpeed:F' in speed
    method('com.coloros.directui.ui.CollectInfoActivity', 'onCreate(Landroid/os/Bundle;)V')
    print('PASS: 17.7.8 cloud wait, completion scheduling, native 2x speed, entry and rule cache contracts')
