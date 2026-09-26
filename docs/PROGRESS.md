# Progress: what has been done so far

**As of 2026-09-26.** Everything is on this Mac only: no GitHub repo, no commits, no pushes. The whole app architecture is built and compiles, 51 unit tests pass, and both APKs build without the INTERNET permission. Nothing has run on the phone yet, because it hasn't been connected. What's left is in [NEXT_STEPS.md](NEXT_STEPS.md).

| Brief phase | Code | Gate on the phone |
|---|---|---|
| 0 Setup | Done | Pending (phone not connected) |
| 1 Models | Done | Pending (model files not on the phone yet) |
| 2 Ingest | Done | Pending |
| 3 Search and answer | Done | Pending |
| 4 Packs and masking | Done | Pending |
| 5 Reminders and emergency | Done | Pending |
| 6 Hardening (brief §8) | Partial (see NEXT_STEPS §3) | Pending |
| 7 Docs | Drafted | Needs the device numbers |

---

## 1. What was set up on this Mac

| Item | Version | Notes |
|---|---|---|
| JDK | OpenJDK 21.0.12.1 | Homebrew `openjdk@21`, no sudo |
| Android SDK | cmdline-tools 23.0, platform-tools 37.0.1 (adb), build-tools 37.0.0, platform android-37.0 | In `~/Library/Android/sdk`. The cmdline-tools checksum matched Google's index; AGP installed the platform itself |
| Gradle | 9.8.0 wrapper | Distribution SHA-256 verified |
| Python | CPython 3.12.13 venv in `.venv` | qai-hub 0.55.0, torch 2.14.0, transformers 5.17.0, Pillow 12.3.0, ai-edge-litert, qrcode |
| Homebrew `fribidi` | 1.0.17 | Lets Pillow shape Hindi and Telugu text correctly |

Side effects to know about: adb created its key in `~/.android/`, and Google's `sdkmanager` installed its own `android-cli` there. **Likely:** the first `sdkmanager` runs sent usage metrics to Google; later calls use `--no-metrics`.

## 2. Decisions you made

- **Emergency mode:** bypasses the fingerprint lock for the health pack only (D-001).
- **AI Hub token:** you configure it yourself; it never enters the chat or the repo (D-003).
- **Phone connection:** USB.
- **Local only:** no GitHub repo, no pushes, no commits.
- **Order of work:** build the whole architecture first, edge cases later.

## 3. What was researched and verified

Each fact was checked against a primary source (Maven metadata, the real jars via `javap`, vendor docs, model cards):

- **Library versions:** the latest stable version of every library came from Maven metadata, never from memory. The full list is in DECISIONS.md D-006.
- **AGP and Kotlin:** AGP 9 compiles Kotlin itself. We raised Kotlin to 2.4.20 through the buildscript classpath, which is the documented route.
- **LiteRT:** the latest LiteRT, 2.2.0, can't use Qualcomm's NPU delegate (the delegate API is gone), so we use LiteRT 1.4.2 (D-007).
- **One QNN runtime:** GenieX 0.3.1 bundles Qualcomm's AI runtime 2.45, the same version the Qwen bundle was built with. The NPU delegate is pinned to match, so the APK carries one QNN runtime and is about 70 MB smaller (D-008).
- **GenieX:** GenieX is real (`com.qualcomm.qti:geniex-android:0.3.1`). It can import model files pushed with adb (`HubSource.LOCALFS`), so Qwen runs with no INTERNET permission. Its classes were read from the actual jar (D-009).
- **Model facts:**
  - SigLIP2 is `google/siglip2-base-patch16-224` at 224×224, and its TFLite export is a public download.
  - Nomic is v1.5 with a 1×128 input.
  - The Qwen3-4B bundle is w4a16, 4 shards, with a 4096-token context.
- **Tesseract data sizes:** Telugu "best" is 9.1 MB; English and Hindi "fast" are 4.1 MB and 1.1 MB.
- **Licences:** checked from the Maven POMs and model cards (see the README attribution table).

## 4. What was built

### App code: 77 Kotlin files, about 7,000 lines

| Package | What it does | Key files |
|---|---|---|
| `model/` | Shared types: 26 document types, languages, sources | `Types.kt` |
| `extract/` | Reads the text: Aadhaar with the Verhoeff check, PAN, IFSC, vehicle numbers, policy numbers, ₹ amounts, phones, ABHA. Parses dates in English, Hindi and Telugu, picks expiry and issue dates, classifies the document, extracts the owner's name, writes the title | `EntityExtractor`, `DateParser`, `ExpiryPicker`, `DocClassifier` |
| `ml/` | Loads models once and tries NPU, then GPU, then CPU. SigLIP2 vision and the document gatekeeper, Nomic embedder, WordPiece tokenizer, chunker, Qwen through GenieX, latency metrics | `LiteRtModel`, `ModelManager`, `QwenLlm` |
| `ocr/` | ML Kit Latin and Devanagari, with Tesseract for Telugu; word boxes are kept for masking | `OcrEngine` |
| `data/` | Encrypted database (SQLCipher), keyword search that handles Hindi/Telugu vowel signs, in-memory vector search, encrypted file vault, key handling | `RecallDb`, `FtsIndex`, `KeyManager` |
| `ingest/` | One pipeline for gallery, camera and PDF; background gallery scan that resumes after a kill; PDFs rendered from memory | `IngestPipeline`, `IndexWorker` |
| `search/` | Understands queries with Qwen or rules; combines keyword, meaning and type search; answers that cite their sources; voice input; EVAL runner | `QueryEngine`, `RuleFallbackParser`, `HybridSearch` |
| `actions/` | Checklists with recency rules, Aadhaar and QR masking that blocks sharing if unsure, masked PDF packs, expiry reminders, Emergency mode with read-aloud | `ChecklistEngine`, `AadhaarMasker`, `PackBuilder` |
| `ui/` | 11 screens in English, Hindi and Telugu, plus the Emergency Quick Settings tile | `HomeScreen`, `ChecklistScreen`, `BenchmarkScreen` |

### Data files shipped in the app (`app/src/main/assets/`)
- **`templates.json`:** 9 checklists: home loan, health claim, vehicle renewal, emergency, passport, and 4 renewal packs.
- **`rule_lexicon.json`:** English, Hindi, Telugu and romanized words for the rules-mode parser.
- **`siglip_labels.json`:** the 15 gatekeeper labels as vectors (768 dimensions).
- **`nomic_vocab.txt`:** the tokenizer vocabulary (30,522 tokens).
- **`model_config.json`:** the calibrated gatekeeper threshold.
- **`eval_queries.json`:** the 20 test queries.
- **`tessdata/`:** Tesseract data for Telugu, English and Hindi.

### Tools and scripts
- **Python tools (`tools/`):**
  - `siglip_labels.py`: label vectors, plus a check that the phone model matches the reference.
  - `tokenizer_parity.py`: vocab export and tokenizer test data.
  - `make_seed_docs.py`: fake test documents.
- **Scripts (`scripts/`):** `env.sh`, `fetch_tessdata.sh` (hash-verified download), `push_models.sh`, `push_seed.sh`, `check_no_internet.sh`.
- **Test documents:** 13 fake documents and 3 non-document photos (English, Hindi, Telugu), plus a 2-page PDF. They include fake Aadhaar numbers that pass Verhoeff, a QR code with fake content, a policy expiring in 7 days, and an expired car policy.

### Docs (`docs/`)
- `ARCHITECTURE.md`: component, ingest, query and masking diagrams.
- `DECISIONS.md`: 15 recorded decisions and deviations.
- `EVAL.md`: the 20 queries and the results so far.
- The `README.md` in the repo root covers setup, models, attribution and limitations.

## 5. Checks run and their results

| Check | Result |
|---|---|
| Unit tests | **51 passed, 0 failed**: checklist and masking 10, dates and expiry 12, classifier 7, Verhoeff and entities 10, tokenizer 4, search logic 7, EVAL proxy 1 |
| Tokenizer vs Hugging Face | **22/22 identical**, including Hindi, Telugu, emoji and long words |
| SigLIP2 labels, reference model on the Mac | Right top label on **7/7** images |
| Gatekeeper, reference model on the 16 seed images | **16/16** sorted correctly (documents ≥ +0.031, photos ≤ −0.028), so the threshold is 0.0 |
| EVAL queries, rules parser, offline | **20/20** map to the right type or checklist (en 7/7, te 7/7, hi 6/6). This checks parsing only; real hit@3 needs the phone |
| Debug APK | Builds, 157.6 MB, no INTERNET |
| Release APK | Builds, 153.3 MB, passes release lint, no INTERNET |
| Hindi and Telugu rendering in the seed images | Checked by eye: correct |

## 6. Problems found and fixed along the way

- **LiteRT:** the latest LiteRT can't take the NPU delegate, so it was pinned to 1.4.2.
- **QNN libraries:** GenieX and the Qualcomm runtime package would have duplicated them, so a single QNN runtime (2.45) is used.
- **Kotlin compile errors:** two type-inference errors and one suspend call inside a plain lambda, all fixed.
- **Invisible characters:** writing the files turned escaped zero-width characters into invisible real ones; they were put back as escapes.
- **Classifier weighting:** a unit test showed SigLIP2's type hint was split too evenly; the weighting was changed.
- **Night-mode crash:** release lint found a colour defined only for night mode, which could crash in day mode; it was fixed.
- **transformers 5:** version 5 changed a return type the label script relied on; the script handles both forms.
- **Indic text rendering:** Pillow couldn't shape Hindi or Telugu until `fribidi` was installed.

## 7. Not done yet

In short: all phone gates, model files on the phone, some edge cases (blur checks, storage-full, thermal, deleted-source marking, memory handling), instrumented tests, and a few quick fixes. The Models screen, for example, has no entry point yet. Everything is listed with owners in [NEXT_STEPS.md](NEXT_STEPS.md).
