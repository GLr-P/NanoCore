#!/usr/bin/env bash
# Boots a throwaway Paper server with the built plugin and checks that it loads and answers a command.
# With BOT=1 a Mineflayer bot play-tests it (tools/bot/scenario.mjs), the server restarts,
# and the bot checks that everything was saved.
# Usage: tools/smoke-test.sh <plugin.jar> [minecraft-version]
# Used by GitHub Actions; also runs on any Linux box with Java 25, curl, jq (and Node for BOT=1).
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
difficulty=peaceful
view-distance=4
simulation-distance=4
server-port=25599
EOF
mkdir -p plugins
rm -f plugins/NanoCore-*.jar
cp "$JAR" plugins/
if [ "${BOT:-0}" = 1 ]; then mkdir -p plugins/NanoCore/events && cp "$TOOLS/bot/test-events.yml" plugins/NanoCore/events/; fi

# The bot speaks 26.1, so it joins through ViaVersion + ViaBackwards. VaultUnlocked checks the economy hook.
modrinth() {
  local q="loaders=%5B%22paper%22%5D&game_versions=%5B%22$MC%22%5D"
  curl -fsS -A "$UA" "https://api.modrinth.com/v2/project/$1/version?$q" | jq -r '.[0].files[] | select(.primary) | .url' | head -1
}
if [ "${BOT:-0}" = 1 ]; then
  for p in viaversion viabackwards vaultunlocked; do
    ls plugins/ | grep -qi "^$p" || curl -fsS -A "$UA" -L -o "plugins/$p.jar" "$(modrinth $p)"
  done
fi

PID=""
start_server() {
  rm -f in && mkfifo in
  java -Xmx1G -jar paper.jar --nogui < in >> server.log 2>&1 &
  PID=$!
  exec 3>in
  local start_lines
  start_lines=$(grep -c 'Done (' server.log 2>/dev/null || true)
  for _ in $(seq 1 300); do
    [ "$(grep -c 'Done (' server.log 2>/dev/null || true)" -gt "${1:-0}" ] && return 0
    kill -0 $PID 2>/dev/null || { cat server.log; echo "SMOKE FAIL: server exited during startup"; exit 1; }
    sleep 1
  done
  tail -50 server.log; echo "SMOKE FAIL: no startup within 300 s"; exit 1
}
stop_server() {
  echo "stop" >&3
  exec 3>&-
  wait $PID || true
}

: > server.log
start_server 0
echo "nanocore version" >&3
sleep 3

fail=0
if [ "${BOT:-0}" = 1 ]; then
  echo "op NanoBot" >&3
  sleep 1
  node "$TOOLS/bot/scenario.mjs" first || fail=1
  stop_server
  if [ $fail -eq 0 ]; then
    echo "----- restart -----"
    start_server 1
    node "$TOOLS/bot/scenario.mjs" second || fail=1
    stop_server
  fi
  if [ $fail -eq 0 ]; then
    echo "----- restart -----"
    start_server 2
    echo "op NanoBot" >&3   # the second phase de-opped it to test the storehouse lock
    sleep 1
    node "$TOOLS/bot/scenario.mjs" third || fail=1
    stop_server
  fi
else
  stop_server
fi

echo "----- NanoCore lines -----"
grep -iE 'nanocore|vault' server.log || true
echo "--------------------------"

grep -q 'NanoCore .* ready' server.log || { echo "SMOKE FAIL: plugin did not finish enabling"; fail=1; }
grep -q "NanoCore .* on $MC" server.log || { echo "SMOKE FAIL: /nanocore version gave no answer"; fail=1; }
if [ "${BOT:-0}" = 1 ]; then
  grep -q 'Registered as the Vault economy provider' server.log || { echo "SMOKE FAIL: no Vault economy hook"; fail=1; }
fi
if grep -iE 'error occurred while enabling nanocore|could not load .*nanocore|exception.*nanocore|nanocore.*exception|\[NanoCore\].*(SEVERE|ERROR)' server.log; then
  echo "SMOKE FAIL: errors from NanoCore in the log"; fail=1
fi
if grep -nE 'at io\.github\.glrp\.nanocore' server.log; then
  echo "SMOKE FAIL: stack trace through NanoCore code"; fail=1
fi
[ $fail -eq 0 ] && echo "SMOKE PASS: NanoCore loads and passes the checks on Paper $MC"
exit $fail
