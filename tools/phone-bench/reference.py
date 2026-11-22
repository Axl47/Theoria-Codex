"""Host reference outputs for the parity inputs (inputs/wd448, inputs/dino224), per model file.

For each .tflite in models/ it runs host LiteRT (XNNPACK, 4 threads) on exactly the floats the phone
builds from the same PNGs, and also the fp32 PyTorch model, writing work/ref/<model file>.npz and
work/ref/<model>.torch.npz with `names` and `out0`, `out1`, ... (model output order). Prints counts.

Run with the spike's LiteRT environment, like export_models.py.
"""
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
REF = HERE / "work" / "ref"
DIRS = {"wd_vit": HERE / "inputs" / "wd448", "dinov2_s": HERE / "inputs" / "dino224"}
MEAN, STD = np.array([0.485, 0.456, 0.406]), np.array([0.229, 0.224, 0.225])


def tensor(model: str, png: Path) -> np.ndarray:
    """The phone's float conversion (Preprocess.kt toFloats), NCHW with a batch of one."""
    rgb = np.asarray(Image.open(png).convert("RGB"), dtype=np.float32)
    x = rgb[..., ::-1] / 127.5 - 1 if model == "wd_vit" else (rgb / 255 - MEAN) / STD
    return np.ascontiguousarray(x.transpose(2, 0, 1)[None], dtype=np.float32)


def run_tflite(path: Path, xs: list[np.ndarray]) -> list[np.ndarray]:
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=str(path), num_threads=4)
    it.allocate_tensors()
    inp, outs = it.get_input_details()[0], sorted(it.get_output_details(), key=lambda d: d["name"])
    rows: list[list[np.ndarray]] = [[] for _ in outs]
    for x in xs:
        it.set_tensor(inp["index"], x)
        it.invoke()
        for row, d in zip(rows, outs):
            row.append(it.get_tensor(d["index"])[0].copy())
    return [np.stack(r) for r in rows]


def run_torch(model: str, xs: list[np.ndarray]) -> list[np.ndarray]:
    import torch
    from export_models import build

    net, _ = build(model)
    with torch.inference_mode():
        outs = [net(torch.from_numpy(x)) for x in xs]
    outs = [o if isinstance(o, tuple) else (o,) for o in outs]
    return [np.stack([o[k][0].numpy() for o in outs]) for k in range(len(outs[0]))]


if __name__ == "__main__":
    REF.mkdir(parents=True, exist_ok=True)
    for model, folder in DIRS.items():
        pngs = sorted(folder.glob("*.png"))
        names = np.array([p.stem for p in pngs])
        xs = [tensor(model, p) for p in pngs]
        for path in sorted((HERE / "models").glob(f"{model}.*.tflite")):
            outs = run_tflite(path, xs)
            np.savez(REF / f"{path.name}.npz", names=names, **{f"out{k}": o for k, o in enumerate(outs)})
            print(path.name, len(names), [o.shape for o in outs], flush=True)
        outs = run_torch(model, xs)
        np.savez(REF / f"{model}.torch.npz", names=names, **{f"out{k}": o for k, o in enumerate(outs)})
        print(model, "torch", len(names), [o.shape for o in outs], flush=True)
