# Pulse v0.4

Pulse is an Android music library/player with Spotify used as a playlist import/sync source.

## v0.4
- Polished dark UI with safe-area handling and bottom navigation.
- Local Android audio playback with mini-player and seek control.
- Cleaner display names for noisy local filenames.
- Spotify Authorization Code + PKCE flow.
- Spotify playlist browser, import-all and per-playlist import.
- Saved Spotify-to-Pulse playlist mapping.
- `snapshot_id` change detection and foreground/manual sync.
- Access-token refresh.
- Local matching of imported Spotify metadata to playable audio on the phone.
- Stable test signing key so v0.4+ debug APKs can update over each other.

## Spotify activation
The Spotify sync code is complete, but a registered Spotify developer Client ID is required at runtime. No client secret is used.

Preferred build setup: add a GitHub Actions repository secret named `SPOTIFY_CLIENT_ID`.
For internal testing, long-press the PULSE logo or v0.4 badge and paste a Client ID into the hidden developer setup screen.

Redirect URI to register in the Spotify developer app:
`pulse-auth://callback`

Package name:
`com.example.pulse`

Spotify is used only for playlist metadata/import/sync. Spotify audio is not copied or downloaded.
