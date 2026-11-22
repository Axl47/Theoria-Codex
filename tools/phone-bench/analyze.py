"""Summarize bench runs: latency, memory, thermals, and on-device numerics against host references.

  python3 analyze.py results/phone            # every run in the folder -> table on stdout + summary.json

Parity: the phone's outputs for the 64 parity images (32 synthetic, 32 photos) are compared with host
LiteRT running the same .tflite on the same floats (`exact`: phone fed host-preprocessed PNGs; `e2e`:
phone decoded and resized the JPEG itself), and with fp32 PyTorch. WD-ViT tag agreement uses the
general/character tags at score >= 0.35 (WD v3's usual threshold), rating tags excluded.
"""
import csv
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REF = HERE / "work" / "ref"
THRESHOLD = 0.35


def load_dump(path: Path, meta: dict) -> dict[str, list[np.ndarray]]:
    sizes, names = meta["sizes"], meta["names"]
    raw = np.fromfile(path, dtype="<f4")
    per = sum(sizes)
    out = {}
    for i, name in enumerate(names):
        row = raw[i * per:(i + 1) * per]
        out[name] = np.split(row, np.cumsum(sizes)[:-1])
    return out


def load_ref(path: Path) -> dict[str, list[np.ndarray]]:
    z = np.load(path)
    keys = sorted(k for k in z.files if k.startswith("out"))
    return {str(n): [z[k][i] for k in keys] for i, n in enumerate(z["names"])}


def tag_mask() -> np.ndarray:
    with (HERE / "models" / "wd_vit.selected_tags.csv").open() as f:
        return np.array([row["category"] != "9" for row in csv.DictReader(f)])


def compare(device: dict, ref: dict, mask: np.ndarray | None) -> dict:
    names = sorted(set(device) & set(ref))
    if not names:
        return {}
    cos = []
    jacc, recall = [], []
    for n in names:
        a, b = device[n][0], ref[n][0]
        cos.append(float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12)))
        if mask is not None and len(device[n]) > 1:
            pa = set(np.flatnonzero((device[n][1] >= THRESHOLD) & mask))
            pb = set(np.flatnonzero((ref[n][1] >= THRESHOLD) & mask))
            jacc.append(len(pa & pb) / len(pa | pb) if pa | pb else 1.0)
            recall.append(len(pa & pb) / len(pb) if pb else 1.0)
    cos = np.array(cos)
    r = {"n": len(names), "cos_mean": float(cos.mean()), "cos_min": float(cos.min()),
         "cos_ge_099": float((cos >= 0.99).mean())}
    if jacc:
        r |= {"tag_jaccard_mean": float(np.mean(jacc)), "tag_recall_mean": float(np.mean(recall))}
    return r


def summarize_run(path: Path, mask: np.ndarray) -> dict:
    r = json.loads(path.read_text())
    spec = r["spec"]
    model_file, kind = spec["model"], spec["kind"]
    base = model_file.split(".")[0]
    out = {"run": spec["run"], "model": model_file, "acc": spec["accelerator"], "threads": spec["threads"],
           "precision": spec["precision"], "mode": spec["mode"], "error": r.get("error", "").splitlines()[:1],
           "load_ms": r.get("load_ms"), "first_ms": r.get("first_inference_ms"),
           "peak_pss_mb": r.get("peak_pss_kb", 0) / 1024,
           "vm_hwm_mb": (r.get("memory_end") or {}).get("vm_hwm_kb", 0) / 1024,
           "accelerators": r.get("available_accelerators")}
    e2e = r.get("e2e", {})
    if e2e:
        out["n"] = e2e["summary"]["n"]
        for key in ("infer", "total"):
            s = e2e["summary"][key]
            out[f"{key}_median"], out[f"{key}_p90"] = s.get("median"), s.get("p90")
        # GPU run() returns before the work finishes and readFloat() waits for it, so inference is run + read.
        model_ms = np.array(e2e["infer_ms"]) + np.array(e2e["read_ms"])
        out["model_median"], out["model_p90"] = float(np.median(model_ms)), float(np.percentile(model_ms, 90))
        for key in ("decode_ms", "prep_ms", "read_ms", "native_ms"):
            out[f"{key}_median"] = float(np.median(e2e[key])) if e2e[key] else None
    tel = r.get("telemetry", [])
    if tel:
        out["thermal_max"] = max(t["thermal_status"] for t in tel)
        temps = [t["battery_temp_c"] for t in tel if t.get("battery_temp_c") is not None]
        out["battery_temp_start_end"] = (temps[0], temps[-1]) if temps else None
        heads = [t["headroom_0s"] for t in tel if t.get("headroom_0s") is not None]
        out["headroom_min"] = min(heads) if heads else None
    out["battery_level_before_after"] = (r["before"].get("battery_level"), r["after"].get("battery_level"))
    if spec["mode"] == "soak" and e2e:
        at, total = np.array(e2e["at_ms"]) / 60000, np.array(e2e["total_ms"])
        out["per_minute_median_total"] = [round(float(np.median(total[(at >= m) & (at < m + 1)])), 1)
                                          for m in range(int(at.max()) + 1) if ((at >= m) & (at < m + 1)).any()]
        out["images_per_minute"] = float(len(total) / max(at.max(), 1e-9))
    refs = {"host": REF / f"{model_file}.npz", "torch": REF / f"{base}.torch.npz"}
    m = mask if kind == "WD" else None
    for label, meta in (("exact", r.get("parity_exact")), ("e2e", e2e.get("parity"))):
        dump = path.with_name(f"{spec['run']}.{label}.bin")
        if meta and dump.exists() and meta["names"]:
            device = load_dump(dump, meta)
            for ref_label, ref_path in refs.items():
                if ref_path.exists():
                    out[f"parity_{label}_vs_{ref_label}"] = compare(device, load_ref(ref_path), m)
    return out


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    mask = tag_mask()
    rows = [summarize_run(p, mask) for p in sorted(folder.glob("*.json")) if p.name != "summary.json"]
    (folder / "summary.json").write_text(json.dumps(rows, indent=1))
    for row in rows:
        def f(v, d=0):
            return "-" if v is None else f"{v:.{d}f}"
        par = row.get("parity_exact_vs_host") or {}
        e2e = row.get("parity_e2e_vs_host") or {}
        print(f"{row['run']:<18} load {f(row['load_ms'])} first {f(row['first_ms'])} "
              f"model {f(row.get('model_median'))}/{f(row.get('model_p90'))} e2e {f(row.get('total_median'))}/{f(row.get('total_p90'))} "
              f"n={row.get('n', 0)} pss {f(row['peak_pss_mb'])}MB hwm {f(row['vm_hwm_mb'])}MB "
              f"cos exact {f(par.get('cos_mean'), 4)}/{f(par.get('cos_min'), 4)} e2e {f(e2e.get('cos_mean'), 4)}/{f(e2e.get('cos_min'), 4)} "
              f"tagJ {f(par.get('tag_jaccard_mean'), 3)}/{f(e2e.get('tag_jaccard_mean'), 3)} "
              f"{'ERR ' + row['error'][0][:120] if row['error'] else ''}")
