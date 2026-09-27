# Decisions log: iQOO Recall

This file records every decision the build brief left open, and every place where what we observed differs from the brief. Where the device or a primary source disagrees with the brief, the observation wins and is recorded here.

Status values: **decided**, **planned** (decided, not built yet), **open** (still being verified).

---

## Environment (verified 2026-09-26)

| Item | Value | Evidence |
|---|---|---|
| Host | Apple Silicon (arm64), macOS 26.7, 8 GB RAM, ~75 GB free | `sw_vers`, `uname -m`, `sysctl hw.memsize`, `df -h` |
| JDK | OpenJDK 21.0.12.1 (Homebrew `openjdk@21`, keg-only, no sudo) | `java -version` |
| Android SDK root | `~/Library/Android/sdk` | — |
| cmdline-tools | 23.0 (build 16111833, arm64). SHA-1 `ad03dc49bfacfd52c110b14104ea548b8a07e830` matches `repository2-3.xml` | checksum compared in script |
| platform-tools | 37.0.1 (adb 1.0.41) | `adb version` |
| build-tools | 37.0.0 | `sdkmanager` |
| Python tooling | uv venv `.venv`, CPython 3.12.13: `qai-hub` 0.55.0, torch 2.14.0, transformers 5.17.0, Pillow 12.3.0 (+ Homebrew `fribidi` so Pillow's raqm can shape Hindi and Telugu), `ai-edge-litert` | `importlib.metadata`, `PIL.features` |
| Gradle | 9.8.0 wrapper; SHA-256 of the distribution matches services.gradle.org | `gradle-wrapper.properties` |
| Phone | _pending: device not yet connected_ | `adb devices -l` |

The SDK licences were accepted with `sdkmanager --licenses` so the command-line build can run (the equivalent of the Android Studio licence click). The new SDK CLI announces metrics collection, so every later `sdkmanager` call passes `--no-metrics`.

To use the toolchain, run `source scripts/env.sh` (it sets `JAVA_HOME`, `ANDROID_HOME` and `PATH`).

---

## D-001 Emergency mode bypasses the lock for the health pack only (decided by the human, 2026-09-26)

**Question from brief §7:** should Emergency mode bypass the biometric lock? The brief's default was no bypass. **The human chose: bypass, health pack only.**

Constraints the implementation must keep:
- Emergency mode shows **only** the `emergency_health` pack: health insurance, health ID (ABHA), Aadhaar (**always masked**, last 4 digits only), and recent medical reports.
- It is read-only. Sharing, exporting, editing, and navigating to any other screen all require `BiometricPrompt` first.
- It is reachable from the home-screen button and from the Quick Settings tile. The tile can open it over the lock screen (`showWhenLocked`), like a Medical ID. Anyone holding the phone can read this pack; the human accepted that trade-off.
- `FLAG_SECURE` still applies to the emergency screen.

## D-002 Vault key model (decided; implemented in `data/KeyManager.kt`)

- The SQLCipher key is 256 random bits. It is wrapped by an AES-GCM key in the Android Keystore (StrongBox when available, otherwise TEE).
- The wrapping key is **not** bound to user authentication. Background indexing (F1) and reminders (F7) must open the database when the user isn't there. An auth-bound key would also be invalidated when a new fingerprint is enrolled, and the vault would be lost with it.
- `BiometricPrompt` (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) gates the UI.
- **What this protects against:** copying the data off the device (the files can't be decrypted without this phone's Keystore), and casual access to an unlocked phone. **What it doesn't:** code running as this app on a rooted or compromised phone.
- **Consequence for D-001:** Emergency mode doesn't need a separate cache. It skips the UI gate and reads only the health pack.
- **Rejected for the MVP:** an auth-bound key plus an "inbox" that background ingest encrypts to a vault public key, merged on unlock. It is stronger, but it adds a second storage path and complicates dedup and reminders. Revisit in Phase 6 if there is time.

## D-003 Handling the AI Hub token (decided, 2026-09-26)

The human runs `.venv/bin/qai-hub configure --api_token <TOKEN>` themselves. The token lives in `~/.qai_hub/client.ini`, outside the repo, and `*.ini` is gitignored. The agent checks the token only indirectly, with `qai-hub list-devices`, and never reads or prints that file.

## D-004 Project identity and layout (decided, 2026-09-26)

- `applicationId` and namespace: `com.hackathon.recall`. This is neutral and avoids implying a vendor-owned domain. The launcher label is "iQOO Recall".
- Model files on the device go in `/sdcard/Android/data/com.hackathon.recall/files/models/` (brief §3.3).
- Assets live in `app/src/main/assets/` (the Android convention), not in a top-level `assets/` folder as brief §11 suggests.
- **Local only (the human's instruction, 2026-09-26):** no GitHub repo, no pushes, no commits. This overrides the brief's "commit after each gate". Gate results are recorded in this file instead. A local `git init` was run earlier; it has no commits and no remote.
- The Python tools use CPython 3.12 in `.venv`, chosen because ML wheels (torch, transformers, qai-hub-models) are widely available for it. **Assumed:** 3.12 is the safe choice. Homebrew's Python 3.14 was not tested with these packages.
- Backups are off: `allowBackup=false`, plus `data_extraction_rules.xml` excludes every domain from cloud backup and device-to-device transfer. The vault never leaves the phone.

## D-005 Offline enforcement (decided, 2026-09-26)

- `AndroidManifest.xml` requests no `INTERNET` permission.
- `scripts/check_no_internet.sh` fails if the merged **release** APK requests `android.permission.INTERNET`. It uses `aapt2 dump permissions`, so it also catches permissions that libraries merge in.
- `AndroidManifest.xml` also declares `tools:node="remove"` for `INTERNET`, so a library can never merge it back in.
- The Benchmark screen checks at runtime and shows a warning if the installed build requests `INTERNET`.
- **Verified on the debug APK (2026-09-26):** `scripts/check_no_internet.sh` reports no `INTERNET`. WorkManager merges `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `FOREGROUND_SERVICE`; none of these allows network access.

## D-006 Build toolchain versions (decided, checked against Maven metadata on 2026-09-26)

- **AGP and Kotlin:** AGP 9.4.1 with built-in Kotlin, so there is no `kotlin-android` plugin and no kapt (Room uses KSP). AGP 9 has a runtime dependency on KGP 2.2.10, and the AGP 9.0 release notes say to put a higher KGP on the buildscript classpath to use it. We use Kotlin 2.4.20 and KSP 2.3.12 that way (`build.gradle.kts`).
- **SDK levels:** `compileSdk = 37` and `targetSdk = 37` (Android 17's setup docs use exactly this syntax). AGP auto-installed `platforms;android-37.0` for it. `minSdk` stays at 30.
- **Libraries:**
  - Compose BOM 2026.09.00 (material3 1.4.0, ui 1.12.1), activity-compose 1.13.0, lifecycle 2.11.0, navigation-compose 2.10.2, core-ktx 1.19.1.
  - Room 2.8.5, androidx.sqlite 2.7.1, SQLCipher `net.zetetic:sqlcipher-android` 4.19.0, Tink 1.23.0.
  - WorkManager 2.12.0, CameraX 1.6.2, coroutines 1.11.0, serialization 1.11.0.
  - ML Kit text-recognition 16.0.1 and text-recognition-devanagari 16.0.1 (both bundled), barcode-scanning 17.3.0 (bundled).
  - Tesseract4Android 4.9.0, from JitPack only (the repository is restricted to that group).
- **ABI:** `abiFilters = arm64-v8a`. GenieX and the QNN delegate ship only arm64.
- **Build host:** the machine has 8 GB RAM, so `gradle.properties` gives Gradle 2.5 GB and compiles Kotlin in-process.

## D-007 LiteRT 1.4.2, not 2.2.0 (deviation from "latest", verified 2026-09-26)

The latest LiteRT (2.2.0) removed delegate support from the classic `Interpreter`. `javap` shows no `Interpreter.Options.addDelegate` and no `org.tensorflow.lite.Delegate` class. Qualcomm's QNN delegate (`com.qualcomm.qti.QnnDelegate`) implements `org.tensorflow.lite.Delegate`, so it cannot attach to LiteRT 2.x.

- **Decision:** use LiteRT 1.4.2 (the last 1.x release) with `litert-gpu` 1.4.2 and the QNN delegate.
- **Backend order:** Hexagon NPU (QNN HTP backend, FP16 precision, burst performance, a graph cache in `cacheDir/qnn`), then the GPU delegate, then CPU (XNNPACK, 4 threads).
- **What counts as a backend:** one only counts after a warm-up inference succeeds on it. The Benchmark screen shows that backend and why the faster ones failed.
- **Why not LiteRT 2.x:** its NPU path (`CompiledModel` with `Accelerator.NPU`) needs a vendor dispatch library and AOT-compiled models, and the AI Hub TFLite exports don't provide either. Revisit if AI Hub publishes LiteRT-NPU builds.

## D-008 One QNN runtime: QAIRT 2.45, shared by GenieX and the QNN delegate (verified 2026-09-26)

- **The match:** GenieX 0.3.1 bundles QAIRT `v2.45.0.260326154327` (read from its `libQnnHtp.so`). The AI Hub Qwen3-4B bundle's `metadata.json` names the same version.
- **The conflict avoided:** `qnn-runtime` 2.50 would duplicate `libQnnHtp.so`, `libQnnHtpPrepare.so`, the V79/V81 skel libraries and `libQnnSystem.so`.
- **Decision:** pin `qnn-litert-delegate` to 2.45.0 and leave out `qnn-runtime`. That gives one QNN runtime and no packaging conflicts, and the APK is about 70 MB smaller.
- **GPU fallback:** GenieX doesn't ship `libQnnGpu.so`, so the GPU fallback uses LiteRT's own GPU delegate.
- **Native library packaging:** native libraries are extracted (`useLegacyPackaging = true`) because the Hexagon DSP loads skel libraries from a file path.

## D-009 GenieX exists; Qwen runs without the INTERNET permission (verified against the SDK jar)

- **The SDK:** `com.qualcomm.qti:geniex-android:0.3.1` on Maven Central, package `com.geniex.sdk`. The 0.3.1 API differs from the web docs, for example `LlmCreateInput(model_name, model_path, …)` and a `model_type` field on `ModelPullInput`. The code follows the jar's real signatures, checked with `javap`.
- **Offline use:** GenieX's install guide says to declare INTERNET because the SDK can download weights. We don't. `scripts/push_models.sh` pushes the AI Hub bundle folder (`metadata.json` plus `.bin` shards), and the app imports it with `HubSource.LOCALFS`. The docs describe this as "imports it into the SDK cache (no network)". The GenieX AAR manifest declares no INTERNET permission (checked).
- **Bundle facts:** Qwen3-4B-Instruct-2507 for Snapdragon 8 Elite Gen 5 is w4a16, 4 shards, context 4096. The `qairt` runtime rejects `nCtx` and `nGpuLayers` overrides, so the default `ModelConfig()` is used.
- **Loading and fallback:** Qwen loads lazily. It falls back to `models/qwen3_1_7b` if the 4B bundle fails to load, or if a parse takes more than 6 s.
- **Open, to check on the device:** what `getPaths()` returns for a LOCALFS import; load time; time to first token; tokens/s.

## D-010 SigLIP2 checkpoint and input convention

- **Checkpoint:** the AI Hub model card names `google/siglip2-base-patch16-224` at 224×224. `tools/siglip_labels.py` embeds the brief's 15 labels with that checkpoint's text tower (`max_length = 64` padding) and writes `assets/siglip_labels.json`, which includes `logit_scale` and `logit_bias`.
- **Public export:** the TFLite export can be downloaded without a token: `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/siglip2/releases/v0.63.0/siglip2-tflite-float.zip`.
- **Open:** does the exported graph expect `[0,1]` input or `[-1,1]`? `model_config.json` defaults to `zero_one` (AI Hub's convention). `tools/siglip_labels.py --tflite …` runs the export on the Mac under both conventions against the HF vision tower and prints the cosine for each; set the value to the one that matches.
- **Gatekeeper calibration (2026-09-26):** using Hugging Face reference embeddings on the 16-image seed set, every document has a margin of at least +0.031 (the lowest is the ABHA card) and every non-document is at or below −0.028. A threshold of **0.0** with an uncertain band of **0.02** classifies all 16 correctly (16/16). The top label was correct for all 7 images spot-checked in `siglip_labels.py`. **Open:** repeat with on-device embeddings once the device-vs-Python cosine is confirmed ≥ 0.99, and with real photos (the seed set is small and synthetic).
- **Doc-type hints:** a label's primary type gets full weight, peers share it (for example the six ID card types), and a SigLIP-led decision needs a combined score of at least 2.5 with a margin of at least 1.2.

## D-011 Nomic Embed Text v1.5 pipeline (tokenizer verified; model open)

- **Tokenizer parity (verified):** the Kotlin WordPiece matches Hugging Face token IDs exactly on 22 cases (`tokenizer_parity.json`). They cover Hindi, Telugu, zero-width joiners, emoji, CJK, accents, a word over 100 characters, truncation at 128, and the empty string.
- **Input and output:** the AI Hub card says the input is `1x128`. The app reads the output shape at load time: per-token output is mean-pooled over the mask, and pooled output is used as is. Either way the vector is L2-normalized. The shape is logged and shown on the Benchmark screen.
- **Embedded text:**
  - Chunk 0 is `search_document: <English label>. <key facts>`, fitted to 120 tokens.
  - Chunks 1 onward are `search_document: <English label>. <OCR chunk>`, with OCR chunks of at most 110 tokens and a 20-token overlap.
  - Queries are embedded as `search_query: <query_en>`.
- **Pooling:** mean pooling followed by L2 normalization, with no layer norm. `tools/tokenizer_parity.py --embed` uses the identical pipeline, so the device-vs-Python parity check compares like with like.
- **Latency:** the deck's "~4 ms" is not assumed. The Benchmark screen records the measured value.

## D-012 OCR and entity extraction

- **ML Kit:** Latin and Devanagari (both bundled) run on every page, and their lines are merged. A Devanagari-recognizer line containing Devanagari script replaces any overlapping Latin line. Tesseract `tel+eng` runs when fewer than 40 useful characters come back, or when most lines have low confidence.
- **Tesseract data:** `tel` comes from `tessdata_best` (9.1 MB), because Tesseract is the only Telugu OCR here. `eng` and `hin` come from `tessdata_fast` (4.1 MB and 1.1 MB), because ML Kit already covers those scripts. `scripts/fetch_tessdata.sh` downloads them and verifies each file's git blob hash.
- **ML Kit Entity Extraction is not used.** **Likely:** its models are downloaded on demand (`downloadModelIfNeeded`), which needs a network the app doesn't have. The brief makes regex authoritative in any case. Regex covers Aadhaar with Verhoeff, PAN, IFSC, vehicle numbers including BH series, policy numbers, ₹ amounts, phones and ABHA numbers or addresses.
- **Word boxes:** they are stored per page, normalized to `[0,1]` (the `pages` table), so masking works at any render size.

## D-013 Search and storage details

- **Full-text search:**
  - FTS5 is used if a probe at first open succeeds, otherwise FTS4.
  - The `unicode61` tokenizer treats combining marks as separators, which would split Hindi and Telugu words at every vowel sign. The combining marks of the Devanagari and Telugu blocks are therefore declared as `tokenchars`.
  - The FTS table is created outside Room, because Room has no FTS5 annotation.
- **SQLCipher key:** passed in raw-key form (`x'…'`), so no PBKDF2 runs at open (for the cold-start target).
- **Vectors:** stored as float32 BLOBs and searched by brute-force cosine in memory. The Benchmark screen shows the last search time.
- **Deferred LLM work:** background ingest never loads the 4B model. When rules can't decide a doc type or an expiry date, the document is flagged `needs_llm`, and the rule fallback is used meanwhile (for expiry, the earliest future date next to a keyword). `LlmEnricher` finishes those steps when Qwen is loaded in the foreground, choosing a doc type from the enum or an expiry by candidate index.

## D-014 Emergency screen, screen capture, and release signing

- **Emergency screen:** text only, with no document images, so an Aadhaar photo can never appear unmasked there. The Quick Settings tile opens it with `showWhenLocked` (D-001).
- **Screen capture:** `FLAG_SECURE` is on by default. Settings has an "Allow screenshots and screen mirroring (for demos)" switch, off by default, because `FLAG_SECURE` also blacks out demo mirroring.
- **Release build:** signed with the debug key so it can be sideloaded with adb, which makes it unsuitable for the Play Store. Minification is off, because R8 keep rules for GenieX, QNN, ML Kit and LiteRT aren't written yet.

## D-015 Seed data and host-side tools

- **Seed documents:** `tools/make_seed_docs.py` writes 13 synthetic documents and 3 non-documents, plus a 2-page PDF. They include Hindi and Telugu text shaped with raqm, Aadhaar-format numbers that are fake but pass Verhoeff, a real QR code containing only "FAKE SAMPLE DATA", a health policy expiring in 7 days, and an already-expired car policy. `seed_manifest.json` lists the expected type of every file.
- **Translations:** Hindi and Telugu UI strings cover every English key (checked by script). They were written without a native-speaker review, which is recommended before a public demo.

## D-016 Off-device step, disclosed: label text vectors for both SigLIP2 gates (decided, 2026-09-27)

Two files ship precomputed SigLIP2 text vectors instead of running a text encoder on the phone: `assets/siglip_labels.json` (the document-vs-photo gate, D-010) and `assets/photo_labels.json` (the gallery photo categorizer). Both are generated once, on a development machine, by `tools/siglip_labels.py` and `tools/photo_labels.py`.

- **What runs off-device:** only the text tower, only on a fixed, hardcoded list of ~45 short catalog strings ("a selfie", "a receipt", "a scanned document page", ...). No photo, no OCR text, no filename, no location and no other user data is ever part of this step. The result is a small JSON of numbers; nothing about a specific user's phone informs it.
- **What runs on the phone:** everything else. Every photo's own embedding (the vision tower, on the NPU), the gate decision, the category decision, OCR, entity extraction, chat, search — all of it, on-device, for every run, forever.
- **Why not fully on-device:** the public AI Hub SigLIP2 export's text tower (`text_encoder.tflite`) is 1.13 GB, float-only (no quantized build published), against 369 MB for the vision tower already bundled and 548 MB for Nomic. Adding it would nearly triple the app's model footprint to compute 45 strings once, and it uses a different tokenizer (not the WordPiece already built for Nomic), so it is not a drop-in file copy either. This conflicts with the project's "lightweight app" goal for a cost that buys nothing at runtime, since the output is static.
- **The user's instruction (2026-09-27), going forward:** any step that runs off the phone — for any reason, even one that touches no user data — is flagged to the user before it's done, not after. This decision record is that disclosure for the two files already shipped this way; it does not need repeating for them again, but a *new* off-device step anywhere else in the project does.
- **Revisit if:** AI Hub publishes a quantized SigLIP2 text encoder small enough not to conflict with "lightweight," in which case both label files should be regenerated on-device at first launch instead.
