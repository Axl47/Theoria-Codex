#!/usr/bin/env python3
"""Writes the vision acceptance's fixture pictures: seeded black line drawings on white, as PNGs, standard library only.

Usage: scripts/vision_fixtures.py <directory> [count]

Pictures COLOUR_FROM to COLOUR_FROM + 5 are filled colour shapes instead, so the line drawings' monochrome is common in the
Library without being on nearly all of it. WD tagger v3 reads every line drawing as monochrome, greyscale and no_humans
(host LiteRT scores each at 0.6 or more) and no colour picture as monochrome, which is what VisionAcceptanceActivity
relies on. Synthetic by construction, so no provider media is involved.
"""
import math
import random
import struct
import sys
import zlib
from pathlib import Path

SIDE = 360
STROKE = 3
COLOUR_FROM = 18


def colour(rng: random.Random) -> list[bytearray]:
    """Filled rectangles in random saturated colours on a coloured ground, as RGB rows."""
    height = rng.choice([270, 300, 360])
    ground = bytes(rng.randrange(256) for _ in range(3))
    rows = [bytearray(ground * SIDE) for _ in range(height)]
    for _ in range(rng.randrange(4, 9)):
        x0, x1 = sorted(rng.randrange(SIDE) for _ in range(2))
        y0, y1 = sorted(rng.randrange(height) for _ in range(2))
        fill = bytes(rng.choice([0, 255, rng.randrange(256)]) for _ in range(3))
        for y in range(y0, y1):
            rows[y][x0 * 3:x1 * 3] = fill * (x1 - x0)
    return rows


def drawing(rng: random.Random) -> list[bytearray]:
    """Black line drawings on white, as RGB rows."""
    height = rng.choice([270, 300, 360])
    rows = [bytearray(b"\xff" * SIDE * 3) for _ in range(height)]

    def dot(x: float, y: float) -> None:
        for dy in range(STROKE):
            for dx in range(STROKE):
                px, py = int(x) + dx - 1, int(y) + dy - 1
                if 0 <= px < SIDE and 0 <= py < height:
                    rows[py][px * 3:px * 3 + 3] = b"\x00\x00\x00"

    def line(x0: float, y0: float, x1: float, y1: float) -> None:
        steps = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for i in range(steps + 1):
            dot(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)

    for _ in range(rng.randrange(6, 14)):
        x0, x1 = sorted(rng.randrange(SIDE) for _ in range(2))
        y0, y1 = sorted(rng.randrange(height) for _ in range(2))
        kind = rng.randrange(3)
        if kind == 0:
            cx, cy, rx, ry = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
            for i in range(720):
                dot(cx + rx * math.cos(i * math.pi / 360), cy + ry * math.sin(i * math.pi / 360))
        elif kind == 1:
            for a, b in (((x0, y0), (x1, y0)), ((x1, y0), (x1, y1)), ((x1, y1), (x0, y1)), ((x0, y1), (x0, y0))):
                line(*a, *b)
        else:
            line(x0, rng.randrange(height), x1, rng.randrange(height))
    return rows


def png(rows: list[bytearray]) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", len(rows[0]) // 3, len(rows), 8, 2, 0, 0, 0)  # 8-bit RGB
    pixels = zlib.compress(b"".join(b"\x00" + bytes(row) for row in rows))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")


def main() -> None:
    directory = Path(sys.argv[1])
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    directory.mkdir(parents=True, exist_ok=True)
    rng = random.Random(7)
    for index in range(count):
        picture = colour(rng) if COLOUR_FROM <= index < COLOUR_FROM + 6 else drawing(rng)
        (directory / f"fixture-{index:02d}.png").write_bytes(png(picture))
    print(f"{count} fixtures in {directory}")


if __name__ == "__main__":
    main()
