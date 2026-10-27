#!/usr/bin/env bash
# Installs Himalayan Dawn on an Android TV over the network and makes it the home screen and screensaver.
#   ./setup-tv.sh 192.168.1.50 HimalayanDawnTV.apk
#   ./setup-tv.sh 192.168.1.50 --undo        # puts the TV's own launcher and screensaver back
# Needs adb (Android platform-tools) on PATH, and Network debugging switched on in the TV's Developer options.
set -euo pipefail

tv="${1:?usage: setup-tv.sh <tv-ip> [apk | --undo]}"
arg="${2:-}"
[[ "$tv" == *:* ]] || tv="$tv:5555"
stock_launchers=(com.google.android.tvlauncher com.google.android.apps.tv.launcherx)
ambient=com.google.android.backdrop
dream=app.himalayandawn.tv/.DawnDreamService
a() { adb -s "$tv" "$@"; }

echo "Connecting to $tv. If the TV asks 'Allow debugging?', tick Always allow and press OK."
adb connect "$tv"
[[ "$(a get-state 2>/dev/null)" == device ]] || { echo "Can't reach the TV at $tv, or it hasn't allowed this computer yet." >&2; exit 1; }

if [[ "$arg" == --undo ]]; then
  for p in "${stock_launchers[@]}" "$ambient"; do a shell pm enable "$p" 2>/dev/null || true; done
  a shell settings put secure screensaver_components "$ambient/.Backdrop"
  a shell input keyevent KEYCODE_HOME
  echo "Done. The TV is back to its own launcher. Remove Himalayan Dawn with: adb uninstall app.himalayandawn.tv"
  exit 0
fi

if [[ -n "$arg" ]]; then echo "Installing $arg ..."; a install -r "$arg"; fi
a shell pm path app.himalayandawn.tv | grep -q package: || { echo "Himalayan Dawn is not installed. Pass the APK path." >&2; exit 1; }

# The stock launcher outranks any installed app, so it has to be switched off (not removed) for ours to be Home.
for p in "${stock_launchers[@]}" "$ambient"; do
  if a shell pm path "$p" | grep -q package:; then a shell pm disable-user --user 0 "$p"; fi
done
a shell settings put secure screensaver_components "$dream"
a shell settings put secure screensaver_enabled 1
a shell input keyevent KEYCODE_HOME
echo "Done. Press Home on the TV remote. To undo: ./setup-tv.sh ${1} --undo"
