#!/usr/bin/env python3
"""glass-followwindow — broadcast the X11 active window to peripheral displays.

Watches `_NET_ACTIVE_WINDOW` via `xprop -spy` and resolves window title / class /
pid for each focus change. Exposes:
    GET /active   single JSON snapshot of the current window
    GET /stream   text/event-stream — one SSE event per focus change
    GET /healthz  liveness ping

Stdlib only. Subscribers (Glass APK, iOS Glass app, kindle-buddy host-bridge)
all consume /stream.
"""
from __future__ import annotations

import argparse
import json
import logging
import queue
import re
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("glass-followwindow")

# Regex helpers for xprop output.
_ACTIVE_RE = re.compile(r"window id # (0x[0-9a-fA-F]+|\d+)")
_WM_CLASS_RE = re.compile(r'WM_CLASS\(STRING\)\s*=\s*"([^"]*)",\s*"([^"]*)"')
_WM_NAME_UTF8_RE = re.compile(r'_NET_WM_NAME\(UTF8_STRING\)\s*=\s*"((?:[^"\\]|\\.)*)"')
_WM_NAME_RE = re.compile(r'WM_NAME\(STRING\)\s*=\s*"((?:[^"\\]|\\.)*)"')
_WM_PID_RE = re.compile(r"_NET_WM_PID\(CARDINAL\)\s*=\s*(\d+)")


def _parse_active(line: str) -> str | None:
    """Parse a line of `xprop -spy -root _NET_ACTIVE_WINDOW` into a window id.

    Returns the lowercased "0x..." form so it can be passed straight to
    `xprop -id`. None if the line names no window (focus dropped).
    """
    m = _ACTIVE_RE.search(line)
    if not m:
        return None
    wid = m.group(1)
    if wid in ("0x0", "0"):
        return None
    return wid.lower() if wid.startswith("0x") else hex(int(wid))


def _resolve_window(wid: str) -> dict:
    """Run xprop on a window id and return a {title, wm_class, wm_instance, pid}
    dict. Best-effort — missing properties become None."""
    try:
        out = subprocess.run(
            ["xprop", "-id", wid, "WM_CLASS", "_NET_WM_NAME", "WM_NAME", "_NET_WM_PID"],
            capture_output=True, text=True, timeout=2,
        ).stdout
    except (subprocess.SubprocessError, FileNotFoundError) as e:
        log.warning("xprop -id %s failed: %s", wid, e)
        return {"title": None, "wm_class": None, "wm_instance": None, "pid": None}

    inst = cls = None
    m = _WM_CLASS_RE.search(out)
    if m:
        inst, cls = m.group(1), m.group(2)

    title = None
    m = _WM_NAME_UTF8_RE.search(out)
    if m:
        title = bytes(m.group(1), "utf-8").decode("unicode_escape")
    else:
        m = _WM_NAME_RE.search(out)
        if m:
            title = bytes(m.group(1), "utf-8").decode("unicode_escape")

    pid = None
    m = _WM_PID_RE.search(out)
    if m:
        try:
            pid = int(m.group(1))
        except ValueError:
            pid = None

    return {"title": title, "wm_class": cls, "wm_instance": inst, "pid": pid}


# ---------------------------------------------------------------------------
# State + subscribers
# ---------------------------------------------------------------------------

class State:
    """Thread-safe focused-window state with fanout to SSE subscribers."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._current: dict = {
            "window_id": None,
            "title": None,
            "wm_class": None,
            "wm_instance": None,
            "pid": None,
            "ts": time.time(),
        }
        # Each subscriber gets its own queue of pending events. Bounded; on
        # overflow we drop the oldest event so a slow subscriber can't block
        # the watcher.
        self._subs: list[queue.Queue] = []

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._current)

    def update(self, info: dict) -> dict:
        """Replace current state and broadcast to subscribers. Returns the
        new state. No-op (returns None) if state is unchanged."""
        info = dict(info)
        info["ts"] = time.time()
        with self._lock:
            unchanged = (
                info.get("window_id") == self._current.get("window_id")
                and info.get("title") == self._current.get("title")
            )
            if unchanged:
                return None
            self._current = info
            subs = list(self._subs)
        log.info("active -> %s [%s] (pid=%s)",
                 info.get("title"), info.get("wm_class"), info.get("pid"))
        for q in subs:
            try:
                q.put_nowait(info)
            except queue.Full:
                # Drop oldest, push new; subscriber slow but still alive.
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
                try:
                    q.put_nowait(info)
                except queue.Full:
                    pass
        return info

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=32)
        with self._lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            try:
                self._subs.remove(q)
            except ValueError:
                pass


# ---------------------------------------------------------------------------
# Watcher thread
# ---------------------------------------------------------------------------

class Watcher(threading.Thread):
    """Spawns `xprop -spy -root _NET_ACTIVE_WINDOW` and feeds State on every
    focus change. Dies when the subprocess dies; main loop respawns."""

    def __init__(self, state: State) -> None:
        super().__init__(daemon=True, name="watcher")
        self.state = state
        self._stop = threading.Event()
        self._proc: subprocess.Popen | None = None

    def stop(self) -> None:
        self._stop.set()
        if self._proc and self._proc.poll() is None:
            try:
                self._proc.terminate()
            except OSError:
                pass

    def _emit(self, wid: str | None) -> None:
        if not wid:
            self.state.update({
                "window_id": None,
                "title": None, "wm_class": None, "wm_instance": None, "pid": None,
            })
            return
        info = _resolve_window(wid)
        info["window_id"] = wid
        self.state.update(info)

    def run(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            try:
                self._proc = subprocess.Popen(
                    ["xprop", "-spy", "-root", "_NET_ACTIVE_WINDOW"],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    text=True, bufsize=1,
                )
            except FileNotFoundError:
                log.error("xprop not installed — install xorg-xprop")
                self._stop.wait(5.0)
                continue
            log.info("xprop -spy started (pid=%s)", self._proc.pid)
            backoff = 1.0
            try:
                assert self._proc.stdout is not None
                for line in self._proc.stdout:
                    if self._stop.is_set():
                        break
                    self._emit(_parse_active(line))
            except OSError as e:
                log.warning("xprop read failed: %s", e)
            finally:
                if self._proc and self._proc.poll() is None:
                    self._proc.terminate()
            if self._stop.is_set():
                break
            log.warning("xprop -spy exited; restarting in %.1fs", backoff)
            self._stop.wait(backoff)
            backoff = min(backoff * 2, 10.0)


def initial_snapshot(state: State) -> None:
    """Seed state once at startup so /active works before the first focus
    change. xprop -spy emits the current window as its first line on most
    builds, but not all — belt-and-suspenders."""
    try:
        out = subprocess.run(
            ["xprop", "-root", "_NET_ACTIVE_WINDOW"],
            capture_output=True, text=True, timeout=2,
        ).stdout
    except (subprocess.SubprocessError, FileNotFoundError):
        return
    wid = _parse_active(out)
    if not wid:
        return
    info = _resolve_window(wid)
    info["window_id"] = wid
    state.update(info)


# ---------------------------------------------------------------------------
# HTTP / SSE
# ---------------------------------------------------------------------------

def make_handler(state: State):
    class Handler(BaseHTTPRequestHandler):
        # Quieter access log; access lines spam stdout otherwise.
        def log_message(self, fmt, *args):
            log.debug("%s - %s", self.address_string(), fmt % args)

        def do_GET(self) -> None:
            if self.path.startswith("/active"):
                self._send_json(200, state.snapshot())
                return
            if self.path.startswith("/stream"):
                self._send_stream()
                return
            if self.path.startswith("/healthz"):
                self._send_json(200, {"ok": True})
                return
            self._send_json(404, {"error": "not found", "path": self.path})

        def _send_json(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def _send_stream(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()

            q = state.subscribe()
            # Send current state immediately so the client doesn't sit on a
            # blank screen until the next focus change.
            try:
                self._sse_send(state.snapshot())
                while True:
                    try:
                        evt = q.get(timeout=15.0)
                    except queue.Empty:
                        # SSE comment as keep-alive — keeps NAT/proxies happy.
                        try:
                            self.wfile.write(b": keepalive\n\n")
                            self.wfile.flush()
                        except (BrokenPipeError, ConnectionResetError):
                            break
                        continue
                    if not self._sse_send(evt):
                        break
            finally:
                state.unsubscribe(q)

        def _sse_send(self, evt: dict) -> bool:
            try:
                payload = b"data: " + json.dumps(evt).encode() + b"\n\n"
                self.wfile.write(payload)
                self.wfile.flush()
                return True
            except (BrokenPipeError, ConnectionResetError):
                return False

    return Handler


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="Active-window broadcaster for Glass / iOS / Kindle.")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--log-level", default="INFO")
    args = ap.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(threadName)s: %(message)s",
    )

    state = State()
    initial_snapshot(state)

    watcher = Watcher(state)
    watcher.start()

    handler = make_handler(state)
    server = ThreadingHTTPServer((args.host, args.port), handler)
    server.daemon_threads = True

    def shutdown(*_):
        log.info("shutting down")
        watcher.stop()
        server.shutdown()
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    log.info("listening on http://%s:%d  (GET /active, GET /stream)",
             args.host, args.port)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        watcher.stop()
        watcher.join(timeout=2.0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
