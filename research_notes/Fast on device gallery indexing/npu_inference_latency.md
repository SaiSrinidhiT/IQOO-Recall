# On-device inference latency and throughput for CLIP/SigLIP-class vision encoders and small text embedders on Snapdragon 8 Elite-class NPUs (Hexagon HTP) via LiteRT/TFLite and QNN

Notes on device naming. Qualcomm AI Hub runs its benchmarks on "Samsung Galaxy S26" for "Snapdragon 8 Elite Gen 5 For Galaxy Mobile", which is SM8850-class like the iQOO 15, and on "Samsung Galaxy S25" / "Dragonwing Q-8750" for "Snapdragon 8 Elite For Galaxy Mobile" (SM8750). In AI Hub's perf.yaml files the S25 and Q-8750 rows are identical. The "For Galaxy" SKUs are Samsung-binned parts, so treat the S26 numbers as a close proxy for the iQOO 15, not an exact match. All AI Hub numbers below come from AI Hub Models release v0.63.0 on QAIRT 2.50 (ONNX Runtime 1.27.1 for the ONNX rows). They measure pure on-device inference with one model at a time and batch size 1. They do not include preprocessing, JNI or Java copies, or model load. Source files were fetched on 2026-09-26.

## Q1. Qualcomm AI Hub published latencies (SigLIP2, OpenAI CLIP, ViT-B/16, Nomic Embed Text, MobileCLIP)

### Takeaway
AI Hub's own SigLIP2 export is `google/siglip2-base-patch16-224`, which is the same model the app uses. Its **image_encoder runs as float TFLite fully on the NPU in 3.30 ms on 8 Elite Gen 5 and 4.11 ms on 8 Elite**. That is roughly 240–300 inferences/s of pure model time. The w8a16 build brings it down to about 2.1–2.5 ms, but that build only exists for QNN_DLC, QNN context binary and ONNX, not for TFLite. Nomic-Embed-Text v1.5 at seq-len 128 takes 3.87 ms as float TFLite on 8 Elite Gen 5. AI Hub lists no MobileCLIP model and no SM8850 page separate from "8 Elite Gen 5 For Galaxy".

### Cited Findings

**SigLIP2 (AI Hub id `siglip2`).** Checkpoint `google/siglip2-base-patch16-224`, image input 224x224, text sequence length 64. Model sizes: image_encoder 352 MB float and 92.8 MB w8a16; text_encoder 1.05 GB float and 461 MB w8a16. Supported precisions are float and w8a16 — [HF qualcomm/SigLIP2 model card](https://huggingface.co/qualcomm/SigLIP2); [manifest.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/manifest.yaml)

SigLIP2 image_encoder latency in ms, primary compute unit NPU, from the [HF card](https://huggingface.co/qualcomm/SigLIP2) and [perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/perf.yaml):

| Runtime / precision | 8 Elite Gen 5 (S26) | 8 Elite (S25) | 8 Gen 3 | 8 Gen 1 |
|---|---|---|---|---|
| TFLite float | **3.299** | 4.108 | 5.881 | 12.937 |
| QNN_DLC float | 3.421 | 4.237 | 5.954 | 13.211 |
| ONNX float | 3.338 | 4.189 | 5.814 | 12.987 |
| QNN_DLC w8a16 | 2.46 | 3.306 | 4.255 | — |
| ONNX w8a16 | **2.116** | 2.845 | 3.683 | 7.156 |

- Peak memory for the SigLIP2 image_encoder TFLite float build is 0–169 MB on the S26 and 0–165 MB on the S25 — [HF qualcomm/SigLIP2](https://huggingface.co/qualcomm/SigLIP2)
- SigLIP2 text_encoder (seq 64): TFLite float takes 2.414 ms on the S26 and 2.406 ms on the S25. QNN_DLC w8a16 takes 1.232 ms on the S26 and 1.276 ms on the S25 — [HF qualcomm/SigLIP2](https://huggingface.co/qualcomm/SigLIP2)
- The only CPU data point is on an automotive SoC, not a phone. The SigLIP2 image_encoder as TFLite float on the "SA7255P ADP" ran on CPU (551 of 551 layers) in 400.273 ms — [perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/perf.yaml)
- AI Hub's SigLIP2 export supports these runtime/precision pairs:
  - float: TFLITE, QNN_DLC, QNN_CONTEXT_BINARY, ONNX, PRECOMPILED_QNN_ONNX.
  - w8a16: QNN_DLC, QNN_CONTEXT_BINARY, ONNX, PRECOMPILED_QNN_ONNX. **There is no w8a16 TFLite build.**

  Source: [siglip2/export.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/export.py)
- Input contract for the AI Hub SigLIP2 image encoder: float32 `[B, 3, 224, 224]`, RGB, values in [0, 1]. The graph itself applies `image*2-1` (mean 0.5, std 0.5) and L2-normalises the output embedding — [siglip2/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/model.py)

**OpenAI-Clip (AI Hub id `openai_clip`).** Checkpoint ViT-B/16, 150M params (image and text encoders combined in one graph), 571 MB float, 224x224 input, text context length 77. Latency in ms on the NPU — [HF qualcomm/OpenAI-Clip](https://huggingface.co/qualcomm/OpenAI-Clip):
  - TFLite float: 12.822 on 8 Elite Gen 5 and 17.003 on 8 Elite.
  - QNN_DLC float: 13.098 and 16.747.
  - QNN_DLC w8a16: 11.506 and 15.226.
  - ONNX w8a16: 11.485 and 14.918.
- Caveat on that CLIP number: it is one graph that takes an image plus `[1, captions_per_image, 77]` text tokens. It is **not** image-encoder-only, so it is not directly comparable to the SigLIP2 image_encoder figure — [openai_clip/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/openai_clip/model.py)

**Plain ViT-B/16 classifier (AI Hub id `vit`, torchvision, 86.6M params).** This is the best like-for-like proxy for a bare ViT-B/16 vision tower at 224 px. Latency in ms on the S26 (8 Elite Gen 5) and S25 (8 Elite) — [vit/perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/vit/perf.yaml); [vit/manifest.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/vit/manifest.yaml):

| Runtime / precision | S26 | S25 |
|---|---|---|
| TFLite float | 3.612 | 4.759 |
| ONNX float | 3.457 | 4.576 |
| QNN_DLC float | 5.141 | 6.118 |
| QNN_DLC w8a16 | 3.567 | 4.917 |
| ONNX w8a16 | 2.981 | 4.124 |
| TFLite w8a8 | 5.213 | 7.462 |
| QNN_DLC w8a8 | 4.279 | 5.876 |
| QNN_DLC w8a8_mixed_int16 | 4.664 | 6.249 |

**Nomic-Embed-Text (AI Hub id `nomic_embed_text`).** Checkpoint v1.5, 137M params, 523 MB float, input 1x128 tokens ("seqlen can vary" at export time). Float only; no quantised build is published. Latency in ms, NPU — [HF qualcomm/Nomic-Embed-Text](https://huggingface.co/qualcomm/Nomic-Embed-Text); [nomic perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/nomic_embed_text/perf.yaml):

| Runtime | 8 Elite Gen 5 | 8 Elite | 8 Gen 3 | 8 Gen 1 |
|---|---|---|---|---|
| TFLite float | **3.872** | 4.294 | 5.618 | 11.06 |
| QNN_DLC float | 3.87 | 4.274 | — | — |
| ONNX float | 3.837 | 4.258 | — | — |

- Peak memory for Nomic TFLite float is 0–194 MB on 8 Elite Gen 5 — [HF qualcomm/Nomic-Embed-Text](https://huggingface.co/qualcomm/Nomic-Embed-Text)
- AI Hub Models has no MobileCLIP or SigLIP v1 model. The repository's models directory contains `openai_clip`, `siglip2`, `nomic_embed_text`, `vit`, `owl_vit`, `mobile_vit` and similar, but no `mobileclip` — [ai-hub-models models dir](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models)
- The MobileCLIP paper gives Apple-hardware numbers only (iPhone 12 Pro Max, Core ML Tools 7.0, batch 1). Image-encoder latency in ms: MobileCLIP-S0 1.5, S1 2.5, S2 3.6, MobileCLIP-B (ViT-B/16) 10.4, OpenAI ViT-B/16 11.5. ImageNet zero-shot accuracy: S0 67.8%, S2 74.4%, B 76.8%, OpenAI B/16 68.3% — [MobileCLIP, arXiv 2311.17049 (CVPR 2024)](https://arxiv.org/html/2311.17049v2)

### Inferences
- **Pure model throughput for the gate step.**
  - Float TFLite SigLIP2 image_encoder: 3.3 ms, about 300 images/s, on an SM8850-class NPU.
  - w8a16 via QNN: 2.1–2.5 ms, about 400–470 images/s.

  Real pipelines will be limited by JPEG decode, resize and copies, not by the NPU (see Q8). A realistic end-to-end target is plausibly 50–150 photos/s, but this is not measured.
- **Nomic per chunk.** About 3.9 ms per 128-token chunk, roughly 250 chunks/s. Text embedding is not the bottleneck unless the documents are very long.
- Swapping to MobileCLIP-S0/S1 for the gate would cut encoder cost several-fold on Apple hardware. There are no Snapdragon NPU numbers for it, and at 3.3 ms the SigLIP2 encoder is already unlikely to dominate.

### Gaps
- There is no AI Hub listing under the explicit name "SM8850" or "iQOO 15". The S26 "8 Elite Gen 5 For Galaxy" figure is the nearest proxy.
- AI Hub publishes no GPU-delegate or CPU (XNNPACK) latencies for these models on phone SoCs. Its only CPU row is on the SA7255P automotive SoC.
- There is no w8a8 SigLIP2 build and no published quantised Nomic build.

## Q2. Float vs INT8 / W8A16 on the Hexagon HTP: speedup, accuracy loss, and whether float TFLite really runs on the HTP

### Takeaway
Float TFLite **does run fully on the HTP**, which executes fp32 graphs in fp16. AI Hub reports 551 of 551 SigLIP2 image-encoder layers on the NPU. On 8 Elite-class HTPs, quantisation gives only a modest speedup for ViT-B:
- w8a16 is about 1.25–1.6x faster with essentially no accuracy loss.
- w8a8 can be *slower* than float for ViT, and costs 2–3 points of top-1 accuracy.

### Cited Findings
- **All layers on the NPU.** The SigLIP2 image_encoder TFLite float build has layer_counts `{npu: 551, total: 551}` on the S26 and S25; the text_encoder has `{npu: 524, total: 524}`. Nomic TFLite float has `{npu: 775, total: 775}`. There is no CPU or GPU fallback — [siglip2 perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/perf.yaml); [nomic perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/nomic_embed_text/perf.yaml)
- **fp16 is the default HTP precision.** AI Hub TFLite options: `allow_fp32_as_fp16` defaults to true, and `qnn_htp_precision` accepts `kHtpQuantized` or `kHtpFp16`, with a default of `kHtpFp16` "when supported". Float models on HTP "execute the graph with float16 math" — [AI Hub API docs](https://workbench.aihub.qualcomm.com/docs/hub/api.html)
- The QAIRT HTP docs say "QNN HTP supports running float32 networks using float16 math on select Qualcomm SoCs". This is controlled by `QNN_HTP_GRAPH_CONFIG_OPTION_PRECISION` — [QAIRT HTP backend docs](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_backend.html)
- Qualcomm's reference Android helper enables FP16 only when `QnnDelegate.checkCapability(Capability.HTP_RUNTIME_FP16)` is true. It then calls `setHtpPrecision(HtpPrecision.HTP_PRECISION_FP16)`, `setHtpUseConvHmx(HTP_CONV_HMX_ON)` and `setHtpPerformanceMode(HTP_PERFORMANCE_BURST)` — [ai-hub-apps TFLiteHelpers.java](https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/android/tflite_helpers/TFLiteHelpers.java)
- **Conflicting claim.** An Edge Impulse tutorial states "INT8 quantization is mandatory for HTP acceleration support". This is contradicted for 8 Gen 1 and later HTPs by AI Hub's float-on-NPU results above. It probably reflects older or low-tier HTPs such as the QCS6490 — [Edge Impulse QNN tutorial](https://docs.edgeimpulse.com/tutorials/topics/android/qnn-acceleration)
- **SigLIP2 image_encoder speedup from float to w8a16:**
  - 8 Elite Gen 5: 3.299 ms (TFLite float) → 2.46 ms (QNN_DLC w8a16) = 1.34x; ONNX 3.338 → 2.116 ms = 1.58x.
  - 8 Elite: 4.108 → 3.306 ms = 1.24x; ONNX 4.189 → 2.845 ms = 1.47x.

  Speedups are computed from [HF qualcomm/SigLIP2](https://huggingface.co/qualcomm/SigLIP2)
- **ViT-B/16 on the S26 (8 Elite Gen 5):**
  - TFLite float 3.612 ms, compared with TFLite w8a8 5.213 ms (**w8a8 slower**).
  - QNN_DLC w8a8 4.279 ms, compared with ONNX float 3.457 ms.
  - Best is ONNX w8a16 at 2.981 ms.

  Source: [vit/perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/vit/perf.yaml)
- **ViT-B/16 ImageNet top-1 accuracy measured on the Galaxy S25** (1,000-sample partial eval; torch reference 80.6%) — [vit/numerics.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/vit/numerics.yaml):
  - float: 80.5–80.6% (ONNX, QNN, TFLite)
  - w8a16: 80.5%
  - w8a8_mixed_int16: 80.0–80.6%
  - **w8a8: 77.6% (ONNX, QNN) and 78.6% (TFLite)**, a drop of 2–3 points
- **OpenAI-Clip ViT-B/16 COCO text-to-image Recall@1 on the Galaxy S25** (500 samples; torch 58.6%): float 58.6–58.7%, w8a16 58.7–58.8%. There is no measurable loss — [openai_clip/numerics.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/openai_clip/numerics.yaml)
- **Independent paper on SM8750 at 336x336 input.** ViT-B/16 with INT8 on the NPU through QNN via ONNX took 14.9 ms (30-run mean). That compares with 98.9 ms on GPU (FP16, LiteRT Adreno) and 663.0 ms on CPU (LiteRT XNNPACK FP16), a 44.5x CPU/NPU ratio. The authors report "GPU-to-NPU: 1.7–6.9×" across encoder families — ["Phase Matters: Characterizing Heterogeneous Vision-Language Inference on a Mobile SoC", arXiv 2606.27906](https://arxiv.org/html/2606.27906)
- Google says its LiteRT Qualcomm NPU work on 8 Elite Gen 5 used "Int8 weight + int16 activation quantization" (that is, w8a16) and custom attention kernels for transformers — [Google Developers Blog, 24 Nov 2025](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/)

**How to verify which delegate or partitions actually ran:**
- AI Hub profile jobs report per-layer compute-unit counts, the `layer_counts` shown above — [perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/perf.yaml)
- On device, check that the QNN libraries are mapped into the process with `adb shell 'cat /proc/$(pidof -s <pkg>)/maps | grep -i qnn'`. You can also set the QNN delegate option `profiling_level: detailed`, which writes `/sdcard/qnn_profile.json` — [Edge Impulse QNN tutorial](https://docs.edgeimpulse.com/tutorials/topics/android/qnn-acceleration)
- LiteRT CompiledModel supports partial delegation, where "unsupported subgraphs seamlessly run on CPU or GPU" — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)

### Inferences
- **The app already runs fp16 on the NPU.** The current code (`app/src/main/java/com/hackathon/recall/ml/LiteRtModel.kt`, lines 154–162) already sets `HTP_BACKEND`, `HTP_PERFORMANCE_BURST`, `HTP_PRECISION_FP16`, `setCacheDir` and `setModelToken`. The float SigLIP2 TFLite should therefore be running as fp16 on the HTP with no fallback, provided delegate creation succeeds. Confirm this on device (see the log check below). The app uses the `qnn-litert-delegate` 2.45.0 artifact while AI Hub benchmarked on QAIRT 2.50, so small differences are possible.
- **Standard TFLite log check (from general TFLite behaviour; not verified against a source in this session).** Look in logcat for `Replacing N out of M node(s) with delegate (...) node, yielding K partitions`. A value of 551/551 with 1 partition means full NPU delegation. If the QNN delegate constructor throws, the app silently drops to GPU or CPU, so log which backend actually got created.
- **Quantisation is worth at most about 1.3–1.6x (about 1 ms per image) for the gate.** It requires leaving TFLite for QNN_DLC, a QNN context binary or ONNX Runtime-QNN, because AI Hub offers no w8a16 TFLite. Given that preprocessing likely dominates, this is a low-priority knob. Avoid w8a8 for ViT: it gives no speed gain and loses 2–3 points of accuracy.

### Gaps
- There is no published SigLIP2-specific embedding cosine similarity (float vs w8a16), and no zero-shot accuracy delta. The only numerics files are for ViT and OpenAI-Clip; the SigLIP2 folder has no numerics.yaml.
- There is no source for the exact first Snapdragon generation or Hexagon version that supports HTP FP16. AI Hub shows float running on the NPU from 8 Gen 1 onward.

## Q3. Batching (batch 4/8/16) on the HTP, and whether batch is fixed at export time

### Takeaway
Batch size is a static input shape fixed at export or compile time. AI Hub models default to batch 1 but expose `image_batch_size` / `batch_size` as export arguments. No published source quantifies per-image throughput gains from batching ViT-B/16 on the HTP; this is a gap that needs on-device measurement.

### Cited Findings
- The SigLIP2 image encoder's `get_input_spec(image_batch_size=1, image_height=224, image_width=224)` produces shape `(image_batch_size, 3, H, W)`. The text encoder has `text_batch_size=1` — [siglip2/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/model.py)
- Nomic's `get_input_spec(batch_size=1)` produces shape `(batch_size, seq_length)`, with `sequence_length` defaulting to 128 — [nomic_embed_text/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/nomic_embed_text/model.py)
- The qai-hub-models export CLI turns `get_input_spec` parameters into CLI arguments (`add_input_spec_args` / `get_model_input_spec_parser`: "assume the CLI args have the same names as get_input_spec method args"). A batch-N export is therefore a re-export with a different input spec — [utils/args.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/utils/args.py)
- In the native QNN API, "The batch dimension at graph execute can be an integer multiple of the respective dimension provided at graph prepare", and all inputs and outputs must share the same multiple — [QAIRT HTP backend docs](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_backend.html)
- General (non-HTP, older) evidence: ResNet50 throughput rose 8.7x (40 → 350 fps) going from batch 1 to batch 100 on a mobile SoC — [arXiv 1803.09492](https://arxiv.org/pdf/1803.09492)

### Inferences
- **Batching gains are likely modest.** At 3.3 ms per image, the fixed per-call overhead (JNI, delegate dispatch, FastRPC to the DSP) is probably a meaningful fraction, so batch 4–8 may improve images/s somewhat. However, a ViT-B/16 at 224 px (196 tokens) already keeps the HMX matrix units fairly busy, so do not expect ResNet-style large gains.
- **Only a measurement will tell.** Export batch 1, 4 and 8 variants and profile them on AI Hub against the S26 before committing. Batching also raises per-call memory (the S26 float image_encoder already peaks at 169 MB at batch 1).
- **Pipelining is probably the bigger win.** Overlapping CPU decode and preprocessing of image N+1 with NPU inference of image N likely matters more than batching, because the NPU step is short.

### Gaps
- There are no published HTP batch-scaling numbers for ViT, SigLIP or CLIP from Qualcomm, Google or independent sources.
- It is unverified whether the TFLite QNN delegate accepts `resizeInput` to a larger batch at runtime without re-preparing the graph.

## Q4. QNN HTP performance modes and how to set them; effect on latency and thermals

### Takeaway
Performance mode is set per delegate: `QnnDelegate.Options.setHtpPerformanceMode(...)` for the TFLite QNN delegate, or `qnn_htp_performance_mode` on AI Hub. **AI Hub's published latencies use BURST, which is the default.** There is no published latency or thermal comparison across modes for these models.

### Cited Findings
- AI Hub `--tflite_options qnn_htp_performance_mode=` accepts `kHtpLowPowerSaver`, `kHtpPowerSaver`, `kHtpHighPowerSaver`, `kHtpLowBalanced`, `kHtpBalanced`, `kHtpHighPerformance`, `kHtpSustainedHighPerformance` and `kHtpBurst`. **The default is `kHtpBurst`** — [AI Hub API docs](https://workbench.aihub.qualcomm.com/docs/hub/api.html)
- Setting it from Java/Kotlin: `qnnOptions.setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)`. The reference helper maps an AI Hub job's "Runtime Configuration" onto these as follows (`htp_options.performance_mode` → `setHtpPerformanceMode`, `htp_options.precision` → `setHtpPrecision`, `htp_options.useConvHmx` → `setHtpUseConvHmx`). It describes BURST as "maximum performance (at the cost of device battery life / heat / precision)" — [TFLiteHelpers.java](https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/android/tflite_helpers/TFLiteHelpers.java)
- The JSON-options form of the QNN TFLite delegate uses `"htp_performance_mode"` with the values `"burst"` (maximum speed), `"high_performance"`, `"balanced"` and `"low_power"` — [Edge Impulse QNN tutorial](https://docs.edgeimpulse.com/tutorials/topics/android/qnn-acceleration)
- **Underlying mechanism.** The low-level QNN HTP API votes clocks through DCVS_V3 (for example, the power mode `QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE` with voltage corner `DCVS_VOLTAGE_VCORNER_TURBO`). It also has `setSleepLatency` (10–65535 µs; "Latency critical applications recommended to vote for greater than 0 and less than 200 us") and `setDcvsEnable`. The v85+ HTPs add finer `DCVS_V3_EXP` corners — [QAIRT HTP backend docs](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_backend.html)

### Inferences
- For multi-minute background indexing, `HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE` or `HIGH_PERFORMANCE` is the likely better trade-off: steadier clocks, less heat, and less competition with the foreground UI. BURST gives the best per-call latency (and AI Hub's numbers are BURST). The exact enum names in the Java API are inferred from the AI Hub `kHtp*` naming and the `HTP_PERFORMANCE_BURST` pattern; check them against the 2.45 AAR.
- Expect latency in sustained mode to be a modest multiple of burst (not quantified anywhere found). Since preprocessing likely dominates, the throughput cost of a sustained mode is probably small.

### Gaps
- No published per-mode latency numbers were found for ViT, SigLIP or Nomic, nor any per-mode power or thermal curves for the Hexagon NPU.

## Q5. Delegate initialisation / graph compilation cost and caching

### Takeaway
The first-load on-device compile (JIT) of a large model costs seconds; cached or AOT loads cost hundreds of ms. Google measured **ResNet152 initialisation dropping from 7,465 ms to 198 ms with LiteRT's compiled-model cache**. The QNN TFLite delegate caches its compiled context when `setCacheDir` and `setModelToken` are set, and the app already does this.

### Cited Findings
- **LiteRT CompiledModel compile modes.** AOT "significantly reduces initialization costs and lowers memory usage". On-device (JIT) compile gives "higher first-run cost" — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- **LiteRT cache mechanics.** Enable it with the `CompilerCacheDir` environment option. The model recompiles only if the vendor compiler plugin version, the Android build fingerprint, the model, or the compile options change. Example: ResNet152 initialisation went from **7,465 ms to 198 ms** and memory from **1,525 MB to 355 MB** with the cache — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- **QNN TFLite delegate caching.** "If the cache dir and model token are set, the compiled asset will be stored on disk so the model does not need to be recompiled on each load" (`qnnOptions.setCacheDir(cacheDir); qnnOptions.setModelToken(modelIdentifier)`). The reference app uses an MD5 hash of the model file as the token — [TFLiteHelpers.java](https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/android/tflite_helpers/TFLiteHelpers.java)
- **GPU delegate equivalent.** `gpuOptions.setSerializationParams(cacheDir, modelIdentifier)`, with `INFERENCE_PREFERENCE_SUSTAINED_SPEED` and `setPrecisionLossAllowed(true)` — [TFLiteHelpers.java](https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/android/tflite_helpers/TFLiteHelpers.java)
- **Context binaries skip online prepare.** Loading a serialized context binary avoids online graph preparation. The QAIRT docs note that O3 finalize optimisation (`QNN_HTP_GRAPH_OPTIMIZATION_TYPE_FINALIZE_OPTIMIZATION_FLAG = 3`) "may yield possible larger context binary size", and that `QNN_HTP_CONTEXT_CONFIG_OPTION_FILE_READ_MEMORY_BUDGET` controls chunked loading — [QAIRT HTP backend docs](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_backend.html); [ONNX Runtime QNN EP docs](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- **Ready-made per-chipset binaries.** AI Hub's `QNN_CONTEXT_BINARY` export target, available for SigLIP2 in float and w8a16, produces a precompiled HTP binary for a specific chipset. That is the AOT route — [siglip2/export.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/export.py)

### Inferences
- **First launch vs later launches.** Expect the first launch to spend several seconds compiling the roughly 350 MB fp32 SigLIP2 plus the roughly 520 MB Nomic model for the HTP. Later launches should come in at a few hundred ms per model if the cache is hit.
- **Token needs care.** The app's token `"${model.name}-${model.length()}"` works but will not invalidate if a model changes at the same byte length. An MD5 or SHA hash, as in Qualcomm's helper, is safer.
- **Defer the text model.** Load Nomic lazily, only once a document is detected, rather than at app start.

### Gaps
- There are no published QNN delegate first-compile or cached-load timings specific to SigLIP2 or Nomic on 8 Elite. AI Hub profile jobs record load times, but these are not in the public perf.yaml.

## Q6. GPU delegate (Adreno) and CPU/XNNPACK fallback latency for ViT-B

### Takeaway
On SM8750, a ViT-B/16 encoder takes about **99 ms on GPU and about 663 ms on CPU at 336 px, against about 15 ms on the NPU**. Scaling to 224 px (196 vs 441 tokens) suggests roughly 35–45 ms on GPU and 200–300 ms on CPU (inferred). Falling back off the NPU therefore cuts gate throughput by roughly 10x (GPU) to 60x or more (CPU).

### Cited Findings
- SM8750 (Oryon CPU at 4.32 GHz, LPDDR5x-5300). ViT-B/16 encoder at 336x336, mean of 30 runs: **CPU 663.0 ms** (LiteRT XNNPACK FP16), **GPU 98.9 ms** (LiteRT Adreno FP16), **NPU 14.9 ms** (QNN via ONNX, INT8). NanoVLM-222M encoder: 638.3 / 98.8 / 14.3 ms. Phi-3.5-V CLIP encoder: 2286.1 / 436.0 / 103.5 ms — [arXiv 2606.27906](https://arxiv.org/html/2606.27906)
- Across 72 models on 8 Elite Gen 5, Google reports that GPU cuts latency to about 5–70% of the CPU baseline and NPU to about 1–20%, with "up to a 100x speedup over CPU and a 10x speedup over GPU" — [Google Developers Blog, Nov 2025](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/)
- LiteRT Qualcomm NPU page (Galaxy S25 / 8 Elite): MobileNetV2 takes 0.3 ms on NPU, 1.8–2.7 ms on GPU and 2.8–4.1 ms on CPU. FFNet-40s takes 24.9 ms on NPU, 43–68.2 ms on GPU and 481.7–871.1 ms on CPU. The ranges span the S23, S24 and S25 — [LiteRT Qualcomm NPU docs](https://developers.google.com/edge/litert/android/npu/qualcomm)
- Automotive CPU reference, not a phone: SigLIP2 image_encoder TFLite float on the SA7255P CPU took 400.273 ms — [siglip2 perf.yaml](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/perf.yaml)
- MobileCLIP paper on the iPhone 12 Pro Max Neural Engine: OpenAI ViT-B/16 image encoder 11.5 ms — [arXiv 2311.17049](https://arxiv.org/html/2311.17049v2)

### Inferences
- The 224 px estimates assume cost scales with the token count (441/196 = 2.25x, with attention growing faster than that). This is not measured.
- **Log the backend, and change strategy on fallback.** If the app ever lands on CPU with 4 threads, gating a 10k-photo gallery would take on the order of an hour, not tens of seconds. When this happens, show the chosen backend in logs or UI. On a CPU fallback, consider cheaper gating instead: MediaStore thumbnails, heuristics, or a MobileCLIP-S0-class model.

### Gaps
- There is no phone-SoC CPU or GPU latency for exactly SigLIP2-B/16 at 224 px with LiteRT.
- The thread count behind the 663 ms CPU figure in arXiv 2606.27906 was not stated in the extracted content.

## Q7. Sustained performance and thermal throttling over thousands of inferences

### Takeaway
No published NPU-specific throttling curve exists for 8 Elite or 8 Elite Gen 5. Evidence covers CPU and GPU stress tests, which show heavy throttling on 8 Elite Gen 5, plus one short (100-run) NPU thermal measurement showing the NPU running about 10 °C cooler than CPU inference.

### Cited Findings
- On SM8750 over 100 back-to-back encoder runs, the NPU stabilised at 40.78 ± 3.03 °C (peak 44.20 °C), compared with 51.25 ± 3.62 °C (peak 55.30 °C) for CPU. That is a 10.47 °C gap, with about 2.52x lower energy per request on the NPU path — [arXiv 2606.27906](https://arxiv.org/html/2606.27906)
- 8 Elite Gen 5 phones under sustained graphics stress: the realme GT8 Pro dropped to "less than 30% of its initial peak" while holding a 44.1 °C skin temperature. The source tests were by Android Authority — [AndroidHeadlines, Nov 2025](https://www.androidheadlines.com/2025/11/qualcomm-snapdragon-8-elite-gen-5-thermal-throttling-heat-hot-tests.html); [Android Authority 8 Elite Gen 5 benchmarks](https://www.androidauthority.com/snapdragon-8-elite-gen-5-benchmarks-3600242/)
- Original 8 Elite CPU stress tests: throttled to 74% of peak in a 15-minute test and 77% in a 60-minute test — [Beebom via search summary](https://beebom.com/snapdragon-8-elite-benchmarks/). Only the search snippet was seen; the page was not fetched.
- Claims such as "throttling inside 4.2 minutes" and "58% within 15 minutes" for 8 Elite Gen 5 CPUs appear on multicoreperformance.com. That site's sourcing is unclear, so treat these as **low-reliability** figures — [multicoreperformance.com](https://multicoreperformance.com/snapdragon-8-elite-gen-5-spec-sheet-desktop-power-meets-22w-heat-trap/)

### Inferences
- A gate pass over thousands of photos is dominated by CPU work (JPEG decode and resize) plus short NPU bursts. **CPU heat, not the NPU, is the likely throttling driver.** Keeping decode cheap (small thumbnails) and using a sustained NPU mode should keep throughput steady.
- Measure the real curve. Run 5–10 minutes of continuous gating while logging per-image ms and `/sys/class/thermal` temperatures, with the phone in a normal (not gaming) thermal profile.

### Gaps
- There is no public Hexagon NPU throttling curve for sustained ViT or CNN inference over minutes on 8 Elite or 8 Elite Gen 5, and nothing iQOO-15-specific.

## Q8. Zero-copy input and preprocessing cost (Bitmap → tensor)

### Takeaway
The AI Hub SigLIP2 graph already does the normalisation internally (input in [0, 1], `x*2-1` in the graph), so Kotlin only needs resize plus RGB extraction to float in [0, 1]. **The layout is NCHW `[1, 3, 224, 224]`.** Check whether the exported TFLite kept NCHW or was transposed to NHWC before writing the fill loop. LiteRT's CompiledModel supports zero-copy AHardwareBuffer inputs. No source gave a measured Kotlin preprocessing time in ms.

### Cited Findings
- SigLIP2 image-encoder input contract: float32 `[B, 3, 224, 224]`, RGB, values in [0, 1]. The model normalises to [-1, 1] (mean 0.5, std 0.5) internally and outputs L2-normalised embeddings — [siglip2/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/siglip2/model.py)
- LiteRT CompiledModel offers `TensorBuffer::CreateFromAhwb()`, which creates NPU buffers from `AHardwareBuffer` and "eliminat[es] CPU memory round-trips" — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- Qualcomm's helper sets `setAllowBufferHandleOutput(true)` and `setRuntime(TfLiteRuntime.FROM_APPLICATION_ONLY)` on the Interpreter — [TFLiteHelpers.java](https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/android/tflite_helpers/TFLiteHelpers.java)
- Standard guidance is to feed a direct `ByteBuffer` (4 bytes × 3 × 224 × 224 = 602,112 bytes) rather than Kotlin float arrays, and to resize with `Bitmap.createScaledBitmap` — [Android TFLite codelab](https://developer.android.com/codelabs/digit-classifier-tflite); [Walmart Global Tech blog](https://medium.com/walmartglobaltech/custom-tensorflow-lite-model-implementation-in-android-5c1c65bd9f97)

### Inferences (not measured; labelled as estimates)
- Per-image CPU cost is likely dominated by decoding the source JPEG, which can reach tens of ms for a 12–50 MP original. It is not dominated by the 50k-pixel normalise loop, which should be around 1 ms or less with `getPixels` into an IntArray and a tight loop into a reused direct ByteBuffer.
- **Biggest single knob for photos/second:**
  - Decode at reduced size (`ContentResolver.loadThumbnail(uri, Size(256,256))`, or `BitmapFactory.Options.inSampleSize` / `ImageDecoder.setTargetSize`).
  - Reuse the input buffer.
  - Run decode on a parallel coroutine pool feeding the single NPU interpreter, which is pipelining.
- With a 3.3 ms NPU step, a single-threaded decode pipeline caps throughput at 1 / (decode ms + preprocess ms + 3.3 ms). Parallel decode across 3–4 cores is where most of the photos/second headroom lies.

### Gaps
- There are no published, device-specific measurements (ms) of Android Bitmap decode, resize and normalise for 224x224 on 8 Elite-class phones. This needs a microbenchmark on the iQOO 15.
- It was not verified whether AI Hub's TFLite export keeps NCHW input or inserts a transpose. Inspect the `.tflite` input tensor shape.
