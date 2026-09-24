# YT Sync for iBroadcast

Android app: paste Spotify/YouTube URLs (or pick local audio), fetch the best
YouTube audio match, tag it, optionally keep a local copy, and upload to
iBroadcast. Ships via GitHub Releases only (a YouTube downloader cannot go on
Google Play).

## Features

- **Flexible intake** — paste Spotify tracks, albums, or playlists, YouTube
  videos or playlists, or pick local audio files (MP3/FLAC/WAV) from storage.
- **Smart matching** — each row is matched against YouTube candidates with
  duration, title, artist, and view-count scoring; review and swap editions
  in the release inspector before anything downloads.
- **Proper tags** — ID3 title/artist/album/genre/year/track number plus cover
  art embedded in every file.
- **Your choice of format** — MP3 192k, MP3 320k, or M4A/AAC.
- **Duplicate protection** — skips tracks already in your iBroadcast library
  (title + artist and checksum matching), with an override per row.
- **Playlist routing** — send uploads straight to your library, always to a
  favorite playlist, or pick per batch; new playlists can be created in-app.
  Spotify/YouTube playlists can auto-create a matching iBroadcast playlist.
- **Optional local copy** — keep files on-device via a folder you choose, or
  upload straight through without saving.
- **Batch engine** — everything runs in a foreground service with progress
  and cancel; finished tracks land in a synced archive with re-sync support.

## Requirements

- Android 10+ (`minSdk 29`), `arm64-v8a` or `x86_64` (separate APKs per
  architecture — download the one matching your device).
- An iBroadcast account (free). Sign-in uses OAuth device code; no password
  ever touches the app.

## Trademarks / disclaimer

Personal-use tool. You are responsible for complying with YouTube's Terms of
Service, Spotify's terms, iBroadcast's terms, and local law. This project is
not affiliated with, endorsed by, or sponsored by Spotify, YouTube, Google, or
iBroadcast.

Powered by iBroadcast (uploads use the official API with your own account).

## Build from source

Prerequisites: JDK 17 and the Android SDK (compileSdk 35) with `sdk.dir`
set in `local.properties` (create it — see the Android Studio default; the
file is git-ignored and never committed).

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (unsigned unless signing is configured)
```

Signed release builds read, in order, environment variables then a
git-ignored `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); without either, release builds stay unsigned
rather than failing, so forks build out of the box. CI release signing is
wired through repository secrets — see `.github/workflows/release.yml`.

## Tests

```bash
./gradlew test                            # JVM unit tests (JUnit 4)
python3 tests/match_scoring_parity.py     # match-scoring parity checks
python3 tests/metadata_slice_parity.py    # metadata/row-state parity (53 checks)
```

## Contributing

Issues and pull requests are welcome. Please keep batches small and behavior
preserving: the row-state machine, match-scoring weights, and never-throw
metadata contracts are covered by the suites above — a red test
reverts, it never gets "fixed forward." Note the project ships via GitHub
Releases only and will never target Google Play (YouTube downloaders are not
permitted there).

## Signing in / client ID

The app signs in with iBroadcast's OAuth 2.0 **device code** flow, which for a
public client needs only a `client_id` — there is **no client_secret**, so
nothing secret is shipped in this repo. A default `client_id` is baked in so
sign-in works on first launch with no setup.

To use your own iBroadcast app instead (for example if the built-in one stops
working, or you'd rather not authenticate through someone else's app record),
open **Settings → Client ID** and paste your own. Get one free at
[media.ibroadcast.com](https://media.ibroadcast.com): **Apps → Developers →
create app**. A blank field falls back to the built-in ID; tap **RESET** to
clear an override. Changing it while signed in requires signing in again,
because tokens are issued to the app record that requested them.

The ID is a public identifier, not a secret — it appears in the authorization
URL by design. Never add a `client_secret` to this app; the device flow does
not use one.

## License

MIT — see `LICENSE`.
