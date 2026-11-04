$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$androidJar = "$sdk\platforms\android-37.0\android.jar"
$objects = Join-Path 'build' ('objects-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $objects 'classes'
$dex = Join-Path $objects 'dex'
$stubs = Join-Path $objects 'stubs'
New-Item -ItemType Directory -Force build,$classes,$dex,$stubs | Out-Null
function Check { if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" } }
& "$bt/aapt2.exe" compile --dir app/src/main/res -o build/resources.zip
Check
& "$bt/aapt2.exe" link -o build/base.apk -I $androidJar --manifest app/src/main/AndroidManifest.xml -A app/src/main/assets build/resources.zip
Check
& javac -encoding UTF-8 -source 8 -target 8 -d $stubs (Get-ChildItem app/src/compileOnly -Recurse -Filter *.java).FullName
Check
& javac -encoding UTF-8 -source 8 -target 8 -cp "$androidJar;lib/xposed-api-82.jar;$stubs" -d $classes (Get-ChildItem app/src/main/java -Recurse -Filter *.java).FullName
Check
& jar cf build/classes.jar -C $classes .
Check
& "$bt/d8.bat" --lib $androidJar --classpath lib/xposed-api-82.jar --classpath $stubs --min-api 26 --output $dex build/classes.jar
Check
Copy-Item build/base.apk build/unsigned.apk -Force
& jar uf build/unsigned.apk -C $dex classes.dex
Check
& "$bt/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
Check
if (!(Test-Path debug.jks)) {
    & keytool -genkeypair -keystore debug.jks -storepass android -keypass android -alias androiddebugkey -dname 'CN=Xiaoxi BYOK Development' -keyalg RSA -validity 10000
    Check
}
& "$bt/apksigner.bat" sign --ks debug.jks --ks-pass pass:android --out build/xiaoxi-byok.apk build/aligned.apk
Check
& "$bt/apksigner.bat" verify --verbose build/xiaoxi-byok.apk
Check
