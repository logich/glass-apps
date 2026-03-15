# glass-vision — AI Vision Assistant for Google Glass

## Inspiration

[VisionClaw](https://github.com/sseanliu/VisionClaw) — real-time AI assistant for Meta Ray-Ban glasses. Streams camera frames (~1fps) + mic audio to Gemini Live via WebSocket, Gemini responds with voice + optional tool calls through OpenClaw.

## What We Already Have

- `glass-clawd` — voice → companion server → Whisper STT → Claude → text response
- `glass-stream` — camera capture + MJPEG streaming from Glass

## Concept

Merge glass-clawd and glass-stream: capture a camera frame alongside each voice recording, send both to a companion server, and get back a multimodal AI response that understands what you're looking at.

1. **Glass app** captures a camera frame (JPEG) alongside each voice recording, sends both to the companion server
2. **Companion server** sends the image + transcribed text to a multimodal model (Claude with vision, or Gemini) so the AI can see what you're looking at
3. **Server responds** with text (displayed on Glass) and optionally TTS audio streamed back

## Key Differences from VisionClaw

- VisionClaw uses Gemini Live's native bidirectional audio WebSocket — we can't do that on API 19, so we'd keep the glass-clawd request/response pattern (record → send → get reply)
- No iOS DAT SDK needed — Glass camera is a standard Android `Camera` API
- Could add continuous ~1fps frame streaming for ongoing visual context, or just snap a frame per voice query (simpler, lower bandwidth)

## Two Possible Approaches

| | Simple (per-query snapshot) | Full (continuous stream) |
|---|---|---|
| Camera | Snap one frame when you tap to talk | Stream ~1fps JPEG to server |
| Latency | Lower — single image per request | Higher — richer visual context |
| Complexity | Extend glass-clawd with one image attachment | New WebSocket pipeline + server state |
| Battery | Minimal extra drain | Camera always on |

Recommendation: start with the simple approach — snap a frame with each voice query and send image + audio to the server together. Gets 90% of the value with minimal complexity.

## Architecture

```
Google Glass (API 19)
       |
       | JPEG snapshot + WAV audio (on tap)
       v
Companion Server (Python)
       |
       | Whisper STT → text
       | text + JPEG → multimodal AI (Claude / Gemini)
       v
AI Response
       |
       |-- Text response --> Glass (displayed on screen)
       |-- TTS audio (optional) --> Glass (speaker playback)
```

## Open Questions

- Claude vision vs Gemini vs OpenAI for the multimodal model?
- TTS on server (what engine?) or text-only responses on Glass?
- Tool calling / agentic actions worth adding? (OpenClaw equivalent)
- Continuous conversation context (remember previous frames/queries)?
