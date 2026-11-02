# Emulator demo: installs the debug build on the haier_tv emulator, plays a steps file, records the screen.
# Usage: powershell -ExecutionPolicy Bypass -File tools/demo.ps1 demo/<name>.txt
# Steps: key <name> [holdMs] | wait <seconds> | shot <name> | shell <command>; blank lines and # comments are skipped.
param([Parameter(Mandatory = $true)][string]$Steps)
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot
$pkg = 'io.github.lettuceketchup.tvqs'
$out = Join-Path $root ('demo\out\' + [IO.Path]::GetFileNameWithoutExtension($Steps))
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $out 'log.txt'
Set-Content $log '' -Encoding utf8

function Log($text) { Add-Content $log $text -Encoding utf8; Write-Host $text }

# Never `adb -e`: that's whichever TCP/IP device is connected, i.e. the TV.
function Adb {
    $o = & adb.exe -s emulator-5554 @args
    if ($LASTEXITCODE -ne 0) { throw "adb $args failed (exit $LASTEXITCODE)" }
    $o
}

Adb wait-for-device
if ((Adb shell getprop ro.kernel.qemu) -ne '1') { throw 'emulator-5554 is not an emulator' }
while ((Adb shell getprop sys.boot_completed) -ne '1') { Start-Sleep 2 }

Adb install -r (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
# Clearing drops saved presets and also unbinds the service, so it comes before the grant and the enable.
Adb shell pm clear $pkg
Adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
Adb shell settings put secure enabled_accessibility_services "$pkg/.QuickService"
Adb shell settings put secure accessibility_enabled 1

# Fixed start values, so every run starts the same.
$start = [ordered]@{
    picture_backlight = 100; picture_color_temperature = 2; picture_mode = 7
    sound_style = 1; hdmi_system_audio_control_enabled = 0; power_picture_off = 1
}
foreach ($k in $start.Keys) { Adb shell settings put global $k $start[$k] }

Adb shell input keyevent WAKEUP
Adb shell input keyevent HOME
# Not through Adb: the emulator sometimes fails to clear the log, and older lines in log.txt are harmless.
& adb.exe -s emulator-5554 logcat -c
# screenrecord stops by itself at 180 s; keep demos shorter.
$rec = Start-Process adb.exe -ArgumentList '-s', 'emulator-5554', 'shell', 'screenrecord', '--bit-rate', '8000000', '/sdcard/demo.mp4' -PassThru -NoNewWindow
Start-Sleep 2

foreach ($line in Get-Content $Steps) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith('#')) { continue }
    Log "> $line"
    $cmd, $rest = $line -split '\s+', 2
    $o = switch ($cmd) {
        'key' {
            $key, $hold = $rest -split '\s+'
            $a = @('shell', 'am', 'broadcast', '-a', "$pkg.DEBUG_KEY", '--es', 'key', $key)
            if ($hold) { $a += '--el', 'hold', $hold }
            Adb @a
        }
        'wait' { Start-Sleep -Milliseconds ([double]$rest * 1000) }
        # screencap to a file and pull it: PowerShell 5.1 corrupts binary exec-out output in a redirect.
        'shot' { Adb shell screencap -p /sdcard/shot.png; Adb pull /sdcard/shot.png (Join-Path $out "$rest.png") }
        'shell' { Adb shell $rest }
        default { throw "unknown step: $line" }
    }
    if ($o) { Log ($o -join "`r`n") }
}

Adb shell pkill -INT screenrecord
$rec.WaitForExit(5000) | Out-Null
Start-Sleep 2
Adb pull /sdcard/demo.mp4 (Join-Path $out 'demo.mp4')
Log '--- logcat -s QuickService'
Log ((Adb logcat -d -s QuickService) -join "`r`n")
Write-Host "Output: $out"
