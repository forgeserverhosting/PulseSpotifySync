# Pulse v0.5

Pulse is an Android local music player and playlist-import app.

## v0.5 direction

Spotify developer credentials are no longer part of the user experience.

### New in v0.5
- Music-first local library filter that hides likely voice notes, recordings, WhatsApp audio and tiny clips by default.
- One-tap switch to **All audio** when you do want everything.
- Cleaner local track titles and better artwork fallbacks.
- Persistent mini-player with previous / play-pause / next and seek.
- Tap the mini-player to open a larger Now Playing view.
- Universal playlist import from M3U, M3U8, CSV and JSON.
- Pulse remembers imported source-file URIs and can **Refresh file** later.
- Imported playlist cards show track count, local matches, source type and last refresh time.
- Search covers local music plus imported playlist metadata.
- Imported tracks play inside Pulse when a matching local audio file exists; otherwise a source link can open externally when present.
- Share-to-Pulse support for playlist files.

## Spotify
Direct live Spotify account sync is intentionally not enabled in v0.5. Spotify's Web API requires an officially registered Spotify developer application. Pulse does not ask normal users for developer IDs or secrets.
