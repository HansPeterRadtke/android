Write-Host "=== INSTALL APK ==="
& adb install -r "C:\dev\general\upload\app-debug.apk"

Write-Host "=== FORCE-STOP OLD APP INSTANCE ==="
& adb shell am force-stop myapp.app

Write-Host "=== CLEAR LOGCAT BUFFER ==="
& adb logcat -c

Write-Host "=== START APP VIA MONKEY ==="
& adb shell monkey -p myapp.app -c android.intent.category.LAUNCHER 1

Start-Sleep -Seconds 3

Write-Host "=== GET APP PID ==="
$appPid = (& adb shell pidof -s myapp.app).Trim()
Write-Host "myapp.app PID: $appPid"

if (-not $appPid) {
    Write-Host "ERROR: could not get app PID, falling back to full logcat."
    & adb logcat
    exit 1
}

Write-Host "=== ATTACH LOGCAT (ONLY THIS APP, ALL TAGS) ==="
& adb logcat --pid=$appPid
