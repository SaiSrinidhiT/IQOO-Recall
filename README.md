# iQOO Recall

iQOO Recall is a private, offline paperwork assistant for Android. It turns the photos, screenshots, scans and PDFs on your phone into an encrypted, searchable memory of your documents. You ask in English, Telugu or Hindi, including romanized mixes like "Home loan ki documents ready cheyyi". It finds the documents, builds task packs that flag what's missing, masks Aadhaar numbers before anything is shared, and reminds you before documents expire. It was built at the iQOO Hackathon 2026 (Hyderabad) for the iQOO 15 (Snapdragon 8 Elite Gen 5).

**Status:** the architecture is complete. Every feature (F1 to F11) is wired end to end, the code compiles, and 51 JVM unit tests pass. Nothing has been verified on the phone yet: models, NPU backends and gates are all pending a device connection. The edge-case hardening in brief §8 is scheduled next. See [docs/DECISIONS.md](docs/DECISIONS.md) for every decision and deviation from the brief, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for diagrams, and [docs/EVAL.md](docs/EVAL.md) for evaluation.

## What runs where

| Job | Runs on the phone with | Fallback when missing |
|---|---|---|
| Document gatekeeper, doc-type hint, near-duplicate vectors | SigLIP2 vision tower, LiteRT 1.4.2 + Qualcomm QNN delegate (NPU), then GPU, then CPU | OCR text-density gate |
| Semantic search | Nomic Embed Text v1.5, same runtime | Keyword (FTS) + doc-type search |
| Intent parsing, grounded answers, doc-type and expiry decisions | Qwen3-4B-Instruct-2507 via GenieX (`qairt`, NPU), then Qwen3-1.7B | Multilingual rule parser, template answers |
| OCR | ML Kit Latin + Devanagari (bundled), Tesseract `tel+eng` | — |
| Storage | Room over SQLCipher; Tink-encrypted vault files; Android Keystore | — |

The release build requests **no INTERNET permission**, and `scripts/check_no_internet.sh` verifies that on the APK.

## Build (macOS, Apple Silicon)

1. Install the toolchain once. No sudo is needed.
   ```sh
   brew install openjdk@21 fribidi
   # Android command-line tools unpacked to ~/Library/Android/sdk/cmdline-tools/latest, then:
   ~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --no-metrics --install platform-tools "build-tools;37.0.0"
   ```
   AGP installs `platforms;android-37.0` by itself on the first build.
2. Set up the Python tools:
   ```sh
   uv venv .venv --python 3.12
   uv pip install --python .venv/bin/python qai-hub torch transformers pillow einops sentencepiece protobuf numpy ai-edge-litert safetensors qrcode
   ```
3. Generate the assets. The vocab and SigLIP labels are already in `app/src/main/assets/`; rerun these if you change a checkpoint.
   ```sh
   scripts/fetch_tessdata.sh                     # Tesseract tel (best), eng + hin (fast), hash-verified
   .venv/bin/python tools/tokenizer_parity.py    # Nomic vocab + tokenizer parity fixtures
   .venv/bin/python tools/siglip_labels.py       # SigLIP2 label vectors
   ```
4. Build and install:
   ```sh
   source scripts/env.sh
   ./gradlew testDebugUnitTest        # JVM unit tests
   ./gradlew installDebug             # needs the phone connected with USB debugging on
   ./gradlew assembleRelease && scripts/check_no_internet.sh
   ```

## Models (not in the APK)

Put the files in `./models`, then run `scripts/push_models.sh`. It pushes them to `/sdcard/Android/data/com.hackathon.recall/files/models/` and writes a size manifest. The app's Models screen shows what is missing or damaged.

| File or folder in `./models` | Source |
|---|---|
| `siglip2_vision.tflite` | AI Hub SigLIP2 TFLite export, public: `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/siglip2/releases/v0.63.0/siglip2-tflite-float.zip`. Use the image-encoder `.tflite` inside. |
| `nomic_embed_text.tflite` | AI Hub `nomic_embed_text` TFLite export for Snapdragon 8 Elite Gen 5 |
| `qwen3_4b_instruct_2507/` | AI Hub Genie bundle `Qwen3-4B-Instruct-2507` for SM8850: `metadata.json` + `part*_of_4.bin`. This may need an AI Hub account or token. |
| `qwen3_1_7b/` (optional) | Fallback bundle in the same format |

To check that the exported SigLIP2 matches the reference checkpoint and to pick its input convention, run `.venv/bin/python tools/siglip_labels.py --images tools/out/seed/*.jpg --tflite models/siglip2_vision.tflite`.

## Seed data (synthetic only)

```sh
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib .venv/bin/python tools/make_seed_docs.py
scripts/push_seed.sh
```
This makes 13 fake documents (English, Hindi and Telugu) and 3 non-documents, plus a 2-page PDF in `tools/out/seed_pdf/` for the Add PDF picker. Every name and number is fake, and the QR code encodes "FAKE SAMPLE DATA". Never use real personal documents.

## Project layout

```
app/src/main/java/com/hackathon/recall/
  model/    shared enums (DocType, Lang, ...)
  extract/  Verhoeff, entities, dates, expiry, issue date, classifier, owner, title
  ml/       ModelManager, LiteRtModel (NPU→GPU→CPU), SiglipVision, Gatekeeper, NomicEmbedder, WordPieceTokenizer, TextChunker, GenieXQwen, Metrics
  ocr/      OcrEngine (ML Kit + Tesseract), ScriptDetector
  data/     Room entities/DAOs over SQLCipher, FtsIndex, VectorIndex, VaultFileStore, KeyManager, DocumentRepository
  ingest/   IngestPipeline, MediaStoreScanner, IndexWorker + GalleryObserver, ImageLoader (images, PDF via memfd)
  search/   IntentParser, RuleFallbackParser, Lexicon, HybridSearch (RRF), AnswerGenerator, QueryEngine, LlmEnricher, VoiceInput, EvalRunner
  actions/  Templates, ChecklistEngine, MaskPlanner, AadhaarMasker, PackBuilder, ReminderScheduler/Receiver, EmergencyMode, Speaker
  ui/       Compose screens: Lock, Home, Results, Checklist, DocDetail, Vault, CameraScan, Emergency (+ QS tile), Benchmark, Settings, Models
app/src/main/assets/  templates.json, rule_lexicon.json, model_config.json, siglip_labels.json, nomic_vocab.txt, eval_queries.json, tessdata/
tools/    siglip_labels.py, tokenizer_parity.py, make_seed_docs.py
scripts/  env.sh, fetch_tessdata.sh, push_models.sh, push_seed.sh, check_no_internet.sh
docs/     ARCHITECTURE.md, DECISIONS.md, EVAL.md
```

## Attribution

Everything was written during the event. Third-party components are used as libraries under their licences:

| Component | Use | Licence |
|---|---|---|
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Language and runtime libraries | Apache-2.0 |
| Android Jetpack: Compose, Room, WorkManager, CameraX, Navigation, Lifecycle, Core | App framework | Apache-2.0 |
| LiteRT 1.4.2 and LiteRT GPU delegate (Google) | TFLite inference | Apache-2.0 |
| Qualcomm QNN LiteRT delegate 2.45.0 | Hexagon NPU backend | Qualcomm AI Hub Model License |
| Qualcomm GenieX Android SDK 0.3.1 (bundles QAIRT 2.45) | Qwen on the NPU | Apache-2.0 (per its Maven POM); the bundled QAIRT libraries carry Qualcomm's terms |
| SQLCipher for Android 4.19.0 (Zetetic) | Encrypted database | SQLCipher community licence (BSD-style), zetetic.net/sqlcipher/license |
| Google Tink 1.23.0 | File encryption | Apache-2.0 |
| Google ML Kit Text Recognition v2 (Latin, Devanagari), Barcode Scanning | OCR, QR detection | ML Kit Terms of Service |
| Tesseract4Android 4.9.0 (Adaptech), Tesseract OCR, tessdata_best / tessdata_fast | Telugu OCR | Apache-2.0 |
| SigLIP2 `google/siglip2-base-patch16-224` via Qualcomm AI Hub | Vision gatekeeper | Apache-2.0 (model); AI Hub export code BSD-3-Clause |
| Nomic Embed Text v1.5 `nomic-ai/nomic-embed-text-v1.5` via Qualcomm AI Hub | Text embeddings | Apache-2.0 (model) |
| Qwen3-4B-Instruct-2507 (Alibaba Qwen) via Qualcomm AI Hub | Language model | Apache-2.0 (model) |
| Host tools: PyTorch, Hugging Face transformers, Pillow, qrcode, ai-edge-litert, qai-hub | Asset generation and checks (not shipped) | BSD-3-Clause / Apache-2.0 / MIT-CMU / BSD / Apache-2.0 / Qualcomm |

Fonts (Arial, Kohinoor) are used only on the build Mac to render synthetic test images. They are not shipped.

## Known limitations (current)

- Nothing has been verified on the phone yet: the NPU backend, model parity, latency, the phase gates and the airplane-mode run are all pending.
- The Qwen bundle has to be obtained from Qualcomm AI Hub, which may need a token. The app runs in rules mode without it.
- Edge cases in brief §8 are still to do. They include:
  - thermal and low-battery pause, a foreground service for very long first indexes, storage-full handling;
  - blur and darkness warnings, marking deleted sources;
  - log scrubbing via a release logger, R8 minification.
- Hindi and Telugu UI strings haven't been reviewed by native speakers.
- The release build is signed with the debug key, for sideloading only.
