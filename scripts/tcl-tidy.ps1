# Optional, TCL / FFalcon Android TVs only: switches off TCL's ad, promo and telemetry apps.
# Nothing is deleted; -Undo switches everything back on. Check the list before running it on another brand.
#   .\tcl-tidy.ps1 -Tv 192.168.1.50
#   .\tcl-tidy.ps1 -Tv 192.168.1.50 -Undo
param([Parameter(Mandatory = $true)][string]$Tv, [switch]$Undo)
$target = if ($Tv -match ':') { $Tv } else { "${Tv}:5555" }
$packages = @(
    'com.google.android.tvrecommendations', # sponsored recommendation rows
    'com.tcl.waterfall.overseas',           # "TCL Channel" feed
    'com.tcl.overseasappshow',              # app promotions
    'com.tcl.bi',                           # usage analytics
    'com.tcl.messagebox',                   # push messages
    'com.tcl.esticker',                     # in-store demo overlay
    'com.tcl.usercenter',                   # TCL account
    'com.tcl.tv.tclhome_passive',           # leftover TCL home component
    'com.google.android.play.games',
    'com.google.android.videos',
    'com.android.printspooler',
    'com.tcl.versionUpdateApp'              # update nags (also stops TCL firmware updates)
)
& adb connect $target | Out-Null
foreach ($p in $packages) {
    $r = if ($Undo) { & adb -s $target shell pm enable $p } else { & adb -s $target shell pm disable-user --user 0 $p }
    "{0,-40} {1}" -f $p, ($r -join ' ')
}
if (-not $Undo) { & adb -s $target shell pm trim-caches 999G; 'Caches trimmed.' }
