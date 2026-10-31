# glass-led

Stream Google Glass's camera to a HUB75 LED matrix, with an iPhone as the bridge.

```
Glass (glass-stream app)  --MJPEG/HTTP-->  iPhone app  --UDP, 1 packet/row-->  Matrix Portal + HUB75 panel
```

> **Status:** untested on hardware. The wire format and the desktop sender were
> checked against a fake stream; the iOS app and firmware have not been built or run.

## Pieces

| Path | What |
|------|------|
| `../glass-stream` | Runs on Glass, serves `http://<glass-ip>:8080/stream` |
| `ios/` | SwiftUI app: reads MJPEG, crops/downsamples to the panel size, gamma + brightness, sends RGB565 rows |
| `firmware/code.py` | CircuitPython receiver for Adafruit Matrix Portal S3 (64x32 default) |
| `tools/send.py` | Same pipeline on a laptop (Pillow) for testing without the iPhone |

Wire format: one UDP packet per row, `[row:u8][WIDTH x RGB565 little-endian]`.
The frame is displayed when the last row arrives.

## Setup

1. **Glass:** install `apks/glass-stream.apk` (`adb install -r`), launch *Glass Stream*.
   Glass needs WiFi, and on an original Explorer the MyGlass setup is gone, so join
   the network from the Glass Settings card or via adb.
2. **Network:** turn on the iPhone **Personal Hotspot** (enable *Maximize Compatibility*
   for 2.4 GHz). Join Glass and the Matrix Portal to it. This keeps everything working
   outdoors with no router. The Glass IP shows in the glass-stream status bar.
3. **Matrix Portal:** install CircuitPython 9+, copy `firmware/code.py` to `CIRCUITPY`,
   and set `CIRCUITPY_WIFI_SSID` / `CIRCUITPY_WIFI_PASSWORD` in `settings.toml`
   (the hotspot's). The IP prints on the serial console. Adjust `WIDTH`/`HEIGHT`.
   For the older Matrix Portal M4 the receiver must use `adafruit_esp32spi` sockets instead.
4. **iPhone app:** `brew install xcodegen && cd ios && xcodegen`, open `GlassLED.xcodeproj`,
   set your signing team, run. Enter the Glass URL and Matrix IP, tap Start.
   (Or make a new Xcode iOS app and drop in the `.swift` files plus the Info.plist keys from `project.yml`.)

Test without the phone: `python3 tools/send.py http://<glass-ip>:8080/stream <matrix-ip>`

## Tips

- Keep brightness low (default 40%): full-white panels draw several amps. Power the panel from its own 5 V supply.
- Expect 10-15 fps; the UDP packets are small, so drops show as brief tearing, not stalls.
- Camera is the Glass's own view, so for a costume you may want *Mirror* on.
