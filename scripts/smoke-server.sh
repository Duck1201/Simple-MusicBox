#!/usr/bin/env bash
# Smoke test: sobe o servidor dedicado de desenvolvimento com o mod, espera o
# mod inicializar e o motor de áudio preparar as libs, e desliga. Falha se o
# servidor não subir a tempo, crashar ou o mod logar erro.
#
#   scripts/smoke-server.sh [timeout_em_segundos]
set -euo pipefail

cd "$(dirname "$0")/.."
TIMEOUT="${1:-600}"
LOG="$(mktemp -d)/server.log"
FIFO="$(dirname "$LOG")/stdin"

mkdir -p run/server
echo "eula=true" > run/server/eula.txt
mkfifo "$FIFO"

./gradlew runServer --console=plain < "$FIFO" > "$LOG" 2>&1 &
SERVER_PID=$!
exec 3> "$FIFO" # mantém o stdin do servidor aberto

stop_server() {
	echo "stop" >&3 2>/dev/null || true
	for _ in $(seq 1 60); do
		kill -0 "$SERVER_PID" 2>/dev/null || return 0
		sleep 1
	done
	kill "$SERVER_PID" 2>/dev/null || true
}

fail() {
	echo "::error::$1"
	stop_server
	echo "----- server.log (fim) -----"
	tail -n 80 "$LOG"
	exit 1
}

wait_for() {
	local pattern="$1" description="$2"
	local deadline=$((SECONDS + TIMEOUT))
	until grep -qE "$pattern" "$LOG"; do
		kill -0 "$SERVER_PID" 2>/dev/null || fail "o servidor terminou antes de: $description"
		((SECONDS < deadline)) || fail "timeout esperando: $description"
		sleep 2
	done
	echo "ok: $description"
}

wait_for 'Simple MusicBox initialized' "mod inicializado"
wait_for 'Done \([0-9.]+s\)!' "servidor no ar"
# A thread de auto-update extrai as libs embutidas e consulta o Maven do Lavalink;
# sem internet ela loga um aviso e segue com a versão local — os dois casos valem.
wait_for 'youtube-source .*(versão mais recente|baixado)|youtube-source update check failed' \
	"motor de áudio preparado"

if grep -qE '\[[^]]*/ERROR\] \(simple_musicbox\)' "$LOG"; then
	fail "o mod logou erro durante a inicialização"
fi
if ! ls run/server/config/simple_musicbox/libs/v2-*.jar > /dev/null 2>&1; then
	fail "as libs do youtube-source não foram extraídas"
fi

stop_server
wait "$SERVER_PID" || true
echo "Smoke test do servidor OK"
