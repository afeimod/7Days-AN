param(
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaRoot = $env:JAVA_HOME,
    [switch]$Debuggable,
    [string]$PackageId = 'org.island.nativevalidation'
)
$ErrorActionPreference = 'Stop'
if (-not $JavaRoot -or -not (Test-Path -LiteralPath "$JavaRoot/bin/javac.exe")) { throw 'Specify a JDK 17 installation using -JavaRoot.' }
$project = $PSScriptRoot
$build = Join-Path $project 'manual-build'
$buildTools = Join-Path $SdkRoot 'build-tools/35.0.1'
$androidJar = Join-Path $SdkRoot 'platforms/android-34/android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Install Android SDK platform 34.' }
if (-not (Test-Path -LiteralPath "$buildTools/aapt.exe")) { throw 'Install Android SDK build tools 35.0.1.' }
New-Item -ItemType Directory -Path $build,"$build/classes","$build/dex" -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$project/app/src/main/java" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& "$JavaRoot/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d "$build/classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
& "$JavaRoot/bin/jar.exe" cf "$build/classes.jar" -C "$build/classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed.' }
& "$JavaRoot/bin/java.exe" -cp "$buildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output "$build/dex" "$build/classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'D8 failed.' }
$manifest = "$project/app/src/main/AndroidManifest.xml"
if ($Debuggable -or $PackageId -ne 'org.island.nativevalidation') {
    $debugManifestDirectory = Join-Path $build 'debug-manifest'
    New-Item -ItemType Directory -Path $debugManifestDirectory -Force | Out-Null
    $debugManifest = Join-Path $debugManifestDirectory 'AndroidManifest.xml'
    $manifestText = [System.IO.File]::ReadAllText($manifest).Replace('package="org.island.nativevalidation"', "package=`"$PackageId`"").Replace('android:name=".MainActivity"','android:name="org.island.nativevalidation.MainActivity"')
    if ($Debuggable) { $manifestText = $manifestText.Replace('<application ', '<application android:debuggable="true" ') }
    [System.IO.File]::WriteAllText($debugManifest, $manifestText, [System.Text.UTF8Encoding]::new($false))
    $manifest = $debugManifest
}
& "$buildTools/aapt.exe" package -f -M $manifest -I $androidJar -S "$project/app/src/main/res" -A "$project/app/src/main/assets" -F "$build/unsigned.apk" --min-sdk-version 26 --target-sdk-version 34 --version-code 5 --version-name '0.5.0'
if ($LASTEXITCODE -ne 0) { throw 'aapt package failed.' }
Push-Location "$build/dex"
try {
    & "$buildTools/aapt.exe" add "$build/unsigned.apk" classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'aapt add failed.' }
} finally { Pop-Location }
& "$buildTools/zipalign.exe" -f -p 4 "$build/unsigned.apk" "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
$keyFile = Join-Path $build 'validation-debug.keystore'
if (-not (Test-Path -LiteralPath $keyFile)) {
    & "$JavaRoot/bin/keytool.exe" -genkeypair -keystore $keyFile -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=AndroidDebug,O=IslandNativeValidation,C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Debug key generation failed.' }
}
$output = Join-Path $build 'island-native-validation.apk'
& "$JavaRoot/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks $keyFile --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $output "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& "$JavaRoot/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" verify --verbose $output
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Format-List
