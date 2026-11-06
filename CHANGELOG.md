# Changelog

## 1.23.4

- Fix the Android player crashing, and the exit question flashing up and
  down, when the back button of the remote is pressed while a song is
  playing.
  - The back key is now taken before the web view sees it. The web view
    uses the back key to walk its own history, and the history of a player
    that has fallen over is a trail of error pages, so a press of the
    remote could replace the player with an error page in the middle of a
    song.
  - The question is raised on the way down of the key and the whole press
    is swallowed afterwards, both halves. Showing the question gives the
    question's own window the focus, so the release of the very press
    that raised it arrives at the question. Answering that release made
    the question appear and vanish in the same breath.
  - Only one question may be on screen at a time. A remote that holds the
    back key down sends a stream of repeats, and answering each of them
    stacked a question on every repeat.
  - The web view is not touched once it has been destroyed. Asking a
    destroyed web view to pause, or to load a page, takes the app down
    from the native side, where there is nothing to catch.
- The back button asks whether to close the app while the splash is
  playing, instead of closing it straight away, and one of the answers
  opens the address field again.

## 1.23.3

- Add a "Shuffle" button to the queue page, next to "Clear all". It
  randomises the order of the songs waiting in the queue, leaving the song
  that is playing untouched.
- The button is admin-only, like the other queue controls, and says so when
  there is nothing to shuffle.

## 1.23.2

- Move the QR code and the address to the top-left corner of the splash
  screen. They sat at the bottom left, where the "up next" announcement and
  the menu button now have room.
- The menu button and the notification toasts move to the bottom left, so
  they no longer sit on top of the QR code.

## 1.23.1

- Fix the address on the splash screen and in the QR code. The Supervisor
  wraps its answer in an envelope, so the host's address was never found in
  it and the add-on fell back to its own container address
  (`172.30.33.x`), which nothing outside Home Assistant can reach. The
  address is now the host's own (`192.168.x.x`), as the Supervisor reports it.
- The extension is regenerated against the current upstream code, so the
  add-on builds again after an upstream update.

## 1.23.0

- Initial Home Assistant add-on release.
- Runs PiKaraoke behind Home Assistant ingress, with the web interface
  reachable from the sidebar without opening a port.
- The QR code and the address on the splash screen point at the host's
  address on the local network, which the Supervisor reports, instead of the
  add-on container's own address on Home Assistant's internal network.
- Options for the admin password, song folder, download quality, autoplay,
  streaming format and the displayed address.
- An Android player app, in `android/`, that shows the splash screen
  full-screen and finds the server on the network by itself. The server
  answers a discovery request over UDP, so the app needs no address typed
  in. The address can be typed in as well, for when the search does not find
  the server or finds one that cannot be reached.
