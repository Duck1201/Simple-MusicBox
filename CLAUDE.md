# Simple MusicBox

Mod Fabric (Minecraft **1.21.1**, Java 21, Yarn mappings) que toca músicas de links do YouTube
e de faixas do Spotify em jukeboxes, com áudio posicional para todos os jogadores próximos.
Publicado no Modrinth (`simple-musicbox`, id `eYOBjKLB`). Visão de usuário no `README.md`.

**Estado atual e pendências: `docs/pendencias.md`. Leia antes de lançar qualquer versão.**

Converse com o usuário em português do Brasil. Código, commits, nomes e strings de erro
seguem no idioma em que já estão: comentários em português, mensagens de commit e logs em
inglês.

## Comandos

```bash
./gradlew build -Pstrict             # o que o CI roda: -Werror, Checkstyle, testes JUnit; copia o jar para dist/
python3 scripts/check-resources.py   # JSON válido, paridade en_us/pt_br, chaves usadas no código, mixins/entrypoints
scripts/smoke-server.sh              # sobe o servidor dedicado (run/server) e confere a inicialização
scripts/verify-jar.sh dist/simple-musicbox-<v>.jar <v>
./gradlew runServer                  # servidor de dev em run/server
./gradlew runClient -PquickplayWorld="Novo mundo"   # cliente abrindo direto um mundo (pasta run/)
```

Rode `build -Pstrict` + `check-resources.py` antes de qualquer commit; o CI barra o resto.

## Arquitetura (o não-óbvio)

- **Dois source sets** (`splitEnvironmentSourceSets`): `src/main` (comum/servidor) e
  `src/client` (só cliente: GUI, reprodução, mixin de som).
- **Motor de áudio isolado.** LavaPlayer + youtube-source **não** são classes do jar. Os jars
  vão como recursos `engine-libs/*.jarx`, são extraídos para `config/simple_musicbox/libs/`
  e carregados num classloader próprio (`audio/EngineClassLoader`, `audio/EngineLoader`).
  Só `engine/LavaPlayerAudioEngine` enxerga o LavaPlayer; a fronteira é a interface
  `audio/AudioEngine`, e só tipos do JDK e de `com.duck.*` cruzam. Isso permite o
  **auto-update do youtube-source** em runtime, sem recompilar.
- **Versão do youtube-source** (`gradle.properties`): hoje é um *snapshot* embutido com o
  rótulo `youtube_source_label=1.18.2.1`. O `EngineLoader.versionKey` compara versões pelos
  dígitos do nome, então um nome com hash ordenaria errado. O rótulo garante > 1.18.2
  (quebrada) e < 1.18.3. Ao voltar para um release, deixe o rótulo igual à versão.
- **Spotify:** `audio/SpotifyResolver` lê as meta tags da página pública da faixa (título,
  artista, duração; sem credenciais) → busca `ytsearch:` → `audio/DurationMatch` escolhe,
  entre os 5 primeiros, o de duração mais próxima (tolerância max(10 s, 10%)).
- **Biblioteca de faixas:** `audio/TrackCache`, arquivos `<videoId>.smb` (`audio/SmbFileFormat`,
  formato SMB2; o SMB1 da 1.0 continua legível). No servidor dedicado fica em
  `config/simple_musicbox/cache`; no single-player/LAN, uma por mundo, em
  `<mundo>/simple_musicbox/cache`. A troca acontece em `SERVER_STARTING` (`SimpleMusicBox`).
  Cliente tem cache próprio de transporte em `<gameDir>/simple_musicbox/cache`.
- **Reprodução:** `playback/JukeboxSessionManager` guarda as sessões **em memória**. A jukebox
  vanilla toca um `jukebox_song` silencioso de 3600 s (partículas, allays, comparador); o
  áudio real vai por pacotes próprios (`net/`) e é tocado no cliente.
- **Discos** (`item/Discs`):
  - Disco Virgem: item próprio `blank_disc`, não toca.
  - Disco físico: `music_disc_custom` com o componente `TRACK`.
  - Disco **virtual**: `TRACK` + componente `VIRTUAL`, criado pela GUI e pela fila. O mixin
    (`JukeboxBlockEntityMixin`) o faz sumir em `decreaseStack` e `dropRecord`, para não
    vazar como item.
  - Disco físico nunca é sobrescrito: a GUI o devolve ao jogador.
- **GUI:** `playback/JukeboxGuiServer` (ações) + `client/gui/JukeboxScreen`. Abre com
  clique-direito numa jukebox vazia (sem disco na mão), shift + clique-direito em qualquer
  jukebox, ou `/player`.

## Armadilhas já encontradas

- **Nunca mexa no mundo dentro de eventos de carregamento de chunk** (ex.:
  `ServerBlockEntityEvents.BLOCK_ENTITY_LOAD`). Na thread do servidor, `server.execute(...)`
  roda **na hora**, não no próximo tick. Chamar `getBlockEntity` ali travou o servidor
  (deadlock no carregamento do chunk). Anote a posição e processe no tick
  (`PENDING_RESUME` em `JukeboxSessionManager`).
- A jukebox vanilla só tem ticker (e só conta `ticksSinceSongStarted`) com
  `HAS_RECORD=true` no blockstate.
- Upload no Modrinth não é idempotente. O workflow de release desliga retries do mc-publish
  e pula a publicação se a versão já existe (já houve uma 1.0.1 duplicada).
- `config.json` existente mantém os valores salvos: mudar um padrão em `ModConfig` só vale
  para instalações novas. Documente isso ao mudar padrões.
- Checkstyle (`config/checkstyle/checkstyle.xml`): tabs, sem import com `*`, linhas ≤ 120,
  nada de `System.out` (use `SimpleMusicBox.LOGGER`). Toda chave de tradução nova vai em
  `en_us.json` **e** `pt_br.json`.

## Testar no jogo sem a tela do usuário

- Cliente headless: `Xvfb :99` + `DISPLAY=:99 ./gradlew runClient -PquickplayWorld=...`,
  dirigido com `xdotool` (chat: tecla `t`, digitar, `Return`) e capturas com
  `import -window root`. Para ter comandos: Esc → Open to LAN → Allow Commands ON.
- Verificações objetivas por comando: `/clear @s <item> 0` conta itens sem apagar;
  `/data get block x y z` mostra o disco da jukebox.
- O cliente usa a pasta `run/` (o mesmo `config/` do jogo do usuário): faça backup antes
  de mudar a config de teste e restaure depois. Não rode testes enquanto o jogo do usuário
  estiver aberto no mesmo mundo (`session.lock`).
- Para encerrar processos, use o PID (`ps` + `kill`). `pkill -f <padrão>` já matou o
  próprio shell, que contém o padrão na linha de comando.

## Lançamento

1. Confira `docs/pendencias.md`: **só lance com o aval do usuário.**
2. `git tag -a vX.Y.Z -m "<changelog em inglês>" && git push origin vX.Y.Z`. O
   `release.yml` roda o CI inteiro, valida a tag (semver, anotada, na `main`), publica no
   GitHub Releases e no Modrinth (secret `MODRINTH_TOKEN`).
3. Atualize a página do Modrinth com `modrinth/description.md` (PATCH em
   `/v2/project/eYOBjKLB`, campo `body`). O token local fica em `~/.modrinth_token`: nunca
   imprima nem copie o valor.
4. Regras do Modrinth: o projeto declara conteúdo gerado por IA (código, assets e texto); a
   descrição precisa continuar honesta e em inglês, com a seção "Network access" atualizada.
