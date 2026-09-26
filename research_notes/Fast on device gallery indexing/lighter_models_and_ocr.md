# Lighter/faster alternatives for the document gate (Stage A) and OCR (Stage B) in an offline Android gallery indexer

Scope: iQOO 15 (Snapdragon 8 Elite Gen 5). Current Stage A = SigLIP2 base-patch16-224 image encoder + zero-shot text-label comparison. Current Stage B = ML Kit Text Recognition v2 (Latin + Devanagari, bundled) on bitmaps up to 2048 px, plus Tesseract (tel+eng) as a Telugu fallback. All latency figures below name the device and runtime. "AI Hub" means Qualcomm AI Hub profiling (Samsung "for Galaxy" variants of the SoC, NPU as compute unit unless stated).

## Stage A candidates: size, latency, accuracy, text encoder, license, AI Hub availability

### Takeaway
SigLIP2 is already the most permissive (Apache-2.0) and strongest option at its size. The easiest speed-up that keeps text search is the **SigLIP2 B/32-256** checkpoint: 64 patch tokens instead of 196, for 74.0% instead of 78.2% ImageNet zero-shot. **MobileCLIP / MobileCLIP2** are 3-8x faster than ViT-B/16 on published iPhone numbers, but Apple's model license allows **non-commercial research only**, so they can't ship in a product. Permissive small CLIP-style options are TinyCLIP (MIT), OpenVision Ti/S (Apache-2.0) and Meta PE-Core T/S (Apache-2.0). A dedicated MobileNet-class gate runs in about 0.3 ms on the 8 Elite Gen 5 NPU.

### Cited Findings

**Current model: SigLIP2 base (the baseline)**
- The SigLIP2 families are ViT-B/16 (86M), L/16 (303M), So400m/14 (400M) and g/16 (1B) — [Emergent Mind summary of SigLIP2 paper](https://www.emergentmind.com/topics/siglip2-model); [SigLIP 2 paper](https://arxiv.org/html/2502.14786v1)
- **No SigLIP2 checkpoint is smaller than ViT-B.** The B variants differ in patch size and resolution. ImageNet zero-shot: **B/32 @256 = 74.0%, B/16 @224 = 78.2%, B/16 @256 = 79.1%**. Software is Apache-2.0 and other materials CC-BY-4.0 — [big_vision SigLIP2 README](https://github.com/google-research/big_vision/blob/main/big_vision/configs/proj/image_text/README_siglip2.md)
- SigLIP2 is on Qualcomm AI Hub as **google/siglip2-base-patch16-224**, the same checkpoint the app uses: 224×224 input, 64-token text. Image encoder is **352 MB float / 92.8 MB w8a16**; text encoder is **1.05 GB float / 461 MB w8a16**; license Apache-2.0. **The page publishes no latency** ("not supported on any All Models chipset") — [AI Hub SigLIP2](https://aihub.qualcomm.com/models/siglip2)
- Best proxy for current Stage A cost: AI Hub **OpenAI-CLIP ViT-B/16 @224** (150M params, 571 MB float, MIT) on **Snapdragon 8 Elite Gen 5**: **13.1 ms** QNN_DLC float, **11.5 ms** QNN_DLC w8a16, **12.8 ms** TFLite float, **13.2 ms** ONNX float. On 8 Elite (Gen 1): **16.7 ms** ONNX float. All on the NPU. The page profiles a "unified" image+text model, so image-only cost is below these figures — [qualcomm/OpenAI-Clip HF card](https://huggingface.co/qualcomm/OpenAI-Clip)
- Google/LiteRT on 8 Elite Gen 5: "over 56 models run in under 5ms with the NPU, while only 13 models achieve that on the CPU". NPU latency is ~1-20% of the CPU baseline and GPU ~5-70% — [Google Developers Blog: LiteRT on Qualcomm NPU](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/) (numbers from the search snippet; the page was not fetched in full)

**MobileCLIP (CVPR 2024) and MobileCLIP2 (TMLR, Aug 2025)**
- MobileCLIP paper latencies were measured on **iPhone 12 Pro Max, Core ML Tools 7.0, batch 1**. Input: S0 = 224, S1 = 256, S2 = 256, B = 224. Image+text latency: **S0 1.5+1.6 ms; S2 3.6+3.3 ms; OpenAI ViT-B/16 11.5+3.3 ms; TinyCLIP-39M/16 5.2+1.9 ms** — [MobileCLIP paper, arXiv 2311.17049](https://arxiv.org/html/2311.17049)
- Params (image+text) and ImageNet zero-shot — [apple/ml-mobileclip README](https://github.com/apple/ml-mobileclip):
  - MobileCLIP-S0: 11.4M + 42.4M, 67.8%
  - MobileCLIP-B (LT): 86.3M + 63.4M, 77.2%
  - MobileCLIP2-S0: 11.4M + 63.4M, 1.5+3.3 ms, **71.5%**
  - MobileCLIP2-S2: 35.7M + 63.4M, 3.6+3.3 ms, **77.2%**
  - MobileCLIP2-B: 86.3M + 63.4M, 10.4+3.3 ms, 79.4%
  - MobileCLIP2-S4: 321.6M + 123.6M, 19.6+6.6 ms, 81.9%
- MobileCLIP-S1: 21.5M + 63.4M, 2.5+3.3 ms, 72.6% ImageNet zero-shot, license `apple-amlr` — [apple/MobileCLIP-S1 HF card](https://huggingface.co/apple/MobileCLIP-S1)
- MobileCLIP2-S0: license `apple-amlr`, PyTorch .pt checkpoint only. The card says it matches OpenAI ViT-B/16 while being "4.8x faster and 2.8x smaller" — [apple/MobileCLIP2-S0 HF card](https://huggingface.co/apple/MobileCLIP2-S0)
- **License (critical):** the code is MIT, but the weights fall under the "Apple ML Research Model TOU". That license grants use "exclusively for Research Purposes", defined as "non-commercial scientific research and academic development activities". It states that "Research Purposes does not include any commercial exploitation, product development or use in any commercial product or service" — [LICENSE_MODELS](https://github.com/apple/ml-mobileclip/blob/main/LICENSE_MODELS). The DataCompDR data is CC-BY-NC-ND — [ml-mobileclip README](https://github.com/apple/ml-mobileclip)
- **MobileCLIP is not in the Qualcomm AI Hub catalog.** Multimodal entries are OpenAI-Clip and SigLIP2 only — [qualcomm/ai-hub-models](https://github.com/qualcomm/ai-hub-models)
- Unverified: a search snippet said MobileCLIP2-S2 runs in "~450 ms" and is 143 MB, against CLIP ViT-B/32 at ~900 ms and 345 MB, on a Samsung Galaxy S21 Ultra (runtime unknown), attributed to the VisionAId paper. **Fetching the PDF did not confirm these numbers**, so treat them as unverified — [VisionAId, arXiv 2607.02371](https://arxiv.org/pdf/2607.02371)

**TinyCLIP (ICCV 2023, Microsoft)** — [wkcn/TinyCLIP](https://github.com/wkcn/TinyCLIP)
- Image/text params and ImageNet zero-shot:
  - ViT-8M/16 + Text-3M: **41.1%**
  - ViT-39M/16 + Text-19M: 63.5%
  - ViT-40M/32 + Text-19M: 59.8%
  - ViT-22M/32 + Text-10M (auto): 53.7%
  - ResNet-19M + Text-19M: 56.4%
- In that table's "Resolution" column, 16/32 is the patch size.
- License: MIT per the HF model card — [wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M). An ONNX export exists — [onnx-community TinyCLIP ONNX](https://huggingface.co/onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX)
- Only published mobile latency: TinyCLIP-39M/16 at 5.2 ms image on iPhone 12 Pro Max (Core ML), from the MobileCLIP paper above.

**OpenVision (UCSC, ICCV 2025): permissive, has tiny variants**
- Apache-2.0, "26 different models spanning between 5.9 million parameters to 632.1 million parameters". Vision variants: Ti/16, S/16, B/16, L/14, So400m/14, H/14. Ti/16 "keeps 87%" of CLIP-L/14's average score at ~50× smaller size — [VentureBeat](https://venturebeat.com/ai/new-fully-open-source-vision-encoder-openvision-arrives-to-improve-on-openais-clip-googles-siglip); [UCSC-VLAA/OpenVision](https://github.com/UCSC-VLAA/OpenVision)
- Caveat: OpenVision was evaluated mainly as an encoder for LLaVA-style VLMs. I found no mobile latency or zero-shot ImageNet figure for Ti/S.

**Meta Perception Encoder PE-Core T/S (added Jul 2025)**
- ImageNet zero-shot: **PE-Core-T16-384 = 62.1%, PE-Core-S16-384 = 72.7%**. COCO T2I: T = 33.0, S = 42.6. Code is Apache-2.0 — [facebookresearch/perception_models README](https://github.com/facebookresearch/perception_models/blob/main/README.md). The timm T16-384 card lists Apache-2.0 and 384 px — [timm/PE-Core-T-16-384](https://huggingface.co/timm/PE-Core-T-16-384)
- Caveat: these run at **384 px** (576 patch tokens at patch 16). Compute is therefore higher than the parameter count suggests. The parameter figures in the fetched card looked copied from the B model, so I don't report them.

**Dedicated classifiers (no text search)**
- AI Hub **MobileNet-v3-Small** (2.54M params, 9.71 MB float, 224×224, BSD-3-Clause) on **8 Elite Gen 5**: **0.256 ms** ONNX float, **0.31-0.32 ms** QNN_DLC/TFLite. On 8 Elite: 0.29-0.43 ms. NPU — [qualcomm/MobileNet-v3-Small](https://huggingface.co/qualcomm/MobileNet-v3-Small)
- AI Hub also lists MobileNet-v2, MobileNet-v3-Large, EfficientNet-B0, EfficientNet-Lite4, EfficientViT-b2-cls / l2-cls, ShuffleNet-v2, SqueezeNet-1.1 and MNASNet05 (latencies not fetched) — [qualcomm/ai-hub-models](https://github.com/qualcomm/ai-hub-models)
- **MobileNetV4** (Google, ECCV 2024): MNv4-Conv-S = 73.8% ImageNet top-1, **1.8 ms on Samsung S23 CPU**, 2.4 ms on Pixel 6 CPU. MNv4-Hybrid-L (distilled) = 87.0% at 3.8 ms on Pixel 8 EdgeTPU — [MobileNetV4 paper, arXiv 2404.10518](https://arxiv.org/pdf/2404.10518) (figures via search summary / Synced)

### Inferences
- **Current Stage A cost.** SigLIP2-B/16-224 is architecturally a ViT-B/16 at 224, the same class as AI Hub's CLIP ViT-B/16. So Stage A should cost roughly **~10-13 ms per image on the 8 Elite Gen 5 NPU**, if it actually runs on the NPU through QNN or LiteRT's Qualcomm accelerator. If it runs on CPU/XNNPACK or GPU, expect several times more (NPU ≈ 1-20% of CPU per the LiteRT blog). **Before swapping models, check which delegate the app actually uses.** Moving the same model to the NPU may be the largest single win.
- **Relative speed of alternatives.** Carrying the Apple iPhone ratios over to Snapdragon (unverified): MobileCLIP2-S0 ≈ 7-8× faster than a ViT-B/16 image encoder, and S2 ≈ 3×. On the Gen 5 NPU that would be very roughly 2-4 ms per image.
- **MobileCLIP licensing.** Because of the research-only license, MobileCLIP/MobileCLIP2 fit a hackathon demo but not a shipped or commercial app. That licensing risk should be stated up front in the report.
- **Best drop-in option that keeps search.** SigLIP2 **B/32-256** keeps the same license and the same text-embedding approach. It has ~3× fewer tokens than B/16-224 (8×8 = 64 vs 14×14 = 196), for a −4.2 point ImageNet zero-shot cost. Text-label and future search-query embeddings must be recomputed with the B/32 text tower. A coarse doc/non-doc decision likely survives the ImageNet drop.
- **Permissive small CLIP options.** TinyCLIP-39M/16 (63.5%, MIT), OpenVision Ti/S (Apache-2.0) and PE-Core-S16 (72.7%, Apache-2.0) are the permissive "smaller CLIP" choices. None have published Snapdragon numbers or AI Hub exports, so each would need its own export and profiling.
- **Tiny classifier gate.** Model inference (~0.3 ms) is negligible next to JPEG decode and resize of a gallery photo, so decode will dominate in practice. Requesting small thumbnails from Android's media store, rather than decoding full images, likely matters more. This is my inference; I found no benchmark.

### Gaps
- I found no published Snapdragon or Android latency for MobileCLIP/MobileCLIP2, TinyCLIP, OpenVision, PE-Core-T/S or SigLIP2 image-only encoders. AI Hub SigLIP2 shows sizes but no latencies.
- I found no zero-shot numbers for any of these models on a doc-vs-photo task.
- EfficientViT / FastViT latencies on 8 Elite Gen 5 were not collected. FastViT is from Apple, so its weight license would need checking (not verified here).

## Two-tier gating: a 1-5 ms pre-filter before the CLIP model

### Takeaway
Cascades of cheap-then-expensive classifiers are well established: NoScope reports 2-3 orders of magnitude speed-up on binary video queries. A MobileNet-v3-Small-class gate costs ~0.3 ms on this SoC. I found **no published numbers** for a document-vs-photo pre-gate or for hand-crafted text-density/edge gates on phone galleries. The main design catch: if CLIP embeddings are needed for every photo for later free-text search, the pre-gate only postpones CLIP work. It does not remove it.

### Cited Findings
- Cascade principle: early stages use "very few and/or cheap features and reject many easily-classified inputs", while later stages are costlier — [Cost-Sensitive Tree of Classifiers / cascade literature](https://arxiv.org/pdf/1210.2771); [Edge Video Analytics survey](https://arxiv.org/pdf/2211.15751)
- NoScope (Stanford, VLDB 2017) trains cascades of specialized cheap models plus difference detectors in front of a reference CNN, with cost-based threshold selection. It reports **two to three orders of magnitude speed-up** for binary classification on fixed-angle video — [NoScope arXiv 1703.02529](https://arxiv.org/pdf/1703.02529); [Stanford DAWN post](https://dawn.cs.stanford.edu/news/noscope-1000x-faster-deep-learning-queries-over-video)
- Edge video analytics precedent: a small model handles every frame, and a heavy model runs only when the small model's confidence is low — [Edge Video Analytics survey](https://arxiv.org/pdf/2211.15751)
- Product precedent: Google Photos auto-classifies gallery images into Documents with sub-labels (Screenshots, Receipts, Identity Documents, Notes, etc.) and later added manual override. This implies a production doc classifier over the camera roll, but no architecture or latency is published — [9to5Google, Mar 2024](https://9to5google.com/2024/03/15/google-photos-screenshot-document-labels/); [Android Police](https://www.androidpolice.com/google-photos-categorization-rolling-out/)
- Per-image cost of a tiny gate on this SoC: MobileNet-v3-Small ≈ 0.26-0.32 ms (8 Elite Gen 5, NPU) — [qualcomm/MobileNet-v3-Small](https://huggingface.co/qualcomm/MobileNet-v3-Small). MNv4-Conv-S ≈ 1.8 ms on S23 CPU — [MobileNetV4 paper](https://arxiv.org/pdf/2404.10518)

### Inferences
- **Two ways to structure the pipeline:**
  - (a) Keep one CLIP pass on every photo as the gate and reuse the embedding for search. This is cheapest overall if search will eventually need embeddings for all photos.
  - (b) Put a ~0.3 ms MobileNet-class gate first so documents surface quickly, then run CLIP on all photos in a low-priority background pass for search.
  (b) cuts time to first searchable document by roughly the CLIP/gate cost ratio (~30-40× on NPU numbers above) but adds total work.
- **Training the gate.** Use the existing SigLIP2 zero-shot decisions as pseudo-labels over the user-agnostic dev set, i.e. distillation. Set a **high-recall threshold**, since false negatives (missed documents) cost more than false positives, which are caught later by CLIP or OCR.
- **Hand-crafted signals.** Features such as the Screenshots folder, aspect ratio matching the screen, EXIF missing camera make, low colour entropy, or high edge density can flag obvious documents and screenshots nearly for free. No published accuracy for them was found.

### Gaps
- I found no paper or blog with precision/recall or latency for a phone-gallery document-vs-photo pre-classifier.
- I found no numbers for text-density or edge-heuristic gates in this setting.

## Resolution: is 224 px enough, and would 160/128 px work?

### Takeaway
I found no direct evidence for the binary doc-vs-photo task at low resolution. Evidence exists only for **fine-grained 16-class** document-type classification (RVL-CDIP), where accuracy rises with input size up to 384 px. That task is much harder than "is this a document", so it's a loose upper bound on how much resolution matters here.

### Cited Findings
- Tensmeyer & Martinez (2017) tested square inputs {32, 64, 100, 150, 227, 256, 320, 384, 512} on RVL-CDIP and found "a distinct trend with larger inputs leading to increased performance". 384×384 reached 90.8%; multi-scale 320-512 reached 91.03%. At ~224 px "large text is generally legible, but smaller text is not". Exact per-size numbers are only in a figure — [arXiv 1708.03273](https://ar5iv.labs.arxiv.org/html/1708.03273)
- Another RVL-CDIP study reported validation accuracy of 88.61% at its lowest tested resolution vs 98.94% at its highest. The resolutions were not captured in the snippet; source attribution is uncertain — [DWT-CompCNN, arXiv 2306.01359](https://arxiv.org/pdf/2306.01359) (unverified snippet)
- A lower-resolution CLIP checkpoint trades accuracy modestly: SigLIP2 B/32-256 scores 74.0% vs 78.2% for B/16-224 on ImageNet zero-shot — [big_vision SigLIP2 README](https://github.com/google-research/big_vision/blob/main/big_vision/configs/proj/image_text/README_siglip2.md)

### Inferences
- **Why low resolution probably works.** Doc-vs-photo depends mostly on global layout: page rectangle, text-line texture, white background, UI chrome in screenshots. These cues survive at 128-160 px, so a trained gate at 128-160 px is plausible. Validate on the app's own labelled sample.
- **Fixed-resolution models.** For zero-shot CLIP, lowering resolution below the training size of a fixed-size ViT degrades embeddings. Use a checkpoint trained at that resolution (B/32-256, MobileCLIP-S0 @224) rather than feeding 128 px into a 224 model.
- **Dense-text vs sparse-text edge cases.** Receipts and whiteboards may need ≥224 px; the RVL-CDIP result that small text is illegible at 224 px suggests this.

### Gaps
- I found no published accuracy-vs-resolution curve for binary document detection on natural photo galleries.

## Stage B: ML Kit v2 latency, resolution, Latin vs Devanagari, script choice

### Takeaway
Google publishes size and input-size guidance but **no ms latency** for Text Recognition v2. I found no credible recent flagship timings either. Two direct savings are supported by the docs:
- The **Devanagari recognizer also recognizes Latin**, so running both recognizers on every document duplicates work.
- ML Kit gains nothing once characters exceed ~24 px tall, so a 2048 px cap is likely more than most documents need.

### Cited Findings
- App-size impact: **bundled ≈ 4 MB per script per architecture**; unbundled (Play Services) ≈ **260 KB**. Input guidance: "each character should be at least 16x16 pixels", with "generally no accuracy benefit for characters to be larger than 24x24 pixels". A business card works at 640×480; a letter-size document "might" need 720×1280. To cut latency, use lower resolution and make text fill the frame — [ML Kit TR v2 Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- The Devanagari recognizer covers **Devanagari and Latin**: DevanagariTextRecognizerOptions is "Configurations for a text recognizer for Devanagari and Latin-based languages." — [ML Kit Swift reference](https://developers.google.com/ml-kit/reference/swift/mlkittextrecognitiondevanagari/api/reference/Classes/DevanagariTextRecognizerOptions) (iOS reference; Android behaviour assumed the same, not independently verified)
- Devanagari languages supported: Hindi, Marathi, Nepali, and Sanskrit (experimental). **No Telugu or other non-Devanagari Indic script** — [ML Kit TR v2 languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- TR v2 "Identifies the language of the recognized text" (per-block recognizedLanguage) and claims "Real-time recognition… on a wide range of devices" — [ML Kit TR v2 overview](https://developers.google.com/ml-kit/vision/text-recognition/v2)
- Unverified: a search snippet claimed the overview page says Latin is real-time "though slower for other scripts". **Fetching the page did not confirm this wording.**
- Anecdotal / old: Fritz.ai (updated Dec 2023) compared ML Kit and Tesseract on a Galaxy J7. APK size was 9.8 MB (ML Kit) vs 23.1 MB (with Tesseract). On 761 images, ML Kit won 130 and Tesseract won 106. **No timings were given** — [Fritz.ai](https://fritz.ai/choose-the-right-on-device-text-recognition-sdk-on-android/)
- HalalBench (Apr 2026) only says ML Kit runs at "~30 fps" camera-frame rate in production. Its ms/image figures are for desktop CPU engines only (docTR 5.85 s, EasyOCR 10.1 s, RapidOCR 2.7 s on a 6-core Intel Mac) — [HalalBench arXiv 2604.22754](https://arxiv.org/html/2604.22754v1)

### Inferences
- **Recognizer choice.** Replace "Latin + Devanagari on every document" with **one** recognizer:
  - Default to Latin. It's the fastest and English-heavy documents are most common.
  - Use Devanagari (which also reads Latin) when a Devanagari cue exists. Cues: a Latin pass returning few characters or low confidence on a text-dense image, the user's locale or language setting, or a gate/CLIP label hint.
  This roughly halves ML Kit time on documents today.
- **Telugu detection without a detector.** Run Latin first. If the text-dense region yields very little Latin text, or ML Kit returns unknown-language blocks, route to the Telugu engine. Otherwise, check the Unicode range (U+0C00-U+0C7F) of Tesseract/Paddle output to confirm.
- **Downscaling rule.** Pick the long side so typical body text is ~20-24 px tall. For phone photos of A4 pages this often means ~1280-1600 px rather than 2048. Screenshots are already at native size, where UI text is ~30-50 px on a 1080p-wide screen, so they can often be downscaled further. Verify on real samples.

### Gaps
- I found no published ML Kit v2 ms/image on Snapdragon 8 Gen 2/3/Elite-class phones, whether Latin vs Devanagari, bundled vs unbundled, or by input resolution. The app should measure this itself.
- I found no official statement on whether bundled and unbundled differ in speed. They are documented as the same functionality with a different delivery mechanism.

## Stage B: Tesseract on Android (tel+eng) and faster Telugu-capable alternatives

### Takeaway
Tesseract's LSTM engine runs at **seconds per page**: ~1.8-4.6 s for a Hindi page on a desktop CPU, and historically slower and single-threaded on Android. Three alternatives support Telugu offline:
- **PaddleOCR PP-OCRv5 `te_PP-OCRv5_mobile_rec`** (Apache-2.0, 87.65% Telugu line accuracy, ~7.6 MiB ONNX). The strongest documented choice.
- **EasyOCR `telugu_g2`** (Apache-2.0). Heavier; the AI Hub export shows what the architecture costs on the NPU.
- **PP-OCRv6** (June 2026). New and much faster on CPU, but its Telugu support is unconfirmed.

### Cited Findings
**Tesseract**
- tessdoc Hindi benchmarks (Tesseract 4 LSTM, one Hindi page, HP Z420 desktop):
  - Real time: **1.8 s** with OpenMP + AVX; **2.7 s** no OpenMP + AVX; **3.1 s** SSE; **4.6 s** with no SIMD
  - Legacy base Tesseract: 2.9 s
  - Google data-centre test: LSTM is "faster than Tess 3.04… for wall time by a factor of 2" and more accurate (char error 7.6 vs 13.9)
  — [tessdoc 4.0 Accuracy & Performance](https://tesseract-ocr.github.io/tessdoc/tess4/4.0-Accuracy-and-Performance.html)
- tessdata_fast = "integer versions" with a smaller network, the best speed/accuracy "value for money". tessdata_best = float and slower, for people "willing to trade a lot of speed for slightly better accuracy" — [tessdoc Data Files](https://tesseract-ocr.github.io/tessdoc/Data-Files.html); [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast). A script-level `script/Telugu.traineddata` also exists — [tessdata](https://github.com/tesseract-ocr/tessdata/blob/main/script/Telugu.traineddata)
- Tesseract4Android discussion (anecdotal, 2019-era):
  - Reported timings: 1-2 min per image with only 16% CPU use (single thread); "less than 5 sec" after **reusing the TessBaseAPI instance** instead of re-initialising per image.
  - Suggestions: exclude bitmap decode from timing, clean the input, use the legacy engine for speed, use Tesseract 5 with NEON ("30% faster with tessdata_fast and 40% faster with standard tessdata"), and enable OpenMP (NDK caveats).
  — [Tesseract4Android discussion #20](https://github.com/adaptech-cz/Tesseract4Android/discussions/20)
- Quality guidance: best at ≥300 DPI. PSM 6 = uniform block; PSM 11 = sparse text. Disabling the dictionaries (`load_system_dawg`, `load_freq_dawg`) helps non-dictionary content — [tessdoc ImproveQuality](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html)

**PaddleOCR PP-OCRv5 (Apache-2.0)**
- PP-OCRv5 multilingual has **separate** recognizers `te_PP-OCRv5_mobile_rec` (Telugu), `devanagari_PP-OCRv5_mobile_rec` and `ta_PP-OCRv5_mobile_rec`. Kannada is not listed. Line accuracy:
  - Telugu **87.65%** (+43.47% over PP-OCRv3; test set 2,478 images)
  - Devanagari **84.96%** (+68.26%)
  - Tamil 94.2%
  — [PP-OCRv5 multilingual doc](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md)
- `te_pp-ocrv5_mobile_rec.onnx` is **7.6 MiB** — [oar-ocr models list](https://github.com/GreatV/oar-ocr/blob/main/docs/models.md) (third-party). An older `te_PP-OCRv3_mobile_rec` is also on HF — [PaddlePaddle/te_PP-OCRv3_mobile_rec](https://huggingface.co/PaddlePaddle/te_PP-OCRv3_mobile_rec)
- PP-OCRv5_mobile_rec (base, Chinese/English/Japanese) is Apache-2.0, with 0.8015 average line accuracy across 13 scenarios — [PaddlePaddle/PP-OCRv5_mobile_rec](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec)
- Android precedent: PPOCRv5-Android (Apache-2.0) runs PP-OCRv5 det+rec in FP16 on LiteRT with an OpenCL GPU delegate and XNNPACK CPU fallback. It claims a "2-4x speedup over CPU" but **publishes no ms numbers** and is arm64-only — [iFleey/PPOCRv5-Android](https://github.com/iFleey/PPOCRv5-Android)
- Server-level pipeline numbers only: PP-OCRv5_mobile end-to-end ≈ 0.25 s on A100 and 5.82 s on Apple M4 (per search summary of PaddleOCR 3.0 report) — [PaddleOCR 3.0 Technical Report](https://arxiv.org/html/2507.05595v1)
- **PP-OCRv6** (arXiv, 11 Jun 2026): medium/small/tiny tiers, **1.5M-34.5M params**. The tiny tier is "3.9× faster than PP-OCRv5_mobile on Intel Xeon CPU while maintaining comparable accuracy". The abstract doesn't state language coverage. The arXiv CC-BY-4.0 covers the paper; the model license was not checked — [arXiv 2606.13108](https://arxiv.org/pdf/2606.13108)

**EasyOCR (Apache-2.0)**
- EasyOCR ships **telugu_g2** and kannada_g2 (2nd-gen, v1.2) plus devanagari_g1 and tamil_g1 recognition models — [EasyOCR Model Hub](https://www.jaided.ai/easyocr/modelhub/); [JaidedAI/EasyOCR](https://github.com/JaidedAI/EasyOCR)
- AI Hub EasyOCR (608×800 input, Apache-2.0) on 8 Elite Gen 5 — [qualcomm/EasyOCR](https://huggingface.co/qualcomm/EasyOCR):
  - **Detector:** 4.5 ms TFLite w8a8; 5.4 ms ONNX w8a8; 15.8-16.8 ms float. 79.2 MB float.
  - **Recognizer:** 7.9 ms ONNX w8a8; 11.2 ms float. 14.7 MB float.
  - On 8 Elite: detector 5.7-22.3 ms, recognizer 7.6-11.7 ms.
  - Which language's recognizer is exported is not stated; it's likely the English/Latin default.
- Desktop anecdote: EasyOCR averaged 10.1 s/image and 2 GB RAM on a 6-core Intel Mac CPU — [HalalBench](https://arxiv.org/html/2604.22754v1)

**Qualcomm AI Hub TrOCR**
- On 8 Elite Gen 5 (ONNX): encoder 4.1 ms, decoder 1.3 ms per step. Encoder 23.0M params / 87.8 MB; decoder 38.3M / 146 MB. MIT. Languages are not stated on the card — [qualcomm/TrOCR](https://huggingface.co/qualcomm/TrOCR). No PaddleOCR / PP-OCR entry exists in the AI Hub catalog — [qualcomm/ai-hub-models](https://github.com/qualcomm/ai-hub-models)

### Inferences
- **Paddle Telugu recognizer.** PP-OCRv5 det + `te_PP-OCRv5_mobile_rec`, via ONNX Runtime (QNN EP) or LiteRT, is the most credible Tesseract replacement for Telugu: small (~7.6 MiB rec), permissive and benchmarked for Telugu. The Paddle Devanagari recognizer could also replace ML Kit Devanagari, unifying the non-Latin stack. Page latency is unmeasured. Use the EasyOCR AI Hub numbers as an analogue for CRAFT/CRNN-style costs on the NPU: ~5-16 ms detection plus ~8-11 ms per recognizer batch. That suggests tens to low hundreds of ms per page, versus seconds for Tesseract. This needs validation.
- **Recognizers run per line.** Page latency is detection plus (number of lines × recognizer time) unless lines are batched. Text-dense pages cost more.
- **If Tesseract stays**, the known levers are:
  - one persistent TessBaseAPI
  - tessdata_fast `tel` + `eng`
  - `tel` alone when the script check says Telugu. Each extra language adds a model pass, a general Tesseract property not measured here.
  - PSM 6 for single-column screenshots/documents
  - downscale so x-height is modest, rather than feeding 2048 px
  - running Tesseract only after ML Kit's Latin pass shows non-Latin content
  - running several documents in parallel threads, since Tesseract on Android is effectively single-threaded
- **TrOCR** isn't a fit. It's autoregressive (one decoder step per token), and the AI Hub export is English-oriented.

### Gaps
- I found no published on-device (Android, Snapdragon) ms numbers for PP-OCRv5 det+rec, `te_PP-OCRv5_mobile_rec`, or EasyOCR telugu_g2.
- I found no recent Tesseract 5 Android per-page timings for `tel`, `tel+eng`, fast or best.
- PP-OCRv6 language coverage (Telugu?), model license and mobile latency are unverified.
- Google has no other offline Telugu OCR option for Android that I found. ML Kit v2 lacks Telugu, and GenAI/Gemini Nano OCR was not researched here.

## Deferring work: what can move to a second pass

### Takeaway
Documents can become searchable sooner by splitting the pipeline into two passes:
- **Fast first pass:** gate, cheap Latin OCR at reduced resolution, and keyword indexing.
- **Deferred pass:** expensive OCR (Telugu/Devanagari), full-resolution re-OCR, text embeddings for semantic search, and CLIP embeddings of non-documents for photo search.
The recommendations here are mostly inference, grounded in the cost figures above. I found no published pipeline study.

### Cited Findings
- Cost asymmetry that justifies deferral:
  - Tesseract LSTM costs seconds per page (1.8-4.6 s desktop Hindi) — [tessdoc](https://tesseract-ocr.github.io/tessdoc/tess4/4.0-Accuracy-and-Performance.html)
  - NPU-class detector/recognizers cost milliseconds (EasyOCR on 8 Elite Gen 5) — [qualcomm/EasyOCR](https://huggingface.co/qualcomm/EasyOCR)
  - A tiny gate costs ~0.3 ms — [qualcomm/MobileNet-v3-Small](https://huggingface.co/qualcomm/MobileNet-v3-Small)
  - A ViT-B/16 CLIP costs ~11-13 ms on the NPU — [qualcomm/OpenAI-Clip](https://huggingface.co/qualcomm/OpenAI-Clip)
- ML Kit reaches usable accuracy at modest resolution (16-24 px characters; ~720×1280 for a letter page), so a lower-resolution first pass is supported by vendor guidance — [ML Kit Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

### Inferences
- **Pass 1 (interactive, per photo):**
  - Decode a thumbnail.
  - Run the gate (tiny classifier, or CLIP if it's already needed).
  - For documents, run ML Kit Latin at ~1280 px long side.
  - Write the raw text to a keyword/FTS index immediately, so documents become findable by exact words.
- **Pass 2 (idle or charging, e.g. WorkManager with charging/idle constraints):**
  - (a) Script check, then Telugu/Devanagari OCR, only for documents where pass 1 found little Latin text.
  - (b) Full-resolution re-OCR only if pass-1 text was sparse or had low confidence.
  - (c) Text embeddings of OCR text for semantic search.
  - (d) CLIP image embeddings for non-document photos, for "beach trip"-style search, if a tiny gate replaced CLIP in pass 1.
  - (e) Document sub-typing (receipt, ID, etc.) through extra zero-shot labels on the stored embedding.
- **Ordering.** Prioritise newer photos and screenshots first, since users are most likely to search recent items. Recompute nothing that's already stored: keep the image embedding once computed.

### Gaps
- I found no published measurements of end-to-end time-to-searchable for on-device gallery indexers (Google Photos, Apple Photos or open-source apps) to benchmark against.
