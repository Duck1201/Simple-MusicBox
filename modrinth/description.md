Turn any jukebox into a music player. Paste a YouTube link, and everyone nearby hears the song with positional audio, just like a vanilla music disc.

```
/music <YouTube URL>
```

## Features

- **Music from YouTube links**: `/music <url>` downloads the track on the server and adds it to the jukebox library.
- **Jukebox GUI** (shift + right-click a jukebox, or `/player` for the nearest one): current track with a live progress bar, Pause / Stop / Skip / Eject / Loop buttons, a searchable list of every downloaded track, and a field to download by URL.
- **Positional audio for everyone**: players within 64 blocks hear the music; it fades with distance and uses the vanilla "Jukebox/Note Blocks" volume slider. Particles, dancing allays and comparator output work like a vanilla disc.
- **Join mid-song**: players who walk up while a song is playing hear it from the right point.
- **Queue and loop**: without loop, the jukebox moves on to the next track in the library like a radio; with loop, it repeats the current track.
- **Physical discs**: eject a jukebox to get a disc of the current track, which you can carry around or put in another jukebox.
- **Compact cache**: tracks are stored as Opus (~0.7 MB per minute) with an LRU size limit.

## Requirements

- Minecraft 1.21.1, Fabric Loader 0.16+, [Fabric API](https://modrinth.com/mod/fabric-api)
- Must be installed on **both the server and every client** that wants to hear the music (vanilla clients cannot play arbitrary audio).

## Network access (please read)

This mod connects to the internet on its own. In detail:

- **YouTube**: the server (not the clients) downloads the audio of the links that players submit with `/music` or in the GUI. Clients receive the audio from the Minecraft server they are connected to, never from YouTube.
- **maven.lavalink.dev**: on server start, the mod checks the official Lavalink Maven repository for a newer version of [youtube-source](https://github.com/lavalink-devs/youtube-source), the open-source library that extracts audio from YouTube and that breaks whenever YouTube changes something. If a newer version exists, it is downloaded (SHA-1 verified) into `config/simple_musicbox/libs/` and loaded. If it fails to load, the mod falls back to the previous version automatically. Set `autoUpdateYoutubeSource` to `false` in the config to disable this; the libraries bundled in the mod jar are used offline.

No player data is uploaded anywhere.

## Configuration

`config/simple_musicbox/config.json`:

| Field | Default | Description |
|---|---|---|
| `maxDurationSeconds` | 600 | Longest track accepted |
| `audibleRadius` | 64.0 | Radius (blocks) in which players receive the audio |
| `stereo` | true | (Client) `true` = stereo, volume fades with distance; `false` = mono with full 3D direction like a vanilla disc |
| `opusBitrate` | 96000 | Bitrate of stored audio (bits/s). `0` keeps LavaPlayer's default output |
| `maxCacheSizeMb` | 512 | Track cache limit (server and client each). `0` = unlimited |
| `autoUpdateYoutubeSource` | true | (Server) Auto-update the YouTube extractor on start |
| `youtubeClients` | ANDROID, IOS, ... | (Server) YouTube clients tried in order. If downloads break, reordering this can help without a mod update |
| `networkChunkSize` / `chunksPerTick` | 60000 / 8 | Audio transfer tuning |

## Known limitations

- YouTube only for now.
- When YouTube changes something, downloads may fail until the youtube-source maintainers publish a fix; the mod then picks it up on the next server restart.
- Already downloaded tracks keep working regardless.

## Credits

Audio is handled by [LavaPlayer](https://github.com/lavalink-devs/lavaplayer) and [youtube-source](https://github.com/lavalink-devs/youtube-source) (Apache 2.0) and [Concentus](https://github.com/lostromb/concentus) (Opus in pure Java).

Please respect the rights of the content you play. This mod is not affiliated with YouTube or Google.

*AI disclosure: this mod's code, its textures/icon and this description were created with the help of generative AI.*
