#!/bin/sh
# Puts otto's commands on PATH, from this checkout: /usr/local/bin (every shell an agent starts has it), or the
# directory given. Build first: npm install && npm run build.
set -e
root=$(cd "$(dirname "$0")/.." && pwd)
bin=${1:-/usr/local/bin}
sudo=; [ -w "$bin" ] || sudo=sudo
chmod +x "$root/cli/dist/main.js" "$root/mcp/dist/main.js" "$root/box/relay/dist/main.js"
$sudo ln -sf "$root/cli/dist/main.js" "$bin/otto"
$sudo ln -sf "$root/mcp/dist/main.js" "$bin/otto-eyes"
$sudo ln -sf "$root/box/relay/dist/main.js" "$bin/otto-relay"
echo "linked otto, otto-eyes and otto-relay into $bin"
