# Changelog

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
