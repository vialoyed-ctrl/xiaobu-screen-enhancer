$ErrorActionPreference = 'Stop'
$testClasses = Join-Path $PSScriptRoot ('build/tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$production = Join-Path $PSScriptRoot 'src/main/java/dev/codex/xiaobu'
& javac -encoding UTF-8 -d $testClasses (Join-Path $production 'ScreenshotSession.java') (Join-Path $production 'gesture/PressGate.java') (Join-Path $production 'gesture/NativeActionPolicy.java') (Join-Path $PSScriptRoot 'tests/dev/codex/xiaobu/ScreenshotSessionTest.java') (Join-Path $PSScriptRoot 'tests/java/dev/codex/xiaobu/gesture/PressGateTest.java') (Join-Path $PSScriptRoot 'tests/java/dev/codex/xiaobu/gesture/NativeActionPolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $testClasses dev.codex.xiaobu.ScreenshotSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Screenshot session test failed' }
& java -cp $testClasses dev.codex.xiaobu.gesture.PressGateTest
if ($LASTEXITCODE -ne 0) { throw 'Gesture cancellation test failed' }
& java -cp $testClasses dev.codex.xiaobu.gesture.NativeActionPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Native action policy test failed' }
& javac -encoding UTF-8 -d $testClasses (Join-Path $production 'WordSelectionMask.java') (Join-Path $production 'WordSelectionDrag.java') (Join-Path $PSScriptRoot 'tests/dev/codex/xiaobu/WordSelectionMaskTest.java') (Join-Path $PSScriptRoot 'tests/dev/codex/xiaobu/WordSelectionDragTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Word selection test compilation failed' }
& java -cp $testClasses dev.codex.xiaobu.WordSelectionMaskTest
if ($LASTEXITCODE -ne 0) { throw 'Word selection mask test failed' }
& java -cp $testClasses dev.codex.xiaobu.WordSelectionDragTest
if ($LASTEXITCODE -ne 0) { throw 'Word selection drag test failed' }
& javac -encoding UTF-8 -d $testClasses (Join-Path $production 'SelectedText.java') (Join-Path $PSScriptRoot 'tests/dev/codex/xiaobu/SelectedTextTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Selected text test compilation failed' }
& java -cp $testClasses dev.codex.xiaobu.SelectedTextTest
if ($LASTEXITCODE -ne 0) { throw 'Single-line copy test failed' }
