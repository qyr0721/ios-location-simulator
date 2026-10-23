# Third-party notices

This project builds on the following open-source work.

## go-ios

- https://github.com/danielpaulus/go-ios
- License: MIT
- `app/src/main/jniLibs/arm64-v8a/libgoios.so` and `liblocsim.so` are compiled
  from go-ios v1.3.2 plus the small wrapper in `go/locsim/`.
- The go-ios source itself is not vendored in this repository; see
  `README.md` for the build commands.

## Leaflet

- https://leafletjs.com/
- License: BSD-2-Clause
- Loaded in `app/src/main/assets/map.html` from unpkg at runtime.

## OpenStreetMap

- https://www.openstreetmap.org/
- Map data © OpenStreetMap contributors, ODbL.
- Default raster tiles: https://tile.openstreetmap.org/
- Geocoding search: Nominatim (https://nominatim.org/), usage policy applies.

## AndroidX / Material Components

- Apache License 2.0

## dd-plist

- https://github.com/3breadt/dd-plist
- License: MIT

## Bouncy Castle

- https://www.bouncycastle.org/
- License: MIT-style

## Protocol references

The usbmux / lockdown / DVT protocol implementation was written from public
documentation and the following open-source references:

- usbmuxd: https://github.com/libimobiledevice/usbmuxd (LGPL-2.1 / GPL-2.0)
- libimobiledevice: https://github.com/libimobiledevice/libimobiledevice (LGPL-2.1)
- pymobiledevice3: https://github.com/doronz88/pymobiledevice3 (GPL-3.0)

No source code from these projects is copied into this repository; only public
protocol behaviour was referenced.
