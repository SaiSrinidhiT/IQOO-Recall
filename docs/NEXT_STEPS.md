# Next steps: iQOO Recall

**Where we are (2026-09-26):** the architecture is built and compiles, and 51 unit tests pass. No step has run on the phone yet. This file lists everything left, in order. Tick the boxes as you go. **Owner** says who does it: **You** means something only you can do (phone, accounts, tokens); **Claude** means it can be done from this Mac.

---

## 0. Blocked on you (do these first)

| # | Task | Why it's needed | How |
|---|---|---|---|
| 0.1 | Connect the iQOO 15 over USB | Every gate runs on the phone | Settings → About phone → tap "Software version" 7 times → Developer options → turn on USB debugging (and "Install via USB" if shown). Plug in and tap **Allow** on the prompt. Check with `adb devices`. |
| 0.2 | Get the model files into `./models/` | The app uses rules-only fallbacks without them | `siglip2_vision.tflite`: public AI Hub download (URL in README). `nomic_embed_text.tflite`: AI Hub export for Snapdragon 8 Elite Gen 5. `qwen3_4b_instruct_2507/`: the AI Hub Genie bundle for SM8850 (`metadata.json` + `.bin` shards). Optional: `qwen3_1_7b/` as a fallback. |
| 0.3 | AI Hub token, only if a download needs it | The Nomic and Qwen downloads may need your account | Run `.venv/bin/qai-hub configure --api_token <TOKEN>` yourself. The token stays out of the repo and the chat. |
| 0.4 | Allow permissions on the phone when asked | Photos, notifications, camera, microphone, exact alarms | Tap Allow. Settings in the app shows what is missing. |
| 0.5 | (Optional) Install Telugu and Hindi voice packs | Offline voice input and read-aloud in te/hi | Android settings: offline speech recognition languages; Text-to-speech voices |

---

## 1. Quick fixes in the current code (before device testing)

These gaps were found by reviewing the code. Each is small.

- [ ] **Models screen can't be reached.** The route exists but nothing opens it. Add a Home banner when a model is missing and a button on Benchmark. *(Claude)*
- [ ] **DD/MM ambiguity isn't recorded.** The parser flags it, but the flag isn't stored or shown. Add a column and a note on the document screen. *(Claude)*
- [ ] **ID cards can take the date of birth as `issued_on`.** Make `IssueDatePicker` skip dates next to DOB/birth keywords, and add a unit test. *(Claude)*
- [ ] **Emergency over the lock screen:** "Open the full vault" should also ask Android to dismiss the keyguard (`KeyguardManager.requestDismissKeyguard`). *(Claude)*
- [ ] **Person filter:** the engine supports `ownerFilter` for checklists and search, but the UI doesn't expose it (only the Vault screen has it). *(Claude)*
- [ ] **Dates on Home** ("saved on …") are converted in UTC. Use the local time zone. *(Claude)*

---

## 2. Phase gates on the phone (brief §10)

### Phase 0: the app runs on the phone
- [ ] `adb devices` shows the phone. Record `getprop ro.build.version.release` and `getprop ro.soc.model` in `docs/DECISIONS.md`. *(You connect, Claude records)*
- [ ] `./gradlew installDebug`, launch, unlock: the encrypted vault opens and Home appears. *(Claude)*

### Phase 1: models (don't start Phase 2 until this passes)
- [ ] Check SigLIP2's input convention: run `.venv/bin/python tools/siglip_labels.py --images tools/out/seed/*.jpg --tflite models/siglip2_vision.tflite`, then set `siglip.input_range` in `app/src/main/assets/model_config.json`. *(Claude)*
- [ ] Push the models with `scripts/push_models.sh`. *(Claude)*
- [ ] Benchmark screen, for SigLIP2 and Nomic: which compute unit actually ran (NPU, GPU or CPU), load time, and latency p50/p95. Record the real Nomic latency (the deck claims about 4 ms). *(Claude)*
- [ ] Parity: device vs Python cosine ≥ 0.99. Covers SigLIP2 on 3 images (the reference is `tools/ref/siglip_ref.json`) and Nomic on text (run `tools/tokenizer_parity.py --embed` for the references). **This needs the instrumented parity test from §5, which isn't written yet.** *(Claude)*
- [ ] Qwen: Benchmark → "Load Qwen" → all 5 prompts return valid JSON. Record load time, time to first token and tokens/s. If a parse takes more than 6 s, confirm the 1.7B fallback kicks in and log it. *(Claude)*
- [ ] Write the numbers into `docs/DECISIONS.md` and `docs/EVAL.md`. *(Claude)*

### Phase 2: ingest
- [ ] Generate and push the seed set: `DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib .venv/bin/python tools/make_seed_docs.py`, then `scripts/push_seed.sh`. Grant photo access and let indexing finish. *(Claude; you tap Allow)*
- [ ] At least 85% of seed documents typed correctly, compared with `tools/out/seed/seed_manifest.json`. **This needs a small on-device check that maps seed file names to documents; not written yet.** *(Claude)*
- [ ] All 3 non-documents skipped. *(Claude)*
- [ ] Kill the app mid-index (`adb shell am kill com.hackathon.recall`), relaunch: indexing resumes with no duplicates. *(Claude)*
- [ ] Camera scan of a printed page. Add PDF with `tools/out/seed_pdf/bank_statement_2pages.pdf`. A password-protected PDF shows a message. *(You hold the phone, Claude checks)*

### Phase 3: search and answers
- [ ] Benchmark → "Run EVAL queries", in rules mode and in Qwen mode: hit@3 ≥ 80% overall and ≥ 70% per language. Tune `rule_lexicon.json` and the prompts on the misses. *(Claude)*
- [ ] Voice input in en-IN, hi-IN and te-IN. Note which offline packs exist; typing must always work. *(You speak, Claude checks)*
- [ ] Answers cite only the documents shown, and "nothing found" appears in the user's language. *(Claude)*

### Phase 4: packs and Aadhaar masking
- [ ] The home loan flow works end to end: checklist counts (salary slips 3 of 3), "Scan" on a missing item ticks it off live, "Make masked PDF and share" works. *(You and Claude)*
- [ ] The exported PDF has no unmasked Aadhaar. The masker already re-reads every page; add an independent check that runs OCR on the final PDF file (instrumented test or host script) and confirms the QR code is hidden. *(Claude)*
- [ ] The blocked-share path shows its warning (for example, an Aadhaar number found but not located). *(Claude)*

### Phase 5: reminders and emergency
- [ ] Document screen → "Test reminder in 1 minute" → the notification fires → tapping it opens the right renewal checklist. *(Claude)*
- [ ] With exact alarms denied, the WorkManager path still delivers. After a reboot, reminders are re-armed. *(Claude)*
- [ ] Emergency opens from the Home button and from the Quick Settings tile, including over the lock screen. Read-aloud works in te/hi, or shows the English-fallback message. *(You add the tile, Claude checks)*

### Phase 6: hardening
- [ ] Finish the edge cases in §3 below. *(Claude)*
- [ ] Release build: no INTERNET (already verified), `FLAG_SECURE`, log scrubbing. *(Claude)*
- [ ] **Gate:** the full flow works in **airplane mode** after a cold start. *(You and Claude)*

### Phase 7: docs
- [ ] README with the final device numbers; `EVAL.md` results; `DECISIONS.md` updates. Re-check attribution (Qualcomm QNN/QAIRT terms, ML Kit terms) and known limitations. Write a 2-minute demo script for the judges. *(Claude)*

---

## 3. Edge cases (brief §8): current status

**Done** means it is implemented and still needs a phone check. **Partial** means part of it exists. **Not started** means nothing is written yet.

| Area | Case | Status | What's left |
|---|---|---|---|
| Permissions | Android 14+ partial photo access | Done | Check the banner and the "Change access" flow on the phone |
| Permissions | Permission denied or revoked at runtime | Partial | Detect revocation, pause the worker without marking items failed, show a banner |
| Permissions | Notifications denied | Partial | Show in-app upcoming expiries as the fallback and prompt again |
| Permissions | Exact alarms denied | Done | WorkManager fallback plus a Settings link |
| Permissions | Biometric not enrolled | Done | Device credential fallback; with no screen lock at all, a warning |
| Input | HEIC, WebP, very large images | Done | Check HEIC decoding on the phone |
| Input | EXIF rotation | Done (**Likely**) | `ImageDecoder` applies EXIF orientation; check with a rotated photo |
| Input | Blurry or dark scans | Not started | Blur (Laplacian variance) and brightness check; "Rescan" prompt for the camera; flag gallery images |
| Input | Multi-page PDFs | Done | Up to 30 pages, rendered from memory |
| Input | Password-protected PDFs | Done | Skipped with a message |
| Input | Screenshots, WhatsApp/Downloads folders | Done | Check the real `RELATIVE_PATH` values on the phone |
| Indexing | App killed mid-index | Done by design | Delete orphaned vault files at start (`VaultFileStore.orphans()` exists but isn't called); run the kill test |
| Indexing | Source deleted from the gallery | Not started | The `source_missing` column exists; add a periodic check that sets it |
| Indexing | Thermal throttling or low battery | Partial | Battery-not-low constraint only; add a thermal-status check between items |
| Indexing | Storage full | Not started | Check free space before vault writes; pause with a message |
| Indexing | Thousands of images on the first run | Partial | Batches of 25 with progress and ETA; add a foreground service (dataSync) for long runs; measure 1,000 images |
| Understanding | OCR returns nothing | Partial | Give camera scans an explicit "couldn't read" message |
| Understanding | Mixed scripts on one page | Partial | Run Tesseract Telugu on low-confidence regions of mostly-English pages |
| Understanding | Devanagari or Telugu digits | Done | Unit-tested |
| Understanding | Several dates on one page | Done | Expiry rules plus LLM candidate choice; unit-tested |
| Understanding | DD/MM vs MM/DD | Partial | Store and show the ambiguity flag (quick fix in §1) |
| Understanding | Expiry already past | Done | "Expired" badge; past reminders skipped |
| Understanding | Two people's documents | Partial | Person filter in checklist and search (quick fix in §1) |
| Search | Romanized or code-mixed queries, spelling mistakes | Done | Unit-tested; tune on EVAL misses |
| Search | No match, very long query, Qwen JSON failure, invented doc ID | Done | Check on the phone |
| Duplicates | Exact and near-duplicates | Done | Calibrate the near-duplicate thresholds (0.95 / 0.8) on real photos |
| Security | Masking fails → fail closed | Done | Independent PDF check (Phase 4) |
| Security | Nothing sensitive in logs | Partial | The current 13 log calls hold no OCR text, IDs or names (checked); add a release logger that drops debug and info logs |
| Security | `FLAG_SECURE` on vault screens | Done | Settings has a demo switch for mirroring |
| Models | Missing or corrupt files | Partial | Size manifest exists; add SHA-256 and the Models-screen entry point |
| Models | NPU delegate fails | Done | Falls back to GPU, then CPU, with reasons on Benchmark |
| Models | Out of memory with Qwen plus the embedders | Not started | Unload the embedders during long generation; handle `onTrimMemory` |

---

## 4. Performance targets to measure (brief §12)

| Target | Budget | Where it shows up | Result |
|---|---|---|---|
| Gatekeeper + OCR + embed per document | < 1.5 s average | Benchmark: `ingest.document` | pending |
| Non-document | < 150 ms (SigLIP2 only) | Benchmark: `siglip.embed` | pending |
| Query to results, no LLM | < 1.5 s | `query.parse.rules` + `query.search` | pending |
| Query with Qwen parse and answer | < 5 s | `query.parse.llm` + `query.answer.llm` | pending |
| Cold start to usable | < 3 s | **not instrumented yet**: add an app-start → vault-ready metric | pending |
| Index 1,000 gallery images | report the real number | needs a 1,000-image synthetic set | pending |

---

## 5. Tests still to write

- [ ] Instrumented `ModelSmokeTest`: each model loads, embeds, and reports its backend. *(brief §9)*
- [ ] Instrumented parity test: SigLIP2 and Nomic on the phone vs `tools/ref/*.json`, with cosine ≥ 0.99.
- [ ] Instrumented end-to-end ingest: seed document → correct type → searchable. *(brief §9)*
- [ ] Exported-PDF masking check (Phase 4).
- [ ] Unit test for the DOB exclusion in `IssueDatePicker`.

---

## 6. Optional, after the gates pass

- ViewModels for screen state; screens currently hold state in Compose and the app is locked to portrait.
- R8 minification with keep rules for GenieX, QNN, ML Kit and LiteRT.
- APK size (153 MB): exclude native libraries for other Hexagon versions. **Likely** only v81 is needed on 8 Elite Gen 5; verify before removing anything.
- A stronger key model: an auth-bound key plus an ingest inbox (docs/DECISIONS.md D-002).
- A native-speaker review of the Hindi and Telugu strings.
- Better ranking if a phone only offers FTS4.

---

## 7. Demo prep for the judges

- [ ] Airplane mode on, then cold start and the full flow. Show the Benchmark screen with the NPU backend and measured latencies.
- [ ] Telugu voice query → home loan checklist → scan a missing item → masked PDF (Aadhaar shows only its last 4 digits).
- [ ] Emergency from the lock screen via the Quick Settings tile, with read-aloud.
- [ ] Turn on Settings → "Allow screenshots and screen mirroring" only while mirroring the demo, then turn it off.
