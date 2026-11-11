#!/usr/bin/env bash
# Remove (or restore) preinstalled packages on an Android TV over adb, without root.
#
#   scripts/debloat.sh <tv-address> [--restore] [--dry-run] [--yes] <list-file>...
#
#   <tv-address>  the TV's adb address, e.g. 192.168.1.50:5555
#   <list-file>   one package per line, '#' starts a comment (see debloat/*.txt)
#   --restore     put the listed packages back instead of removing them
#   --dry-run     show what would happen and change nothing
#   --yes         don't ask for confirmation
#
# Packages are removed for the TV's user only (pm uninstall --user 0). The factory copy stays
# on the read-only system partition, so every removal can be undone with --restore.
set -euo pipefail

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

tv="" mode=remove dry=0 yes=0 lists=()
for arg in "$@"; do
  case "$arg" in
    --restore) mode=restore ;;
    --dry-run) dry=1 ;;
    --yes) yes=1 ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $arg" >&2; usage ;;
    *) if [[ -z "$tv" ]]; then tv="$arg"; else lists+=("$arg"); fi ;;
  esac
done
[[ -n "$tv" && ${#lists[@]} -gt 0 ]] || usage

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first." >&2; exit 1; }
adb connect "$tv" >/dev/null 2>&1 || true
[[ "$(adb -s "$tv" get-state 2>/dev/null)" == "device" ]] || {
  echo "Can't reach $tv over adb. Is USB debugging on, and did you accept the prompt on the TV?" >&2; exit 1; }
sh() { adb -s "$tv" shell "$@" </dev/null | tr -d '\r'; }

# Package names from the list files, comments and blanks dropped.
packages=()
for f in "${lists[@]}"; do
  [[ -f "$f" ]] || { echo "No such list file: $f" >&2; exit 1; }
  while IFS= read -r line; do
    name="${line%%#*}"; name="${name//[[:space:]]/}"
    [[ -n "$name" ]] && packages+=("$name")
  done < "$f"
done
[[ ${#packages[@]} -gt 0 ]] || { echo "The list files name no packages."; exit 0; }

installed="$(sh pm list packages --user 0)"
known="$(sh pm list packages -u --user 0)"
state() {  # installed | removed | absent
  if grep -qx "package:$1" <<<"$installed"; then echo installed
  elif grep -qx "package:$1" <<<"$known"; then echo removed
  else echo absent; fi
}

todo=()
echo "On $tv:"
for p in "${packages[@]}"; do
  s="$(state "$p")"
  case "$mode:$s" in
    remove:installed)  echo "  remove    $p"; todo+=("$p") ;;
    remove:removed)    echo "  (already removed)  $p" ;;
    restore:removed)   echo "  restore   $p"; todo+=("$p") ;;
    restore:installed) echo "  (already installed)  $p" ;;
    *:absent)          echo "  (not on this TV)  $p" ;;
  esac
done
[[ ${#todo[@]} -gt 0 ]] || { echo "Nothing to do."; exit 0; }
[[ $dry -eq 1 ]] && { echo "Dry run: nothing changed."; exit 0; }
if [[ $yes -ne 1 ]]; then
  read -r -p "Proceed with ${#todo[@]} package(s)? [y/N] " answer
  [[ "$answer" == [yY]* ]] || { echo "Cancelled."; exit 0; }
fi

failed=0
for p in "${todo[@]}"; do
  if [[ $mode == remove ]]; then out="$(sh pm uninstall --user 0 "$p" 2>&1 || true)"
  else out="$(sh cmd package install-existing "$p" 2>&1 || true)"; fi
  echo "  $p: $out"
  [[ "$out" == *Success* || "$out" == *"installed for user"* ]] || failed=$((failed + 1))
  # Stop at once if Android's core process has gone: something essential was touched.
  [[ -n "$(sh pidof system_server)" ]] || {
    echo "system_server is not running after changing $p. Stopping." >&2
    echo "Restore it with: adb -s $tv shell cmd package install-existing $p" >&2; exit 1; }
done
[[ $failed -eq 0 ]] || { echo "$failed package(s) did not change; see the messages above." >&2; exit 1; }
if [[ $mode == remove ]]; then echo "Done. Undo with: $0 $tv --restore ${lists[*]}"; else echo "Done."; fi
