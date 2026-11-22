"""Export the phase 0 benchmark models to LiteRT: WD-ViT tagger v3 with both outputs, and DINOv2-small.

WD-ViT returns (L2-normalized 768-d penultimate vector, 10,861 sigmoid tag scores), which is what the
product's WdTaggerRunner needs; the spike's export only returned the vector. Each model is written as
dynamic-range int8 (the shipping candidate) and fp16 weights (for the GPU and NPU paths).
Both graphs are rewritten for LiteRT's GPU delegate before export, with identical math (checked against
the original module on random input): timm attention's 5-D qkv reshape becomes three 4-D reshapes, and
DINOv2's CLS broadcast and position-embedding interpolation become constants for the fixed 224 input.
Writes models/*.tflite and models/wd_vit.selected_tags.csv; prints only sizes and parity numbers.

Run with the spike's LiteRT environment (litert-torch 0.10, jax<0.9):
  uv run --project <spike>/tools/visual-similarity-spike/litert python export_models.py
"""
import os
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "models"
OUT.mkdir(exist_ok=True)
os.environ.setdefault("HF_HOME", str(OUT / "hf"))

import torch  # noqa: E402
import litert_torch  # noqa: E402
from litert_torch.generative.quantize import quant_recipes  # noqa: E402

RECIPES = {"int8": quant_recipes.full_dynamic_recipe, "fp16": quant_recipes.full_fp16_recipe}


class WdBoth(torch.nn.Module):
    """WD tagger: normalized penultimate features and sigmoid tag scores from one forward pass."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        feats = self.model.forward_head(self.model.forward_features(x), pre_logits=True)
        probs = torch.sigmoid(self.model.get_classifier()(feats))
        return torch.nn.functional.normalize(feats, dim=-1), probs


class DinoPooled(torch.nn.Module):
    """DINOv2-small pooled output, L2-normalized (same as the spike's export)."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return torch.nn.functional.normalize(self.model(pixel_values=x).pooler_output, dim=-1)


def attention_4d(self, x, attn_mask=None, is_causal=False):
    """timm Attention.forward without 5-D tensors (the GPU delegate rejects rank > 4 reshapes)."""
    B, N, _ = x.shape
    q, k, v = (t.reshape(B, N, self.num_heads, self.head_dim).transpose(1, 2) for t in self.qkv(x).chunk(3, dim=-1))
    q, k = self.q_norm(q), self.k_norm(k)
    attn = ((q * self.scale) @ k.transpose(-2, -1)).softmax(dim=-1)
    x = (attn @ v).transpose(1, 2).reshape(B, N, self.attn_dim)
    return self.proj(self.norm(x))


class DinoEmbeddings224(torch.nn.Module):
    """Dinov2Embeddings for one 224 px image: CLS token and interpolated position embedding as constants."""

    def __init__(self, embeddings):
        super().__init__()
        self.patch = embeddings.patch_embeddings
        self.register_buffer("cls", embeddings.cls_token.detach().clone())
        with torch.no_grad():
            tokens = torch.zeros(1, 1 + (224 // 14) ** 2, embeddings.cls_token.shape[-1])
            self.register_buffer("pos", embeddings.interpolate_pos_encoding(tokens, 224, 224).detach().clone())

    def forward(self, pixel_values, bool_masked_pos=None):
        return torch.cat((self.cls, self.patch(pixel_values)), dim=1) + self.pos


def gpu_friendly(name: str, model: torch.nn.Module) -> torch.nn.Module:
    import types

    if name == "wd_vit":
        from timm.layers import Attention

        for module in model.modules():
            if isinstance(module, Attention):
                module.forward = types.MethodType(attention_4d, module)
    else:
        model.model.embeddings = DinoEmbeddings224(model.model.embeddings)
    return model


def build(name: str):
    """The fp32 export module and its input side, rewritten by gpu_friendly (same outputs as the original)."""
    model, size = build_original(name)
    return gpu_friendly(name, model), size


def build_original(name: str):
    if name == "wd_vit":
        import timm
        from huggingface_hub import hf_hub_download

        repo = "SmilingWolf/wd-vit-tagger-v3"
        shutil.copy(hf_hub_download(repo, "selected_tags.csv"), OUT / "wd_vit.selected_tags.csv")
        return WdBoth(timm.create_model(f"hf-hub:{repo}", pretrained=True).eval()), 448
    from transformers import AutoModel

    return DinoPooled(AutoModel.from_pretrained("facebook/dinov2-small").eval()), 224


if __name__ == "__main__":
    for name in ("wd_vit", "dinov2_s"):
        original, size = build_original(name)
        x = torch.rand(1, 3, size, size) * 2 - 1
        with torch.no_grad():
            before = original(x)
            model = gpu_friendly(name, original)
            after = model(x)
        for i, (a, b) in enumerate(zip(*(o if isinstance(o, tuple) else (o,) for o in (before, after)))):
            print(name, f"output {i} rewrite max abs diff {float((a - b).abs().max()):.2e}", flush=True)
        for precision, recipe in RECIPES.items():
            path = OUT / f"{name}.{precision}.tflite"
            if not path.exists():
                litert_torch.convert(model, (x,), quant_config=recipe()).export(str(path))
            print(name, precision, f"{path.stat().st_size / 1e6:.1f} MB", flush=True)
