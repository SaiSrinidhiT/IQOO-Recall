"""Precomputes SigLIP2 text vectors for gallery photo categories.

The phone runs only SigLIP2's vision tower. Each photo's image vector is compared with these text
vectors (same checkpoint as the AI Hub export, google/siglip2-base-patch16-224); the phone adds up the
probability of every description in a category and files the photo under the most likely one.
Writes app/src/main/assets/photo_labels.json.

Usage: .venv/bin/python tools/photo_labels.py
"""
import json
import pathlib

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoProcessor

ROOT = pathlib.Path(__file__).resolve().parents[1]
CKPT = "google/siglip2-base-patch16-224"

# Several descriptions per category: zero-shot accuracy improves when each class is described a few ways.
CATEGORIES = {
    "SELFIE": [
        "a selfie",
        "a close-up selfie of one person's face taken with a phone front camera",
        "a mirror selfie",
    ],
    "PEOPLE": [
        "a group photo of friends",
        "a family photo",
        "a photo of people at a party or event",
        "a photo of a person standing",
    ],
    "FOOD": [
        "a photo of food",
        "a plate of food at a restaurant",
        "a photo of a meal on a table",
        "a photo of a drink or dessert",
    ],
    "PLACES": [
        "a landscape photo",
        "a photo of a beach",
        "a photo of mountains",
        "a photo of a famous monument or temple",
        "a photo of a city street",
        "a scenic view from a trip",
    ],
    "SCREENSHOT": [
        "a screenshot of a phone app",
        "a chat screenshot",
        "a meme or social media post",
        "a screenshot of a website",
    ],
    "BILLS": [
        "a printed bill or invoice",
        "a restaurant bill",
        "a receipt",
    ],
    "DOCUMENTS": [
        "a scanned document page",
        "a photo of an identity card",
    ],
    "OTHER": [
        "a photo of an object",
        "a photo of a pet animal",
        "a blurry photo",
        "a photo of a vehicle",
        "a photo of a room interior",
    ],
}


def main() -> None:
    model = AutoModel.from_pretrained(CKPT).eval()
    processor = AutoProcessor.from_pretrained(CKPT)
    labels = [(cat, text) for cat, texts in CATEGORIES.items() for text in texts]
    # SigLIP2 was trained with max_length 64 padding; the HF model card uses the same.
    inputs = processor(text=[t for _, t in labels], padding="max_length", max_length=64, return_tensors="pt")
    with torch.no_grad():
        out = model.get_text_features(**inputs)
        feats = F.normalize(out if torch.is_tensor(out) else out.pooler_output, dim=-1)
    data = {
        "checkpoint": CKPT,
        "logit_scale": float(model.logit_scale.exp()),
        "logit_bias": float(model.logit_bias),
        "labels": [{"category": c, "text": t, "vector": [round(float(x), 6) for x in v]} for (c, t), v in zip(labels, feats)],
    }
    target = ROOT / "app/src/main/assets/photo_labels.json"
    target.write_text(json.dumps(data), encoding="utf-8")
    print(f"wrote {target}: {len(labels)} labels in {len(CATEGORIES)} categories, dim {feats.shape[1]}")


if __name__ == "__main__":
    main()
