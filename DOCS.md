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

## Notes

- The add-on is based on Alpine Linux with a static FFmpeg build, because
  PiKaraoke needs the `rubberband` filter for pitch shifting.
- The song library is scanned on start, so songs added while the add-on is
  running show up after a restart.
- The add-on has no audio device of its own; playback happens on the machine
  running Home Assistant.
