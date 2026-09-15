# glass-stocks

Stock chart viewer for Google Glass. Cycles a list of tickers as a fullscreen slideshow with three zoom levels, pulling each chart from StockCharts.

Originally a [StockCharts Voyeur](https://stockcharts.com/voyeur.html) viewer; Voyeur was retired and its images now 404, so the app fetches per-ticker SharpCharts images instead.

## Build & Install

```bash
cd ~/glass-apps/glass-stocks
# this machine needs both overrides -- see the android-sdk-path-conflict note
env -u ANDROID_SDK_ROOT ANDROID_HOME=/home/stathis/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Launch

```bash
adb shell am start -n com.glassstocks/.MainActivity
```

## Controls

| Input | Action |
|-------|--------|
| Tap | Cycle zoom: Fit → Fill → Close-up |
| Swipe right | Next chart |
| Swipe left | Previous chart |
| Swipe down | Exit |
| Long press | Exit |

## Zoom Modes

| Mode | Description |
|------|-------------|
| Fit | Full chart scaled to fit screen (default) |
| Fill | Center-crop to fill screen |
| Close-up | 2.5x zoom anchored top-left to read the ticker symbol |

Slideshow pauses while zoomed. Tap back to Fit to resume, or swipe to the next chart.

## Tickers

Defaults to 14 symbols: `$SPX $COMPQ $VIX SPY QQQ IWM TLT GLD USO XLE XLF XLK NVDA AAPL`.

Override without rebuilding — one symbol per line, `#` for comments:

```bash
adb push tickers.txt /sdcard/glass-stocks-tickers.txt
```

The list is read at launch; restart the app after changing it.

## How it works

- Fetches one PNG per ticker from `stockcharts.com/c-sc/sc?s=SYMBOL&p=D&b=5&g=0&i=0`, the SharpCharts image endpoint (700x530, no login)
- Auto-cycles every 10 seconds
- Re-fetches every chart every 15 minutes
- Counter overlay shows the symbol and position (e.g. `SPY   4 / 14`)

## Landmines

- **StockCharts 404s the default Java User-Agent.** The app sends a browser UA; without it every request fails and looks like a dead URL rather than a block.
- **An unknown symbol returns HTTP 200** with a ~2.4 KB "invalid symbol" image (a real chart is ~20 KB). Anything under 5 KB is rejected and the previous chart for that slot is kept.
- One bad ticker no longer kills the sweep — each fetch is caught on its own.

**Permissions:** `INTERNET`, `WAKE_LOCK`, `READ_EXTERNAL_STORAGE` (only for the optional ticker file)

No companion required.
