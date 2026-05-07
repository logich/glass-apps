# glass-followwindow

Mirrors the X11 active window (and optionally the active terminal pane) to
peripheral displays. Two consumers today:

- **Google Glass Explorer** — `app/` (this Gradle project). Two-mode HUD:
  - **Window** — big title, class, PID. Live-updated via SSE.
  - **Terminal** — monospace preview of the active terminal pane. Polled (2s).
  - Tap the touchpad to toggle. Only the active mode polls — the other client
    is stopped — so terminal-mode capture only runs while it's on screen.
- **Kindle Keyboard (K3)** — kindle-buddy host-bridge subscribes via
  `bridge/glass_subscriber.py` and writes the focused-window summary onto the
  K3 status row.

Single source of truth so swapping desktop focus updates all peripheral
displays in real time.

```
                ┌──────────────────────┐
   xprop -spy → │ glass-followwindow   │
                │  server  :8089       │ ──── /stream (SSE) ─── Glass HUD (window mode)
                └──────────────────────┘ ──── /stream (SSE) ─── kindle-buddy host-bridge
                                                                         ↓
                                                           K3 fbink status row 11

                     dashd  :7778      ──── /preview ─────── Glass HUD (terminal mode)
```

## 1. Server

Stdlib-only Python. Watches `_NET_ACTIVE_WINDOW` via `xprop -spy` and resolves
title/class/PID per focus change.

```bash
cd server
./start.sh                # listens on 0.0.0.0:8089
./start.sh --port 9000    # custom port
./start.sh --log-level DEBUG
```

Prereqs:
- `xprop` and `xdotool` on PATH (`pacman -S xorg-xprop xdotool`).
- An X11 session with a `_NET_ACTIVE_WINDOW`-aware WM (i3, GNOME, KDE, etc.).
- Python ≥ 3.9. No pip dependencies.

Endpoints:
- `GET /active`  — JSON snapshot of the current focused window.
- `GET /stream`  — `text/event-stream`. One event per focus change. Sends the
  current snapshot on connect so subscribers never render empty.
- `GET /healthz` — liveness ping.

Run as a session service (Linux):

```bash
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/glass-followwindow.service <<'EOF'
[Unit]
Description=glass-followwindow active-window broadcaster
After=graphical-session.target

[Service]
ExecStart=%h/glass-apps/glass-followwindow/server/start.sh
Restart=on-failure

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now glass-followwindow
```

## 2. Glass app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch options. Host + ports are persisted to `SharedPreferences` after first
launch — subsequent launches need no extras.

```bash
# Via USB tunnel (only if `adb reverse` works on your unit; some Glass adbds
# silently fail to set the reverse, in which case use Wi-Fi).
adb reverse tcp:8089 tcp:8089
adb reverse tcp:7778 tcp:7778
adb shell am start -n com.glassfollowwindow/.MainActivity --es host localhost

# Via Wi-Fi (Glass and host must be on the same SSID with no client isolation).
adb shell am start -n com.glassfollowwindow/.MainActivity \
  --es host 10.26.209.148 \
  --ei follow_port 8089 \
  --ei dash_port 7778
```

### HUD modes

| Mode      | Source                          | Update model      |
|-----------|---------------------------------|-------------------|
| Window    | `:8089/stream` (SSE)            | Push, on focus    |
| Terminal  | `:7778/preview` (HTTP poll)     | 1.5s poll         |

**Tap the touchpad** (`KEYCODE_DPAD_CENTER`) to toggle. Selected mode is
persisted to `SharedPreferences`. Default is Window.

Terminal mode requires `dashd` (the kindle-buddy host-publisher) to be running
on the host. Without it the terminal panel will simply show a status error and
can be toggled away from. Window mode does NOT depend on dashd.

## 3. Kindle Buddy integration

Already wired. `~/kindle-apps/kindle-toolkit/apps/kindle-buddy/host-bridge/bridge/glass_subscriber.py`
runs as part of `host-bridge` and pushes the focused-window summary to fbink
row 11 on the K3.

Config via env (set in `host-bridge` start script if needed):

```
GLASS_FOLLOW_URL=http://127.0.0.1:8089/stream  # set empty to disable
```

## 4. Network notes

- **UFW**: open inbound for the ports you want peripherals to reach.
  ```
  sudo ufw allow 8089/tcp
  sudo ufw allow 7778/tcp   # only if Glass/iOS will use terminal mode
  ```
- **AP client isolation**: many dual-band APs separate guest/IoT/2.4GHz from
  the main SSID. If the Glass and the host share the /24 but can't ping each
  other, both must be on the same SSID. (E.g. on this rig host needs to be on
  `yoga2` (2.4GHz) because Glass is 2.4-only.)
- **adb reverse on Glass**: known-flaky on some Glass units — `--list` returns
  empty even after a "successful" set. Prefer Wi-Fi unless USB tunnel is
  verified working with `adb reverse --list`.

## 5. File map

```
glass-followwindow/
├── server/
│   ├── glass_followwindow.py    # daemon (stdlib)
│   └── start.sh
├── app/                         # Glass APK (Gradle, AGP 8.9, no AndroidX)
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/glassfollowwindow/
│       │   ├── MainActivity.java   # mode toggle + UI
│       │   ├── SseClient.java      # /stream consumer
│       │   └── TerminalClient.java # /preview poller
│       └── res/
└── README.md
```

Kindle subscriber lives outside this tree at
`~/kindle-apps/kindle-toolkit/apps/kindle-buddy/host-bridge/bridge/glass_subscriber.py`.
