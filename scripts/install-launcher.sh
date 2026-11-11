#!/usr/bin/env bash
# Install the launcher on an Android TV and make it the Home app.
#
#   scripts/install-launcher.sh <tv-address> [--yes]
#
# Builds launcher/build/tvhome.apk first if it isn't there (see launcher/build.sh for what
# that needs). The stock Android TV home is disabled, not removed, so it can be switched back
# on from the TV's own Settings or with scripts/remove-launcher.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

tv="" yes=0
for arg in "$@"; do
  case "$arg" in
    --yes) yes=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) tv="$arg" ;;
  esac
done
[[ -n "$tv" ]] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

app=local.tvhome
stock=com.google.android.tvlauncher
apk=launcher/build/tvhome.apk

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first." >&2; exit 1; }
adb connect "$tv" >/dev/null 2>&1 || true
[[ "$(adb -s "$tv" get-state 2>/dev/null)" == "device" ]] || {
  echo "Can't reach $tv over adb. Is USB debugging on, and did you accept the prompt on the TV?" >&2; exit 1; }
sh() { adb -s "$tv" shell "$@" </dev/null | tr -d '\r'; }

sdk="$(sh getprop ro.build.version.sdk)"
[[ "$sdk" -ge 28 ]] || { echo "This TV runs Android API $sdk; the launcher needs 28 (Android 9) or newer." >&2; exit 1; }

[[ -f $apk ]] || { echo "Building the launcher..."; launcher/build.sh; }
echo "Installing..."
adb -s "$tv" install -r "$apk"
# Lets the launcher read the system Watch Next list for its Continue Watching row.
sh pm grant $app android.permission.READ_TV_LISTINGS || echo "Couldn't grant READ_TV_LISTINGS: Continue Watching will stay empty."
sh cmd package compile -m speed -f $app >/dev/null || true

home_now() { sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }
if [[ "$(home_now)" != "$app/"* ]]; then
  if ! sh pm list packages --user 0 | grep -qx "package:$stock"; then
    echo "Home is currently $(home_now), which this script doesn't know how to step aside." >&2
    echo "The launcher is installed; choose it as Home in the TV's settings if it offers that." >&2
    exit 1
  fi
  if [[ $yes -ne 1 ]]; then
    read -r -p "Disable the stock Android TV home ($stock) and use the new launcher as Home? [y/N] " answer
    [[ "$answer" == [yY]* ]] || { echo "Left Home as it was. The launcher is installed but not in use."; exit 0; }
  fi
  sh pm disable-user --user 0 $stock
  sh am force-stop $stock
  sh cmd package set-home-activity $app/.HomeActivity >/dev/null
fi
sh input keyevent KEYCODE_HOME
[[ "$(home_now)" == "$app/"* ]] || { echo "Home did not switch: it is $(home_now)." >&2; exit 1; }
echo "Done: Home is now the new launcher."
echo "If you no longer want the stock home's helpers: scripts/debloat.sh $tv debloat/stock-home.txt"
