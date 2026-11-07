param(
    [string]$SigningKey = "$env:USERPROFILE\.android\debug.keystore"
)

$ErrorActionPreference = "Stop"

# --- Locate the Android SDK (env override, then default install) -----------
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) {
    throw "Android SDK not found. Set ANDROID_HOME or install the SDK."
}

$btDir = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $btDir) { throw "No build-tools installed in $sdk\build-tools" }
$bt = $btDir.FullName

$platDir = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [int](($_.Name -replace '^android-', '') -split '\.')[0] } -Descending |
    Select-Object -First 1
if (-not $platDir) { throw "No Android platform installed in $sdk\platforms" }
$jar = Join-Path $platDir.FullName "android.jar"

# --- Locate a JDK (env override, Android Studio JBR, then common installs) --
$jbr = $env:JAVA_HOME
$candidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-17",
    "$env:ProgramFiles\Java\jdk-17"
)
if (-not $jbr -or -not (Test-Path (Join-Path $jbr "bin\javac.exe"))) {
    $jbr = $candidates | Where-Object { Test-Path (Join-Path $_ "bin\javac.exe") } | Select-Object -First 1
}
if (-not $jbr) { throw "No JDK found. Set JAVA_HOME (a JDK with javac)." }
$env:JAVA_HOME = $jbr

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src  = Join-Path $root "app\src\main"
$out  = Join-Path $root "build"
$cls  = Join-Path $out "classes"
$dex  = Join-Path $out "dex"

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $cls, $dex | Out-Null

Write-Host "[1/5] aapt2 link"
& "$bt\aapt2.exe" link -o (Join-Path $out "base.apk") `
    --manifest (Join-Path $src "AndroidManifest.xml") -I $jar
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Write-Host "[2/5] javac"
$javaFiles = Get-ChildItem (Join-Path $src "java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& "$jbr\bin\javac.exe" -source 8 -target 8 -nowarn -classpath $jar -d $cls $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/5] d8"
$classFiles = Get-ChildItem $cls -Recurse -Filter *.class |
    ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 19 --lib $jar --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/5] package + zipalign"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $out "base.apk"), "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex "classes.dex"), "classes.dex") | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 (Join-Path $out "base.apk") (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[5/5] sign (v1+v2, minSdk 19)"
& "$bt\apksigner.bat" sign `
    --ks $SigningKey --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true `
    --min-sdk-version 19 `
    --out (Join-Path $root "FireDeck.apk") (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "Built: $(Join-Path $root 'FireDeck.apk')"
