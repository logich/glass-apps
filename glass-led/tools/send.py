#!/usr/bin/env python3
"""Desktop reference sender: Glass MJPEG -> UDP rows for the Matrix Portal.
Same wire format and processing as the iOS app. Needs Pillow.

  send.py http://<glass-ip>:8080/stream <matrix-ip> [--w 64 --h 32 --port 7777]
"""
import argparse, io, socket, struct, time, urllib.request
from PIL import Image, ImageOps

GAMMA = [round(255 * (i / 255) ** 2.2) for i in range(256)]


def frames(url):
    buf = b""
    with urllib.request.urlopen(url, timeout=10) as r:
        while True:
            chunk = r.read(4096)
            if not chunk:
                return
            buf += chunk
            while True:
                s = buf.find(b"\xff\xd8")
                e = buf.find(b"\xff\xd9", s + 2) if s >= 0 else -1
                if s < 0 or e < 0:
                    buf = buf[s:] if s >= 0 else buf[-1:]
                    break
                yield buf[s:e + 2]
                buf = buf[e + 2:]


def encode(img, w, h, mirror, brightness):
    img = ImageOps.fit(img.convert("RGB"), (w, h))
    if mirror:
        img = ImageOps.mirror(img)
    px = img.load()
    rows = []
    for y in range(h):
        out = bytearray([y])
        for x in range(w):
            r, g, b = (GAMMA[int(c * brightness)] for c in px[x, y])
            out += struct.pack("<H", (r >> 3) << 11 | (g >> 2) << 5 | (b >> 3))
        rows.append(bytes(out))
    return rows


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("url"); ap.add_argument("host")
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--w", type=int, default=64); ap.add_argument("--h", type=int, default=32)
    ap.add_argument("--mirror", action="store_true")
    ap.add_argument("--brightness", type=float, default=0.5)
    a = ap.parse_args()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    n, t = 0, time.time()
    for jpg in frames(a.url):
        for row in encode(Image.open(io.BytesIO(jpg)), a.w, a.h, a.mirror, a.brightness):
            sock.sendto(row, (a.host, a.port))
        n += 1
        if time.time() - t > 2:
            print(f"{n / (time.time() - t):.1f} fps"); n, t = 0, time.time()
