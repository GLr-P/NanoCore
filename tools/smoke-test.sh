#!/usr/bin/env bash
# Boots a throwaway Paper server with the built plugin and checks that it loads and answers a command.
# Usage: tools/smoke-test.sh <plugin.jar> [minecraft-version]
# Used by GitHub Actions; also runs on any Linux box with Java 25, curl and jq.
set -euo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)"
JAR="$(realpath "$1")"
MC="${2:-26.3}"
WORK="${SMOKE_DIR:-$(mktemp -d)}"
UA="NanoCore-CI (github.com/GLr-P/NanoCore)"
cd "$WORK"

if [ ! -f paper.jar ]; then
  URL=$(curl -fsS -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds/latest" | jq -r '.downloads."server:default".url')
  curl -fsS -A "$UA" -o paper.jar "$URL"
fi

echo "eula=true" > eula.txt
cat > server.properties <<EOF
online-mode=false
white-list=false
enforce-whitelist=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
server-port=25599
EOF
mkdir -p plugins
rm -f plugins/NanoCore-*.jar
cp "$JAR" plugins/

# With BOT=1, a Mineflayer bot (tools/bot, supports up to 26.1) joins through ViaVersion + ViaBackwards.
modrinth() {
  local q="loaders=%5B%22paper%22%5D&game_versions=%5B%22$MC%22%5D"
  curl -fsS -A "$UA" "https://api.modrinth.com/v2/project/$1/version?$q" | jq -r '.[0].files[] | select(.primary) | .url' | head -1
}
if [ "${BOT:-0}" = 1 ]; then
  for p in viaversion viabackwards; do
    ls plugins/ | grep -qi "^$p" || curl -fsS -A "$UA" -L -o "plugins/$p.jar" "$(modrinth $p)"
  done
fi

rm -f in && mkfifo in
java -Xmx1G -jar paper.jar --nogui < in > server.log 2>&1 &
PID=$!
exec 3>in

for _ in $(seq 1 300); do
  grep -q 'Done (' server.log && break
  kill -0 $PID 2>/dev/null || { cat server.log; echo "SMOKE FAIL: server exited during startup"; exit 1; }
  sleep 1
done
grep -q 'Done (' server.log || { tail -50 server.log; echo "SMOKE FAIL: no startup within 300 s"; exit 1; }

echo "nanocore version" >&3
sleep 3

bot_fail=0
if [ "${BOT:-0}" = 1 ]; then
  echo "op NanoBot" >&3
  sleep 1
  if [ "${BOT_DIAG:-0}" = 1 ]; then
    BOT_PHYSICS=1 node "$TOOLS/bot/diag-bot.mjs" || true
    BOT_PHYSICS=0 node "$TOOLS/bot/diag-bot.mjs" || true
  fi
  node "$TOOLS/bot/smoke-bot.mjs" 127.0.0.1 25599 || bot_fail=1
fi
echo "stop" >&3
exec 3>&-
wait $PID || true

echo "----- NanoCore lines -----"
grep -i 'nanocore' server.log || true
echo "--------------------------"

fail=$bot_fail
[ $bot_fail -eq 1 ] && { echo "SMOKE FAIL: the bot check failed"; grep -iE 'viaversion|viabackwards|NanoBot' server.log | tail -20; }
grep -q 'NanoCore .* ready' server.log || { echo "SMOKE FAIL: plugin did not finish enabling"; fail=1; }
grep -q "NanoCore .* on $MC" server.log || { echo "SMOKE FAIL: /nanocore version gave no answer"; fail=1; }
if grep -iE 'error occurred while enabling nanocore|could not load .*nanocore|exception.*nanocore|nanocore.*exception' server.log; then
  echo "SMOKE FAIL: errors from NanoCore in the log"; fail=1
fi
[ $fail -eq 0 ] && echo "SMOKE PASS: NanoCore loads and answers on Paper $MC"
exit $fail
