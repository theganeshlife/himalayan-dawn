# Clears every app's cache on an Android TV and closes background apps, then reports what it freed.
# Apps, sign-ins and settings are not touched. Once a month is plenty.
#   .\clean-tv.ps1 -Tv 192.168.1.50
# Needs adb (Android platform-tools) on PATH, and USB/Network debugging switched on in the TV's Developer options.
param([Parameter(Mandatory = $true)][string]$Tv)
$target = if ($Tv -match ':') { $Tv } else { "${Tv}:5555" }

function Snapshot {
    $out = & adb -s $target shell "df -k /data | tail -1; grep MemAvailable /proc/meminfo"
    $free = [long](($out | Select-Object -First 1) -split '\s+')[3]
    $ram = [long](($out | Where-Object { $_ -match 'MemAvailable' }) -split '\s+')[1]
    [pscustomobject]@{ FreeMB = [math]::Round($free / 1024); RamMB = [math]::Round($ram / 1024) }
}

Write-Host "Connecting to $target. If the TV asks 'Allow debugging?', tick Always allow and press OK."
& adb connect $target | Out-Host
if ((& adb -s $target get-state) -ne 'device') { throw "Can't reach the TV at $target, or it hasn't allowed this computer yet." }

$before = Snapshot
& adb -s $target shell "pm trim-caches 999G; am kill-all" | Out-Null
Start-Sleep 2
$after = Snapshot

Write-Host ("Freed {0} MB of storage ({1:N1} GB free). Free memory {2} -> {3} MB." -f `
    [math]::Max(0, $after.FreeMB - $before.FreeMB), ($after.FreeMB / 1024), $before.RamMB, $after.RamMB)
