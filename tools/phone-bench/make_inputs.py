"""Build the benchmark's safe-by-construction inputs. Prints counts and sizes only.

- 100 synthetic images (seeded gradients, shapes and blurred noise) and the first 100 photos of the
  public INRIA Holidays set (streamed; the rest of the archive is never downloaded), each saved as a
  JPEG thumbnail whose longest side is 360 px, like the app's cached thumbnails: inputs/jpeg/.
- For the first PARITY images of each kind, the exact model input as lossless PNG, preprocessed on
  the host: inputs/wd448/ (white-padded square, bicubic 448, RGB) and inputs/dino224/ (bicubic
  shortest side 256, center crop 224, RGB). The phone only rescales these to floats, so comparing
  outputs isolates the runtime's numerics from decode and resampling differences.

No agent opens or describes these files; only scripts read them.
"""
import random
import tarfile
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
INPUTS = HERE / "inputs"
HOLIDAYS = "ftp://ftp.inrialpes.fr/pub/lear/douze/data/jpg1.tar.gz"
PER_KIND = 100
PARITY = 32
THUMB = 360


def thumb(im: Image.Image) -> Image.Image:
    im = im.convert("RGB")
    scale = THUMB / max(im.size)
    return im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))), Image.LANCZOS)


def synthetic(rng: random.Random) -> Image.Image:
    aspect = rng.uniform(0.5, 2.0)
    w, h = (THUMB, round(THUMB / aspect)) if aspect >= 1 else (round(THUMB * aspect), THUMB)
    a, b = np.array([rng.randrange(256) for _ in range(3)]), np.array([rng.randrange(256) for _ in range(3)])
    t = np.linspace(0, 1, w)[None, :, None] if rng.random() < 0.5 else np.linspace(0, 1, h)[:, None, None]
    base = np.broadcast_to(a * (1 - t) + b * t, (h, w, 3)).astype(np.uint8)
    im = Image.fromarray(base)
    draw = ImageDraw.Draw(im)
    for _ in range(rng.randrange(3, 12)):
        box = sorted(rng.sample(range(w), 2)), sorted(rng.sample(range(h), 2))
        xy = [box[0][0], box[1][0], box[0][1], box[1][1]]
        fill = tuple(rng.randrange(256) for _ in range(3))
        match rng.randrange(3):
            case 0:
                draw.ellipse(xy, fill=fill)
            case 1:
                draw.rectangle(xy, fill=fill)
            case _:
                draw.line(xy, fill=fill, width=rng.randrange(2, 16))
    noise = np.random.default_rng(rng.randrange(1 << 30)).integers(0, 256, (h // 8 + 1, w // 8 + 1, 3), dtype=np.uint8)
    noise = Image.fromarray(noise).resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(2))
    return Image.blend(im, noise, rng.uniform(0.0, 0.35))


def holidays(n: int):
    """Yield the first n photos of the Holidays archive, streaming and stopping early."""
    with urllib.request.urlopen(HOLIDAYS, timeout=120) as resp, tarfile.open(fileobj=resp, mode="r|gz") as tar:
        for member in tar:
            if member.isfile() and member.name.lower().endswith(".jpg"):
                yield Image.open(tar.extractfile(member)).copy()
                n -= 1
                if n == 0:
                    return


def wd448(im: Image.Image) -> Image.Image:
    side = max(im.size)
    canvas = Image.new("RGB", (side, side), (255, 255, 255))
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    return canvas.resize((448, 448), Image.BICUBIC)


def dino224(im: Image.Image) -> Image.Image:
    scale = 256 / min(im.size)
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.BICUBIC)
    left, top = (im.width - 224) // 2, (im.height - 224) // 2
    return im.crop((left, top, left + 224, top + 224))


if __name__ == "__main__":
    for sub in ("jpeg", "wd448", "dino224"):
        (INPUTS / sub).mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261122)
    sources = {"syn": (synthetic(rng) for _ in range(PER_KIND)), "hol": holidays(PER_KIND)}
    for kind, images in sources.items():
        count = 0
        for i, im in enumerate(images):
            name = f"{kind}_{i:03d}"
            jpg = INPUTS / "jpeg" / f"{name}.jpg"
            thumb(im).save(jpg, quality=90)
            if i < PARITY:
                decoded = Image.open(jpg).convert("RGB")  # same bytes the phone decodes
                wd448(decoded).save(INPUTS / "wd448" / f"{name}.png")
                dino224(decoded).save(INPUTS / "dino224" / f"{name}.png")
            count += 1
        print(kind, count, flush=True)
    for sub in ("jpeg", "wd448", "dino224"):
        files = list((INPUTS / sub).iterdir())
        print(sub, len(files), f"{sum(f.stat().st_size for f in files) / 1e6:.1f} MB")
