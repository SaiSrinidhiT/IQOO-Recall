"""Exports Nomic Embed v1.5's WordPiece vocab and writes tokenizer parity fixtures (brief §3.2).

Outputs:
  app/src/main/assets/nomic_vocab.txt            one token per line, line number = token id
  app/src/test/resources/tokenizer_parity.json   20+ strings with Hugging Face token ids (WordPieceTokenizerTest)
  tools/ref/nomic_ref.json                       (--embed) reference embeddings for the on-device parity check

Usage: .venv/bin/python tools/tokenizer_parity.py [--embed]
"""
import argparse
import json
import pathlib

from transformers import AutoTokenizer

ROOT = pathlib.Path(__file__).resolve().parents[1]
CKPT = "nomic-ai/nomic-embed-text-v1.5"

SAMPLES = [
    "search_query: salary slips from the last three months",
    "search_document: Health insurance policy. expires 3 Oct 2026",
    "Home loan ki documents ready cheyyi",
    "What is my car insurance policy number?",
    "GOVERNMENT OF INDIA  DOB: 01/01/1990  MALE",
    "Policy No: 2856/12345678/00/000 — Sum Insured ₹5,00,000",
    "PAN: ABCPE1234F, IFSC SBIN0001234; Vehicle TS 09 EA 1234!",
    "Café naïve résumé Ångström",
    "मेरी पिछले महीने की सैलरी स्लिप दिखाओ",
    "आयकर विभाग INCOME TAX DEPARTMENT",
    "నా ఆరోగ్య బీమా పాలసీ ఎక్కడ ఉంది",
    "జీతం స్లిప్పులు గత మూడు నెలలు",
    "ఆఫ్‌లైన్ బిల్డ్ — zero‌width joiner test",
    "Emojis 😀 and symbols © ® ™ ± × ÷",
    "中文 字符 测试 mixed with english",
    "UPI transaction ID 412345678901 paid to shop@okaxis",
    "hyphen-ated words, quotes \"like this\" and 'this'",
    "   leading and trailing spaces\tand\ttabs\nnewlines   ",
    "ALL CAPS STATEMENT OF ACCOUNT OPENING BALANCE",
    "a" * 150 + " very long word above the 100 character limit",
    " ".join(["token"] * 200),
    "",
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--embed", action="store_true", help="also write reference embeddings (downloads the model)")
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(CKPT)
    print("tokenizer:", type(tok).__name__, "| lowercase:", getattr(tok, "do_lower_case", None))
    vocab = sorted(tok.get_vocab().items(), key=lambda kv: kv[1])
    assert [i for _, i in vocab] == list(range(len(vocab))), "vocab ids must be dense"
    for special in ("[CLS]", "[SEP]", "[PAD]", "[UNK]"):
        assert special in tok.get_vocab(), special
    out_vocab = ROOT / "app/src/main/assets/nomic_vocab.txt"
    out_vocab.write_text("\n".join(t for t, _ in vocab) + "\n", encoding="utf-8")
    print(f"wrote {out_vocab} ({len(vocab)} tokens)")

    cases = [{"text": s, "ids": tok(s, truncation=True, max_length=128)["input_ids"]} for s in SAMPLES]
    out_fix = ROOT / "app/src/test/resources/tokenizer_parity.json"
    out_fix.parent.mkdir(parents=True, exist_ok=True)
    out_fix.write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote {out_fix} ({len(cases)} cases)")

    if args.embed:
        import torch
        import torch.nn.functional as F
        from transformers import AutoModel

        model = AutoModel.from_pretrained(CKPT, trust_remote_code=True).eval()
        texts = SAMPLES[:6]
        enc = tok(texts, padding="max_length", truncation=True, max_length=128, return_tensors="pt")
        with torch.no_grad():
            hidden = model(**enc)[0]
        mask = enc["attention_mask"].unsqueeze(-1).float()
        pooled = (hidden * mask).sum(1) / mask.sum(1)  # mean pooling, as NomicEmbedder does on device
        vecs = F.normalize(pooled, dim=-1)
        ref = ROOT / "tools/ref/nomic_ref.json"
        ref.parent.mkdir(parents=True, exist_ok=True)
        ref.write_text(json.dumps([{"text": t, "vector": v.tolist()} for t, v in zip(texts, vecs)]), encoding="utf-8")
        print(f"wrote {ref}")


if __name__ == "__main__":
    main()
