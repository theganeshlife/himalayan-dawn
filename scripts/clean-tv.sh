#!/usr/bin/env bash
# Clears every app's cache on an Android TV and closes background apps, then reports what it freed.
# Apps, sign-ins and settings are not touched. Once a month is plenty.
#   ./clean-tv.sh 192.168.1.50
# Needs adb (Android platform-tools) on PATH, and USB/Network debugging switched on in the TV's Developer options.
set -euo pipefail

tv="${1:?usage: clean-tv.sh <tv-ip>}"
[[ "$tv" == *:* ]] || tv="$tv:5555"
a() { adb -s "$tv" "$@"; }

# Prints "<free /data KB> <MemAvailable KB>"
snapshot() { a shell "df -k /data | tail -1 | awk '{print \$4}'; grep MemAvailable /proc/meminfo | awk '{print \$2}'" | tr -d '\r' | paste -sd' ' -; }

echo "Connecting to $tv. If the TV asks 'Allow debugging?', tick Always allow and press OK."
adb connect "$tv"
[[ "$(a get-state 2>/dev/null)" == device ]] || { echo "Can't reach the TV at $tv, or it hasn't allowed this computer yet." >&2; exit 1; }

read -r free_before ram_before <<< "$(snapshot)"
a shell "pm trim-caches 999G; am kill-all" > /dev/null
sleep 2
read -r free_after ram_after <<< "$(snapshot)"

freed=$(( (free_after - free_before) / 1024 )); (( freed < 0 )) && freed=0
printf 'Freed %d MB of storage (%.1f GB free). Free memory %d -> %d MB.\n' \
  "$freed" "$(echo "$free_after / 1048576" | bc -l)" $(( ram_before / 1024 )) $(( ram_after / 1024 ))
