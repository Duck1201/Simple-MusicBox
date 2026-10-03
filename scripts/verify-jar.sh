#!/usr/bin/env bash
# Confere o jar de release antes de publicar.
#
#   scripts/verify-jar.sh <jar> [versão esperada]
set -euo pipefail

JAR="$1"
EXPECTED_VERSION="${2:-}"
MAX_BYTES=$((100 * 1024 * 1024))
status=0

fail() {
	echo "::error::$1"
	status=1
}

[[ -f "$JAR" ]] || { echo "::error::jar não encontrado: $JAR"; exit 1; }
entries="$(unzip -Z1 "$JAR")"

has() {
	grep -qxE "$1" <<< "$entries" || fail "$2"
}

version="$(unzip -p "$JAR" fabric.mod.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"
echo "versão no fabric.mod.json: $version"
# shellcheck disable=SC2016 # procura o placeholder literal ${version}
[[ "$version" != *'${'* ]] || fail "versão não foi expandida no fabric.mod.json"
if [[ -n "$EXPECTED_VERSION" && "$version" != "$EXPECTED_VERSION" ]]; then
	fail "versão do jar ($version) diferente da esperada ($EXPECTED_VERSION)"
fi

has 'com/duck/simplemusicbox/SimpleMusicBox\.class' "entrypoint principal ausente"
has 'com/duck/simplemusicbox/client/SimpleMusicBoxClient\.class' "classes do cliente ausentes"
has 'com/duck/simplemusicbox/engine/LavaPlayerAudioEngine\.class' "motor de áudio ausente"
has 'io/github/jaredmdobson/concentus/OpusDecoder\.class' "Concentus não foi embutido (shadow)"
has 'engine-libs/v2-.+\.jarx' "youtube-source (v2) não embutido"
has 'engine-libs/common-.+\.jarx' "youtube-source (common) não embutido"
has 'engine-libs/lavaplayer-[0-9].+\.jarx' "LavaPlayer não embutido"
# O LavaPlayer não pode vazar como classe solta: ele vive só no classloader do motor
if grep -qE '^com/sedmelluq/' <<< "$entries"; then
	fail "classes do LavaPlayer soltas no jar (deveriam estar só em engine-libs/)"
fi

size="$(stat -c %s "$JAR")"
echo "tamanho: $((size / 1024 / 1024)) MB"
((size < MAX_BYTES)) || fail "jar com mais de 100 MB"

((status == 0)) && echo "Jar OK"
exit "$status"
