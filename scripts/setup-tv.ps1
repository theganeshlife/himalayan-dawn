# Installs Himalayan Dawn on an Android TV over the network and makes it the home screen and screensaver.
#   .\setup-tv.ps1 -Tv 192.168.1.50 -Apk ..\HimalayanDawnTV.apk
#   .\setup-tv.ps1 -Tv 192.168.1.50 -Undo        # puts the TV's own launcher and screensaver back
# Needs adb (Android platform-tools) on PATH, and Network debugging switched on in the TV's Developer options.
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [string]$Apk,
    [switch]$Undo
)
$target = if ($Tv -match ':') { $Tv } else { "${Tv}:5555" }
$stockLaunchers = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx')
$ambient = 'com.google.android.backdrop'
$dream = 'app.himalayandawn.tv/.DawnDreamService'

function Adb { & adb -s $target @args }

Write-Host "Connecting to $target. If the TV asks 'Allow debugging?', tick Always allow and press OK."
& adb connect $target | Out-Host
if ((Adb get-state) -ne 'device') { throw "Can't reach the TV at $target, or it hasn't allowed this computer yet." }

if ($Undo) {
    foreach ($p in $stockLaunchers + $ambient) { Adb shell pm enable $p 2>$null | Out-Host }
    Adb shell settings put secure screensaver_components "$ambient/.Backdrop" | Out-Null
    Adb shell input keyevent KEYCODE_HOME
    Write-Host 'Done. The TV is back to its own launcher. Himalayan Dawn is still installed; remove it with: adb uninstall app.himalayandawn.tv'
    return
}

if ($Apk) {
    Write-Host "Installing $Apk ..."
    Adb install -r $Apk | Out-Host
}
if (-not (Adb shell pm path app.himalayandawn.tv)) { throw 'Himalayan Dawn is not installed. Pass -Apk path\to\HimalayanDawnTV.apk' }

# The stock launcher outranks any installed app, so it has to be switched off (not removed) for ours to be Home.
foreach ($p in $stockLaunchers) {
    if (Adb shell pm path $p) { Adb shell pm disable-user --user 0 $p | Out-Host }
}
if (Adb shell pm path $ambient) { Adb shell pm disable-user --user 0 $ambient | Out-Host }
Adb shell settings put secure screensaver_components $dream | Out-Null
Adb shell settings put secure screensaver_enabled 1 | Out-Null
Adb shell input keyevent KEYCODE_HOME
Write-Host 'Done. Press Home on the TV remote. To undo: .\setup-tv.ps1 -Tv' $Tv '-Undo'
