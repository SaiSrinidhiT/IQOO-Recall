"""Precomputes SigLIP2 text vectors for the gatekeeper labels (brief §3.1).

Only the vision tower runs on the phone. This script embeds the fixed label set with the text tower of
the same checkpoint Qualcomm AI Hub exported (google/siglip2-base-patch16-224, per the AI Hub model
card) and writes app/src/main/assets/siglip_labels.json.

Optional checks:
  --images a.jpg b.jpg c.jpg   write reference image embeddings to tools/ref/siglip_ref.json, for the
                               on-device parity check (target cosine ≥ 0.99).
  --tflite models/siglip2_vision.tflite
                               run the AI Hub export on the Mac with LiteRT and compare it with the HF
                               vision tower under both input conventions, to confirm the checkpoint is
                               the same and to pick model_config.json "siglip.input_range".

Usage: .venv/bin/python tools/siglip_labels.py [--images ...] [--tflite PATH]
"""
import argparse
import json
import pathlib

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image
from transformers import AutoModel, AutoProcessor

ROOT = pathlib.Path(__file__).resolve().parents[1]
CKPT = "google/siglip2-base-patch16-224"

LABELS = [
    ("a photo of an identity card", True),
    ("a scanned document page", True),
    ("a screenshot of a payment receipt", True),
    ("a printed bill or invoice", True),
    ("a bank statement page", True),
    ("a salary slip", True),
    ("an insurance policy document", True),
    ("a medical report", True),
    ("a hospital bill", True),
    ("a selfie", False),
    ("a photo of people", False),
    ("a landscape photo", False),
    ("a photo of food", False),
    ("a meme or social media post", False),
    ("a chat screenshot", False),
]


def _tensor(out):
    """transformers 5 returns an output object from get_*_features; 4.x returned the tensor."""
    return out if torch.is_tensor(out) else out.pooler_output


def hf_image_embeddings(model, processor, paths):
    images = [Image.open(p).convert("RGB") for p in paths]
    inputs = processor(images=images, return_tensors="pt")
    with torch.no_grad():
        feats = _tensor(model.get_image_features(**inputs))
    return F.normalize(feats, dim=-1).numpy(), images


def tflite_embeddings(path, images):
    from ai_edge_litert.interpreter import Interpreter

    interp = Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    shape = inp["shape"]
    nchw = len(shape) == 4 and shape[1] == 3
    size = int(shape[2] if nchw else shape[1])
    print("TFLite input", inp["name"], shape, inp["dtype"], "| output", out["name"], out["shape"])
    results = {}
    for name, fn in (("zero_one", lambda x: x), ("minus_one_one", lambda x: x * 2 - 1)):
        vecs = []
        for im in images:
            x = np.asarray(im.resize((size, size), Image.BILINEAR), dtype=np.float32) / 255.0
            x = fn(x)
            x = np.transpose(x, (2, 0, 1))[None] if nchw else x[None]
            interp.set_tensor(inp["index"], x.astype(inp["dtype"]))
            interp.invoke()
            v = interp.get_tensor(out["index"]).reshape(-1).astype(np.float32)
            vecs.append(v / np.linalg.norm(v))
        results[name] = np.stack(vecs)
    return results


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", nargs="*", default=[])
    ap.add_argument("--tflite")
    args = ap.parse_args()

    model = AutoModel.from_pretrained(CKPT).eval()
    processor = AutoProcessor.from_pretrained(CKPT)
    texts = [t for t, _ in LABELS]
    # SigLIP2 was trained with max_length 64 padding; the HF model card uses the same.
    inputs = processor(text=texts, padding="max_length", max_length=64, return_tensors="pt")
    with torch.no_grad():
        feats = F.normalize(_tensor(model.get_text_features(**inputs)), dim=-1)
    out = {
        "checkpoint": CKPT,
        "logit_scale": float(model.logit_scale.exp()),
        "logit_bias": float(model.logit_bias),
        "labels": [{"text": t, "is_document": d, "vector": [round(float(x), 6) for x in v]} for (t, d), v in zip(LABELS, feats)],
    }
    target = ROOT / "app/src/main/assets/siglip_labels.json"
    target.write_text(json.dumps(out), encoding="utf-8")
    print(f"wrote {target}: {len(LABELS)} labels, dim {feats.shape[1]}, scale {out['logit_scale']:.3f}, bias {out['logit_bias']:.3f}")

    if args.images:
        hf, images = hf_image_embeddings(model, processor, args.images)
        ref = ROOT / "tools/ref/siglip_ref.json"
        ref.parent.mkdir(parents=True, exist_ok=True)
        ref.write_text(json.dumps([{"image": pathlib.Path(p).name, "vector": v.tolist()} for p, v in zip(args.images, hf)]))
        print(f"wrote {ref}")
        sims = hf @ feats.numpy().T
        for p, row in zip(args.images, sims):
            best = int(row.argmax())
            print(f"  {pathlib.Path(p).name}: top label '{LABELS[best][0]}' ({row[best]:.3f})")
        if args.tflite:
            for name, vecs in tflite_embeddings(args.tflite, images).items():
                cos = (vecs * hf).sum(1)
                print(f"  TFLite vs HF with input_range={name}: cosine per image {np.round(cos, 4).tolist()}")


if __name__ == "__main__":
    main()
