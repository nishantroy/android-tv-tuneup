#!/usr/bin/env bash
# Switch an Android TV back to its stock home screen.
#
#   scripts/remove-launcher.sh <tv-address> [--keep-app]
#
#   --keep-app  leave the launcher installed (and its layout saved), just stop using it as Home
#
# Provided as-is, as a demonstration. You run this at your own risk and are responsible for
# what it does to your TV. See docs/safety.md.
set -euo pipefail
cd "$(dirname "$0")/.."

tv="" keep=0
for arg in "$@"; do
  case "$arg" in
    --keep-app) keep=1 ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) tv="$arg" ;;
  esac
done
[[ -n "$tv" ]] || { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

app=local.tvhome
stock=com.google.android.tvlauncher

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first." >&2; exit 1; }
adb connect "$tv" >/dev/null 2>&1 || true
[[ "$(adb -s "$tv" get-state 2>/dev/null)" == "device" ]] || { echo "Can't reach $tv over adb." >&2; exit 1; }
sh() { adb -s "$tv" shell "$@" </dev/null | tr -d '\r'; }

# The stock home needs its helpers back before it is switched on.
scripts/debloat.sh "$tv" --restore --yes debloat/stock-home.txt
sh pm enable $stock
[[ $keep -eq 1 ]] || adb -s "$tv" uninstall $app
sh input keyevent KEYCODE_HOME
home="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
[[ "$home" == "$stock/"* ]] || { echo "Home is $home, not the stock home." >&2; exit 1; }
echo "Done: Home is the stock Android TV home again."
