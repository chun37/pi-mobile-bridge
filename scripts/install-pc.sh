#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/build" "$HOME/.local/bin" "$HOME/.pi/agent/extensions"
(cd "$root/relay" && go build -trimpath -o "$root/build/pibridge" .)
link="$HOME/.local/bin/pibridge"
if [[ -e "$link" || -L "$link" ]]; then
  resolved=$(readlink -f "$link")
  if [[ "$resolved" != "$root/build/pibridge" && "$resolved" != "$root/pibridge" ]]; then
    printf 'Refusing to replace an unrelated executable: %s\n' "$link" >&2
    exit 1
  fi
fi
ln -sfn "$root/build/pibridge" "$link"
ext="$HOME/.pi/agent/extensions/pi-bridge"
if [[ -e "$ext" || -L "$ext" ]]; then
  [[ "$(readlink -f "$ext")" == "$root/extension" ]] || { printf 'Unrelated extension exists: %s\n' "$ext" >&2; exit 1; }
fi
ln -sfn "$root/extension" "$ext"
if [[ ! -e "$HOME/.pi/agent/extensions/herdr-agent-state.ts" ]]; then
  herdr integration install pi
fi
printf 'Installed relay: %s\nInstalled extension: %s\nExisting Pi TUI sessions need /reload; new sessions load automatically.\n' "$link" "$ext"
