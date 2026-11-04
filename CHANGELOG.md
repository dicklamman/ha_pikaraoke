# Changelog

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
  in.
