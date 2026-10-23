param(
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$XposedJar,
    [Parameter(Mandatory=$true)][string]$R8Jar,
    [Parameter(Mandatory=$true)][string]$Aapt2,
    [Parameter(Mandatory=$true)][string]$Keystore,
    [string]$KeyAlias = 'xiaobu',
    [string]$StorePass = $env:XIAOBU_STORE_PASSWORD,
    [string]$KeyPass = $env:XIAOBU_KEY_PASSWORD
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
foreach ($dependency in @($AndroidJar, $XposedJar, $R8Jar, $Aapt2, $Keystore)) {
    if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) { throw "Missing dependency: $dependency" }
}
if (-not $StorePass) { throw 'Set XIAOBU_STORE_PASSWORD or supply -StorePass.' }
if (-not $KeyPass) { $KeyPass = $StorePass }
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$XposedJar = (Resolve-Path -LiteralPath $XposedJar).Path
$R8Jar = (Resolve-Path -LiteralPath $R8Jar).Path
$Aapt2 = (Resolve-Path -LiteralPath $Aapt2).Path
$Keystore = (Resolve-Path -LiteralPath $Keystore).Path
[xml]$manifest = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'src/main/AndroidManifest.xml')
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$build = Join-Path $PSScriptRoot ('build/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Path $classes, $dex -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed with exit code $LASTEXITCODE" }
}
function Quote-Argument([string]$value) { return '"' + $value.Replace('\', '/') + '"' }
$source = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java')
$stubs = Join-Path $PSScriptRoot 'compile-stubs'
if (Test-Path -LiteralPath $stubs) { $source += @(Get-ChildItem -LiteralPath $stubs -Recurse -Filter '*.java') }
$compileArgs = Join-Path $build 'javac.args'
$arguments = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options', '-classpath',
    (Quote-Argument "$AndroidJar;$XposedJar"), '-d', (Quote-Argument $classes))
$arguments += @($source | ForEach-Object { Quote-Argument $_.FullName })
[IO.File]::WriteAllLines($compileArgs, $arguments, [Text.UTF8Encoding]::new($false))
& javac '-J-Duser.language=en' "@$compileArgs"
Assert-Exit 'Java compilation'

# Hidden Android classes are compile-only declarations. Never ship them in DEX.
$program = @(Get-ChildItem -LiteralPath (Join-Path $classes 'dev') -Recurse -Filter '*.class')
$dexArgs = Join-Path $build 'd8.args'
$arguments = @('--min-api', '28', '--lib', $AndroidJar, '--classpath',
    $XposedJar, '--classpath', $classes, '--output', $dex)
$arguments += @($program | ForEach-Object { $_.FullName })
[IO.File]::WriteAllLines($dexArgs, $arguments, [Text.UTF8Encoding]::new($false))
& java '-Duser.language=en' -cp $R8Jar com.android.tools.r8.D8 "@$dexArgs"
Assert-Exit 'DEX compilation'

$resources = Join-Path $build 'resources.zip'
& $Aapt2 compile --dir (Join-Path $PSScriptRoot 'src/main/res') -o $resources
Assert-Exit 'Resource compilation'
$unsigned = Join-Path $build 'unsigned.apk'
& $Aapt2 link --manifest (Join-Path $PSScriptRoot 'src/main/AndroidManifest.xml') -I $AndroidJar $resources -o $unsigned
Assert-Exit 'Resource linking'
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($item in @(Get-ChildItem -LiteralPath $dex -Filter '*.dex')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $item.FullName, $item.Name) | Out-Null
    }
    $assets = Join-Path $PSScriptRoot 'src/main/assets'
    foreach ($item in @(Get-ChildItem -LiteralPath $assets -Recurse -File)) {
        $name = 'assets/' + $item.FullName.Substring($assets.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $item.FullName, $name) | Out-Null
    }
} finally { $archive.Dispose() }
$output = Join-Path $PSScriptRoot "XiaobuScreenEnhancer-v$version.apk"
Copy-Item -LiteralPath $unsigned -Destination $output -Force
# Use environment password options so the password does not appear in process arguments.
$previousStore = $env:XIAOBU_BUILD_STORE_PASSWORD
$previousKey = $env:XIAOBU_BUILD_KEY_PASSWORD
try {
    $env:XIAOBU_BUILD_STORE_PASSWORD = $StorePass
    $env:XIAOBU_BUILD_KEY_PASSWORD = $KeyPass
    & jarsigner '-J-Duser.language=en' -keystore $Keystore -storepass:env XIAOBU_BUILD_STORE_PASSWORD -keypass:env XIAOBU_BUILD_KEY_PASSWORD -sigalg SHA256withRSA -digestalg SHA-256 $output $KeyAlias
    Assert-Exit 'APK signing'
} finally {
    $env:XIAOBU_BUILD_STORE_PASSWORD = $previousStore
    $env:XIAOBU_BUILD_KEY_PASSWORD = $previousKey
}
& jarsigner '-J-Duser.language=en' -verify $output
Assert-Exit 'APK signature verification'
Write-Output "APK: $output"
Get-FileHash -Algorithm SHA256 -LiteralPath $output
