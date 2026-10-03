# Estado do projeto e pendências

Atualizado em **03/10/2026**, ao fim da sessão que preparou a 1.1.0.

## Onde paramos

| Item | Estado |
|---|---|
| Modrinth | Projeto **em análise da moderação** (`processing`). Versões publicadas: 1.0.0 e 1.0.1 |
| `main` | **1.1.0 pronta, não lançada.** CI verde. A tag `v1.1.0` ainda não existe |
| Página do Modrinth | Mostra o texto da 1.0.1. A versão nova já está em `modrinth/description.md`, mas **não foi enviada** |
| GitHub Releases | v1.0.0 e v1.0.1 (pelo CI), mais um "Simple MusicBox V1.0.0" antigo, com a tag `Release`, criado à mão em 13/07 |

**Decisão do usuário:** a 1.1.0 só é lançada **depois que a moderação do Modrinth aprovar**
o projeto.

## O que a 1.1.0 traz

- **Spotify:** links de faixa (`open.spotify.com/track/…`, `spotify:track:…`). O mod busca a
  música no YouTube pela duração. Álbuns e playlists são recusados. O disco mostra
  `Spotify: <id>`.
- **Disco Virgem:**
  - item próprio que empilha até 16, com textura de CD branco;
  - receita: 4 pedregulhos em cruz + 1 barra de ferro;
  - o botão **Gravar** em cada faixa da GUI gasta um e entrega o disco gravado. Ele só
    aparece se o jogador tiver Disco Virgem (ou estiver no criativo, onde não gasta).
- **Fim da duplicação de discos:** tocar pela GUI cria um disco virtual, que some ao ejetar
  ou ao quebrar a jukebox. Disco físico nunca é sobrescrito.
- **GUI:** abre com clique-direito na jukebox vazia. Com disco dentro, o clique faz o
  vanilla (ejeta).
- **Retomada:** a música volta de onde estava depois de reiniciar o servidor ou recarregar
  o chunk. Faixa que falta na biblioteca é baixada de novo.
- **Opções de config:**
  - `musicCommandPermission` (0 = todos, 2 = só operadores);
  - `requireBlankDiscToDownload` (baixar pela GUI gasta um Disco Virgem);
  - padrão de `maxDurationSeconds` = 900 (15 min).
- **Interno:**
  - formato de arquivo SMB2;
  - servidor de dev em `run/server`;
  - runners do CI fixados em `ubuntu-24.04`;
  - primeiros testes JUnit (15 testes).

Changelog sugerido para a tag (em inglês):

```
- Spotify track links: the song is found on YouTube by matching its length
- Blank Discs: craft them (4 cobblestone + 1 iron ingot) and use the Record button in the jukebox menu to get a disc of any song
- No more disc duplication: songs played from the menu don't turn into items
- Right-click an empty jukebox to open its menu
- Music resumes where it was after a server restart
- New config options for survival servers: musicCommandPermission, requireBlankDiscToDownload
- Max track length raised to 15 minutes (new installs)
```

## Pendências

### Depende de terceiros
1. **Aprovação do Modrinth.** Quando sair: tag `v1.1.0` + PATCH da página com
   `modrinth/description.md` (passos no `CLAUDE.md`, seção "Lançamento").
2. **youtube-source 1.18.3.** Não saiu ainda; a 1.18.2 não toca nada desde a mudança do
   YouTube em agosto/2026, por isso o mod usa o snapshot `2be8e54`. Quando sair:
   - em `gradle.properties`, `youtube_source_version=1.18.3` e `youtube_source_label=1.18.3`;
   - remover o repositório de snapshots do `build.gradle`;
   - lançar uma 1.1.1.

   Servidores com o mod já baixam a 1.18.3 sozinhos ao reiniciar.

### Decisões do usuário
3. **Release antigo "Simple MusicBox V1.0.0"** (tag `Release`) no GitHub: duplica o v1.0.0.
   Apagar ou manter?
4. **Mundo de teste `run/saves/SMB Teste`** (cópia do "Novo mundo", fora do git): pode ser
   apagado quando não for mais útil.

### Não testado ainda
5. **Dois jogadores reais num servidor dedicado** ao mesmo tempo (um gravando ou baixando
   enquanto o outro ouve). Tudo foi testado em single-player/LAN, no smoke test do
   dedicado e pelo console. Vale um teste com outra pessoa antes de lançar a 1.1.0.

### Adiado de propósito
6. **Gradle 8.12 / Loom 1.10** desatualizados (o CI avisa). Atualizar mexe no build sem
   ganho para o jogador.
7. **Servidores em datacenter:** o YouTube bloqueia esses IPs. Exigiria OAuth/poToken do
   youtube-source, que está mudando. Ficou de fora por decisão e está nas limitações.

### Observações
- **Configs existentes:** quem já tem `config.json` mantém o limite de 10 min, e as opções
  novas entram com os padrões. Está documentado no README.
- **Jukebox antiga no "Novo mundo"** (13, 65, 9): retoma sempre do zero. Hipótese **não
  confirmada**: o blockstate dela estaria com `has_record=false` enquanto há disco dentro
  (estado gravado por uma versão antiga do mod), e sem isso o vanilla não conta o tempo.
  Para confirmar: `/execute if block 13 65 9 jukebox[has_record=false]`. Jukeboxes novas
  retomam do ponto certo (testado).
- **Ícone e texturas** foram gerados por IA. A regra 6.2 do Modrinth proíbe imagens de IA no
  ícone/galeria; o usuário aceitou o risco. Se a moderação recusar, trocar o ícone por um
  feito à mão.
