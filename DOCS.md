# PiKaraoke for Home Assistant

PiKaraoke is a YouTube-based karaoke machine: guests search for a song, add it
to the queue, and the host plays it back with pitch shifting and streaming to
the screen.

This add-on runs PiKaraoke inside Home Assistant and exposes it through the
Home Assistant ingress, so the web interface works without opening a port on
your network.

## How this repository is put together

This repository does not contain PiKaraoke's source. It holds only what the
add-on needs on top of upstream:

| Path | Purpose |
| --- | --- |
| `Dockerfile` | Builds the add-on image. Fetches upstream PiKaraoke at build time and applies this repository's patch. |
| `extension/0001-ha-addon.patch` | The add-on's own changes to upstream: Home Assistant ingress support and the lyrics feature. |
| `config.yaml` | The add-on's options and schema. |
| `run.sh` | The container's entry point. Translates the add-on's options into PiKaraoke's command line. |
| `android/` | The Android player app, and the built APK. |

Because upstream is fetched during the build, **updating PiKaraoke is a
one-line change**: set `UPSTREAM_REF` in the `Dockerfile` to a newer branch or
tag, and rebuild. If upstream has changed a file this repository patches, the
build fails with a clear conflict rather than producing a broken add-on.

## Installation

1. Add this repository to Home Assistant under
   **Settings → Add-ons → Add-on Store → ⋮ → Repositories**.
1. Reload the add-on store and install **PiKaraoke**.
1. Start the add-on and open it from the Home Assistant sidebar.

## Options

| Option | Description |
| --- | --- |
| `admin_password` | Password for the host-only actions (player controls, song editing, shutdown). Leave empty to let everyone on the network be an admin. |
| `song_folder` | Where downloaded songs are stored. Defaults to `/share/pikaraoke`. |
| `high_quality` | Download higher quality video. Uses more CPU and bandwidth. |
| `autoplay` | Start the next queued song automatically. Disable to let the host press play. |
| `streaming_format` | `hls` (default) or `mp4`. |
| `fetch_lyrics` | Fetch a downloaded song's captions from YouTube and save them next to it, so the lyrics appear on screen during playback. On by default. |
| `url` | Override the address shown on the splash screen and in the QR code, for example `http://192.168.1.50:5555`. Only needed when the address the add-on works out is not the one to use. |
| `max_storage_mb` | Maximum size of the song library, in megabytes. When the library grows past it, the oldest downloaded songs are removed after the next download. `0` means no limit. The same setting is on the Settings page. |

## Mappings

| Mapping | Target in the add-on | Purpose |
| --- | --- | --- |
| `share` | `/share` | Song library. Downloaded videos live in `/share/pikaraoke`. |
| `app_config` | `/config` | The add-on's own configuration (`config.ini`). |

## Accessing the interface

The add-on is served through Home Assistant ingress, so the web interface is
reachable from the sidebar and from any device that can reach Home Assistant.

The port `5555` is also published, which lets you open the interface directly at
`http://<home-assistant-host>:5555` and use the splash screen on a second
display.

The QR code on the splash screen points at that address. The add-on asks the
Home Assistant Supervisor for the host's address on the local network, because
the address the container detects for itself belongs to Home Assistant's
internal network and cannot be reached from a phone. Set the `url` option to
override it.

## Lyrics

With `fetch_lyrics` enabled, a song downloaded from YouTube gets its captions
fetched at the same time and saved next to the video as an `.ass` sidecar file.
The player picks it up automatically, so the words appear on screen while the
song plays.

The captions come from YouTube itself. Human-authored captions are preferred
over automatic ones, and the song's own language over a translation. A video
with no reachable captions simply plays without lyrics.

## Storage limit

The song library is a folder of downloaded videos, and a video is tens of
megabytes, so an evening of downloads fills a small disk. The `max_storage_mb`
option keeps the library under a ceiling:

```yaml
max_storage_mb: 500
```

Whenever a download finishes and the library has grown past the limit, the
oldest songs are removed until it fits again. The trim happens after the
download, so the limit is reached before the next song is picked up rather
than when the disk is already full. The limit is also checked at startup, so
lowering it takes effect without waiting for the next download.

The song that is playing is never removed, and neither is a song waiting in
the queue: a singer does not lose their turn because the disk filled up. A
song's lyrics and graphics are removed along with it, so trimming leaves no
orphans behind.

Set `max_storage_mb` to `0` for no limit, which is the default. The setting is
also on the Settings page, next to the other download options.

## The Android player

The add-on ships an Android app that shows the splash screen full-screen, for a
television or a tablet that has no browser worth using as a player.

Download it from the repository: [android/apk/piKaraoke.apk](android/apk/piKaraoke.apk).
Install it on the device (sideload it, or open the file and allow installation
from your file manager), then start **PiKaraoke**.

The app looks for the server on the network by itself, so on a television with
a remote control there is nothing to type: it finds the add-on and shows its
splash screen.

The address can also be typed in at any time, which is useful when the search
does not find the server, or when it finds one at an address that does not
reach it. The field is on the first screen and is filled in with the address
last used, so the app connects to it again on the next start.

Pressing back while the splash is playing asks whether the app should be
closed, rather than closing it straight away: a player on a television is
often left running, and a stray press of the remote should not end the
song. The question can be answered with the arrow keys of the remote, and
one of the answers opens the address field again.

The address is in the form `192.168.0.10:5555` — the host's address on the
local network and the port the add-on serves on. The port may be left out if
it is the usual one.

The address can also be given when the app is started, which is useful for
putting the app on a home screen with a fixed server:

```sh
adb shell am start -n com.dicklamman.pikaraoke/.MainActivity \
    -e com.dicklamman.pikaraoke.SERVER_URL http://192.168.0.10:5555
```

The app is signed with the keystore in `android/keystore`, so that a newer
build installs over an older one rather than being refused. Keep the keystore:
losing it means the app has to be uninstalled before a new one can be
installed.

### Building the app

```sh
cd android
ANDROID_HOME=/path/to/android-sdk ./build-apk.sh
```

This needs a JDK (17 or newer) and the Android SDK, and writes the result to
`android/apk/piKaraoke.apk`.

### How the app finds the server

The app asks the local network for a PiKaraoke server over UDP, on the same
port number as the web interface, and the server answers with the address to
open. The add-on therefore publishes `5555/udp` as well as `5555/tcp`.

The answer the server gives is the address shown on its splash screen, which is
the host's address on the local network rather than the container's, so the
app opens a page that a phone or a television can actually reach.

## Notes

- The add-on is based on Alpine Linux with a static FFmpeg build, because
  PiKaraoke needs the `rubberband` filter for pitch shifting.
- The song library is scanned on start, so songs added while the add-on is
  running show up after a restart.
- The add-on has no audio device of its own; playback happens on the machine
  running Home Assistant.
