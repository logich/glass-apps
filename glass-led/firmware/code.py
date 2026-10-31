# Glass LED - Adafruit Matrix Portal S3 receiver (CircuitPython 9+)
# Receives one UDP packet per matrix row and shows the frame on a HUB75 panel.
#
# Packet: [row: uint8][WIDTH * RGB565 little-endian]   (1 + WIDTH*2 bytes)
#
# settings.toml needs: CIRCUITPY_WIFI_SSID, CIRCUITPY_WIFI_PASSWORD
# UNTESTED on hardware - adjust WIDTH/HEIGHT/BIT_DEPTH to your panel.
import array
import os
import board
import displayio
import framebufferio
import rgbmatrix
import wifi
import socketpool
import bitmaptools

WIDTH, HEIGHT = 64, 32
BIT_DEPTH = 4
PORT = 7777

displayio.release_displays()
matrix = rgbmatrix.RGBMatrix(
    width=WIDTH, height=HEIGHT, bit_depth=BIT_DEPTH,
    rgb_pins=[board.MTX_R1, board.MTX_G1, board.MTX_B1,
              board.MTX_R2, board.MTX_G2, board.MTX_B2],
    addr_pins=[board.MTX_ADDRA, board.MTX_ADDRB, board.MTX_ADDRC, board.MTX_ADDRD],
    clock_pin=board.MTX_CLK, latch_pin=board.MTX_LAT,
    output_enable_pin=board.MTX_OE,
)
display = framebufferio.FramebufferDisplay(matrix, auto_refresh=False)

bitmap = displayio.Bitmap(WIDTH, HEIGHT, 65536)
shader = displayio.ColorConverter(input_colorspace=displayio.Colorspace.RGB565)
group = displayio.Group()
group.append(displayio.TileGrid(bitmap, pixel_shader=shader))
display.root_group = group

wifi.radio.connect(os.getenv("CIRCUITPY_WIFI_SSID"), os.getenv("CIRCUITPY_WIFI_PASSWORD"))
print("Send frames to", wifi.radio.ipv4_address, "port", PORT)

sock = socketpool.SocketPool(wifi.radio).socket(socketpool.SocketPool.AF_INET,
                                                socketpool.SocketPool.SOCK_DGRAM)
sock.bind(("0.0.0.0", PORT))
sock.settimeout(0)

frame = array.array("H", [0] * (WIDTH * HEIGHT))
buf = bytearray(1 + WIDTH * 2)
dirty = False

while True:
    try:
        n = sock.recv_into(buf)
    except OSError:  # nothing waiting
        n = 0
    if n == len(buf):
        row = buf[0]
        if row < HEIGHT:
            frame[row * WIDTH:(row + 1) * WIDTH] = array.array("H", bytes(buf[1:]))
            dirty = True
            if row == HEIGHT - 1:  # last row of a frame -> show it
                bitmaptools.arrayblit(bitmap, frame)
                display.refresh()
                dirty = False
    elif dirty:
        bitmaptools.arrayblit(bitmap, frame)
        display.refresh()
        dirty = False
