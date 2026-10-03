# Simple MusicBox

**Autor:** Duck • **Licença:** Apache 2.0 • **Data:** 13/07/2026

Mod Fabric para Minecraft **1.21.1** que cria discos de música a partir de links do YouTube.

```
/music <url do YouTube>
```

O servidor baixa e converte a faixa (LavaPlayer + youtube-source, formato Opus), entrega um
**Disco de Música Personalizado** e, ao colocá-lo em uma jukebox, todos os jogadores próximos
ouvem a música com áudio posicional — igual a um disco vanilla (partículas, allays dançando,
sinal de comparador etc.).

## Requisitos

- Minecraft 1.21.1 + Fabric Loader ≥ 0.16 + Fabric API
- O mod precisa estar instalado **no servidor e em todos os clientes** que quiserem ouvir
  (o jogo vanilla não tem como reproduzir áudio arbitrário)
- O servidor precisa de acesso à internet para baixar as faixas; os clientes não
  (recebem o áudio do próprio servidor, em chunks)

## Como funciona

1. `/music <url>` → o servidor resolve a faixa, decodifica para frames Opus e grava em
   `config/simple_musicbox/cache/<videoId>.smb` (a mesma URL não é baixada duas vezes).
2. A faixa fica disponível na GUI de qualquer jukebox. Discos físicos (com o título da
   faixa num data component `simple_musicbox:track`) **só nascem ejetando uma jukebox** —
   inserir um disco o consome, e tocar até o fim também: sem farm de discos.
3. Ao inserir na jukebox, o servidor abre uma "sessão" e envia aos jogadores num raio de
   64 blocos o pedido de reprodução; quem não tem a faixa no cache local pede os bytes,
   que chegam em chunks de ~60 KB com limite de vazão por tick.
4. O cliente decodifica os frames Opus e injeta o áudio no engine de som do Minecraft como
   um som posicional da categoria "Discos/Jukebox" (funciona com o slider de volume).
5. Quem chega perto no meio da música ouve a partir do ponto certo; ejetar o disco ou
   quebrar a jukebox para a música para todos.

## GUI da jukebox (shift + clique-direito)

Abre uma tela com a faixa atual e barra de progresso ao vivo, botões **Parar** /
**Avançar** / **Ejetar** / **Loop**, a lista de todas as faixas do cache do servidor
(com busca), e um campo para baixar direto por URL. Clicar numa faixa toca na hora (o
disco é materializado dentro da jukebox); **Ejetar** entrega o disco da faixa atual.
Também abre pelo chat com `/player` (jukebox mais próxima, raio de 16 blocos).

## Fila e loop

- **Sem loop**: ao terminar uma faixa, a jukebox avança sozinha para a próxima do cache
  (ordem alfabética, circular) — uma rádio que só para quando alguém usar Parar/Ejetar ou
  quebrar o bloco. Se só existir uma faixa no cache, ela repete.
- **Com loop** (botão na GUI, salvo no bloco): repete a faixa atual indefinidamente.

## Configuração (`config/simple_musicbox/config.json`)

| Campo | Padrão | Descrição |
|---|---|---|
| `maxDurationSeconds` | 600 | Duração máxima aceita para uma faixa |
| `audibleRadius` | 64.0 | Raio (blocos) em que os jogadores recebem o áudio |
| `networkChunkSize` | 60000 | Tamanho de cada chunk de rede (bytes) |
| `chunksPerTick` | 8 | Chunks enviados por tick para cada jogador |
| `stereo` | true | (Cliente) `true` = estéreo com volume caindo pela distância, sem direção esquerda/direita; `false` = mono posicional como um disco vanilla (o OpenAL só espacializa fontes mono) |
| `opusBitrate` | 96000 | Bitrate do áudio armazenado, em bits/s (~0,7 MB/min). `0` desliga o re-encode e usa a saída padrão do LavaPlayer (~140 kbps). Só afeta downloads novos |
| `maxCacheSizeMb` | 512 | Limite do cache de faixas em MB (cada lado tem o seu). Excedeu, as faixas tocadas há mais tempo são apagadas (LRU). `0` = ilimitado |
| `autoUpdateYoutubeSource` | true | (Servidor) A cada início, verifica e baixa automaticamente a versão mais nova do extrator do YouTube (veja abaixo) |
| `youtubeClients` | ANDROID, IOS, ... | (Servidor) Clients do YouTube tentados em ordem. Quando o YouTube bloqueia um, dá para reordenar sem recompilar |

## Auto-atualização do extrator do YouTube

A parte que quebra quando o YouTube muda alguma coisa é a biblioteca
[youtube-source](https://github.com/lavalink-devs/youtube-source), mantida pela equipe do
Lavalink. Para o mod não depender de ninguém recompilar nada:

- As bibliotecas do motor de áudio vêm embutidas no jar e são extraídas para
  `config/simple_musicbox/libs/` no primeiro uso (funciona offline).
- A cada início do servidor, o mod consulta o repositório Maven oficial do Lavalink e, se
  houver youtube-source mais novo, baixa (com verificação de integridade) e passa a usá-lo.
- Se uma versão nova falhar ao carregar, o mod volta sozinho para a anterior e avisa os
  admins no jogo.

Ou seja: quando o YouTube quebrar, normalmente basta **reiniciar o servidor** depois que a
correção da comunidade sair — o mod se atualiza sozinho.

## Desenvolvimento

```bash
./gradlew build          # gera build/libs/simple-musicbox-<versão>.jar
./gradlew runClient      # cliente de desenvolvimento
./gradlew runServer      # servidor de desenvolvimento
./gradlew runClient -Pquickplay=localhost:25565   # cliente conectando direto num servidor
```

O jar final também é copiado para `dist/`.

### Verificações (as mesmas do CI)

```bash
./gradlew build -Pstrict           # avisos do compilador viram erro + Checkstyle
python3 scripts/check-resources.py # JSON, paridade en_us/pt_br, chaves usadas, mixins
scripts/smoke-server.sh            # sobe o servidor dedicado e confere a inicialização
scripts/verify-jar.sh dist/simple-musicbox-<versão>.jar <versão>
```

### CI/CD

- **CI** (`.github/workflows/ci.yml`, todo push/PR na main): lint (actionlint, shellcheck,
  validação do Gradle wrapper, recursos), build estrito com Checkstyle, verificação do jar e
  smoke test do servidor.
- **Release** (`.github/workflows/release.yml`): ao dar push numa tag anotada `v*`, roda o
  CI inteiro, confere que a tag é semver e está na main, compila com a versão da tag, cria o
  GitHub Release e publica no Modrinth (secret `MODRINTH_TOKEN`). Sufixos `-alpha`/`-beta`/`-rc`
  viram versões de teste.

```bash
git tag -a v1.0.1 -m "Changelog da versão" && git push origin v1.0.1
```

O jar final embute o LavaPlayer e o plugin youtube-source (shadow, com jackson/httpclient
relocados para evitar conflito com as libs do próprio Minecraft).

## Limitações conhecidas

- Apenas YouTube na v1 (Spotify não permite download de áudio; o plano é resolver links do
  Spotify via metadados + busca no YouTube em uma versão futura).
- O YouTube muda com frequência; o mod se auto-atualiza (seção acima), mas entre a quebra e
  a correção da comunidade os downloads podem falhar temporariamente.
- Em servidores hospedados em datacenter o YouTube pode bloquear downloads (soluções como
  OAuth do youtube-source ficam para depois).
- Se o servidor reiniciar com uma música tocando, a jukebox vanilla pode ficar "tocando"
  silêncio até o disco ser ejetado.

## Licença

Copyright 2026 Duck.

Distribuído sob a [Apache License 2.0](LICENSE).
