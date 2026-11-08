#!/bin/sh
# Puts otto's commands on this user's PATH (~/.local/bin), from this checkout. Build first: npm install && npm run build.
set -e
root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$HOME/.local/bin"
ln -sf "$root/cli/dist/main.js" "$HOME/.local/bin/otto"
ln -sf "$root/mcp/dist/main.js" "$HOME/.local/bin/otto-eyes"
ln -sf "$root/box/relay/dist/main.js" "$HOME/.local/bin/otto-relay"
chmod +x "$root/cli/dist/main.js" "$root/mcp/dist/main.js" "$root/box/relay/dist/main.js"
echo "linked otto, otto-eyes and otto-relay into $HOME/.local/bin"
