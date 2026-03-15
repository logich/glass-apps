# Glass Baseball App Ideas

## Why Not a Strike Zone Projection?
Glass XE's display is a prism in the upper-right of the right eye — not a true AR overlay.
No spatial tracking, no depth sensing, no way to anchor virtual objects to real-world positions.
Camera is offset from the eye so CV-based overlay wouldn't align with what the pitcher sees.

## Viable Ideas

### 1. Pitch Counter HUD (simplest, most practical)
- Tap to log each pitch — track balls, strikes, total count, pitch type
- Voice commands via Glass mic: "fastball", "curve", "change"
- Running count displayed on HUD between pitches
- Per-inning and per-game stats
- Export game log after session

### 2. Pitch Speed Display (requires BLE radar gun)
- Pair with Pocket Radar or similar BLE-enabled speed gun
- Display speed on Glass immediately after each pitch
- BLE already working in glass-bt / glass-bike-hud

### 3. Video Review Station
- Record pitching sessions via glass-stream camera
- Send frames to companion server for strike zone overlay on video (server-side CV)
- Review annotated clips on phone/laptop between innings

### 4. Scouting / Batting Order HUD
- Display batter info (height, handedness, tendencies) as each new batter steps up
- Tap/swipe through lineup
- Catcher relays signs, pitcher sees them on Glass
