# Pipelining On-Device Gallery Indexing and Staying Alive Under Android 14/15/16 + vivo/OriginOS Background Rules

Context: Kotlin/coroutines app, minSdk 30, targetSdk 37, iQOO 15 (Snapdragon 8 Elite Gen 5, OriginOS), WorkManager CoroutineWorker indexing thousands of photos. Stages today: read bytes (I/O) -> decode/downscale (CPU) -> SigLIP2 image encoder on NPU (LiteRT/QNN) -> for ~10% docs: ML Kit OCR + Tesseract (CPU/GPU), text embedder (NPU) -> SQLCipher/Room writes.

Research date: 2026-09-26. Note: several official Kotlin docs pages (kotlinlang.org/docs/flow.html) could not be retrieved (redirect); API reference pages were used instead.

---

## Q1. Pipelining with coroutines: Channels/Flow, buffer, flatMapMerge, limitedParallelism, decoder count, NPU saturation, micro-batching, backpressure

### Takeaway
Restructure into a bounded, staged pipeline: an I/O stage (list + read), a small pool of CPU decoders (bounded by `Dispatchers.Default.limitedParallelism(N)` and small channel capacities for backpressure), a single dedicated NPU consumer that owns the LiteRT model(s), a separate low-rate OCR branch for the ~10% documents, and a single batched DB writer. Flow's `buffer()`/`flowOn()` create channel-backed concurrency with SUSPEND backpressure by default; `flatMapMerge` defaults to 16 concurrent flows (too many for bitmap work) and is experimental, so explicit channels + fixed worker counts are safer. The SoC has no "little" cores (2 prime + 6 performance Oryon), so 3-4 decoders are a sensible starting point, tuned by measurement and thermal headroom.

### Cited Findings
- `buffer()` "runs the upstream producer in a separate coroutine" connected by a channel, letting upstream and downstream run concurrently; default capacity `BUFFERED`, options `CONFLATED`, `RENDEZVOUS`, `UNLIMITED` or an explicit integer; default `onBufferOverflow = SUSPEND` (producer suspends when full), alternatives `DROP_OLDEST`/`DROP_LATEST` — [kotlinx.coroutines buffer API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/buffer.html)
- Adjacent `buffer()`, `flowOn()`, `channelFlow()` and `produceIn()` are fused into a single channel; explicitly specified capacities are summed — [buffer API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/buffer.html)
- `flatMapMerge(concurrency = DEFAULT_CONCURRENCY, transform)`: calls `transform` sequentially, then merges with at most `concurrency` inner flows collected at once; default `DEFAULT_CONCURRENCY` is 16; marked `@ExperimentalCoroutinesApi`; docs discourage its use in regular application flows ("linear transformations are easier to reason about"); `flowOn`/`buffer`/`produceIn` after it fuse with its merging channel — [flatMapMerge API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/flat-map-merge.html)
- `limitedParallelism(n)` returns a view guaranteeing "no more than [parallelism] coroutines are executed at the same time" on the underlying dispatcher; views are independent, need no closing; `Dispatchers.IO` is elastic ("safe to replace `newFixedThreadPoolContext(nThreads)` with `Dispatchers.IO.limitedParallelism(nThreads)`"), whereas views of `Default` share its CPU-sized pool — [limitedParallelism API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html)
- Pitfall: `limitedParallelism` limits thread parallelism, not coroutine concurrency — `limitedParallelism(1)` is "not a mutex"; coroutines interleave across suspension points. Use `Mutex`/`Semaphore` to bound in-flight work — [limitedParallelism API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html)
- Snapdragon 8 Elite Gen 5 uses a "2 + 6" CPU layout: 2 prime cores up to 4.60 GHz and 6 performance cores up to 3.62 GHz (third-gen Oryon); NPU claimed 37% faster than Snapdragon 8 Elite — [Android Authority](https://www.androidauthority.com/snapdragon-8-elite-gen-5-benchmarks-3600242/); [FoneArena](https://www.fonearena.com/blog/465280/qualcomm-snapdragon-8-elite-gen-5-features.html); [Qualcomm product page](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-gen-5)
- A third-party spec write-up headlines the chip as a "22W heat trap" (i.e., sustained-load thermals are a concern) — [multicoreperformance.com](https://multicoreperformance.com/snapdragon-8-elite-gen-5-spec-sheet-desktop-power-meets-22w-heat-trap/) (secondary/unverified source)
- LiteRT on Snapdragon 8 Elite Gen 5: "over 56 models run in under 5ms with the NPU, while only 13 models achieve that on the CPU"; NPU up to 100x faster than CPU and 10x vs GPU (vendor/Google claim) — [Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/)
- LiteRT NPU zero-copy: "Using zero-copy enables an NPU to access data directly in its own memory without the need for the CPU to explicitly copy that data" (C++ `TensorBuffer::CreateFromAhwb()`), and chaining NPU inferences keeps data in NPU-managed memory — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- LiteRT NPU docs give no guidance on batching — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- TFLite `resizeInput(idx, dims, strict)` exists (with strict mode only resizing dimensions marked `-1` in `shapeSignature()`) — the mechanism for a dynamic batch dimension on the classic Interpreter — [InterpreterApi.java source](https://raw.githubusercontent.com/tensorflow/tensorflow/master/tensorflow/lite/java/src/main/java/org/tensorflow/lite/InterpreterApi.java)

### Inferences
- Recommended topology (sketch):
  1. `producer(Dispatchers.IO.limitedParallelism(2..4))`: MediaStore cursor -> open/read bytes (or pass URIs; let decoder stream). Channel capacity small (e.g., 8-16 encoded items; encoded JPEGs are only a few MB).
  2. Decoder pool: N coroutines on `Dispatchers.Default.limitedParallelism(N)` receiving from the byte channel, decoding directly at target size (e.g., SigLIP2 input 224/256/384 px) and emitting a ready-to-infer float/uint8 buffer. Output channel capacity ~2x NPU batch size so the NPU never waits but memory stays bounded.
  3. Single NPU consumer (one coroutine on a dedicated single thread, e.g., `newSingleThreadContext` or `Dispatchers.IO.limitedParallelism(1)` + the model object confined to it) that drains the decoded channel, optionally assembling micro-batches (`receive` first item, then `tryReceive` up to B-1 more without waiting) and running inference.
  4. Branch: document-classified items go to a separate OCR channel with 1-2 workers (ML Kit + Tesseract are CPU-heavy and slow); their text embeddings go back to the NPU thread (or a second model object on the same NPU thread) to avoid concurrent NPU sessions.
  5. Single DB writer coroutine collecting results into batches of ~50-200 rows (or every ~500 ms) and committing one transaction per batch; progress updates derived from committed counts.
- Decoder count: memory math — a full 12 MP ARGB_8888 bitmap is 4000x3000x4 ≈ 48 MB; 50 MP images ≈ 200 MB. Decoding at full size in parallel is the real risk, not CPU count. If decoding is done with subsampling/target size (e.g., `ImageDecoder.setTargetSize` or `BitmapFactory` `inSampleSize` + region), each in-flight bitmap is ~0.2-2 MB and 3-4 decoders are cheap. Start with 3 (leave prime cores + UI thread + NPU driver thread + OCR headroom), measure, scale to 4-5 only if NPU is starved and thermal headroom allows. (Inference; no source benchmarked this exact device.)
- Because the 8 Elite Gen 5 has no efficiency cluster, "background tasks are pinned to little cores" logic from older big.LITTLE chips maps to "background cpuset gets a subset of performance cores" on this SoC — exact OEM cpuset mapping unknown (see Q4).
- NPU saturation: if SigLIP2 on NPU takes a few ms per image and JPEG decode+downscale takes ~10-30 ms per image on one core (typical, unverified), the pipeline is decode-bound; the NPU consumer will be idle most of the time unless 3+ decoders feed it. The OCR branch (hundreds of ms per doc image with Tesseract, unverified) can dominate total wall-time even at 10% of photos — give it its own bounded channel so it doesn't stall the image path, and consider deferring OCR to a second pass.
- Micro-batching on NPU: QNN/HTP models are typically compiled with static shapes; batching needs a model exported/compiled with batch B (AOT). Gains are usually modest for small vision encoders when per-invoke overhead is already low; measure batch 1 vs 4 vs 8 before investing. Prefer zero-copy input buffers (AHardwareBuffer-backed TensorBuffers) and pre-allocated input/output buffers reused per invoke.
- Pitfalls: `Channel.UNLIMITED` or `buffer(UNLIMITED)` between decode and NPU will OOM on large galleries; `flatMapMerge` default 16 concurrency will spawn 16 decodes at once; `runBlocking` inside workers; forgetting to `recycle()`/release bitmaps or `close()` buffers; cancellation — wrap native resources with `try/finally` so `WorkManager` stop (quota/timeout) cleanly closes the interpreter.
- Ensure idempotent/resumable indexing: persist "last indexed MediaStore ID / date_modified" in each DB batch so a stopped worker (quota exhaustion, OEM kill) resumes rather than restarting.

### Gaps
- No authoritative per-stage timings found for JPEG decode, SigLIP2 on Hexagon (8 Elite Gen 5), ML Kit or Tesseract on this device — must be measured (see Q6).
- No official source found on NPU micro-batching gains for LiteRT/QNN on Hexagon.
- Could not retrieve the prose Kotlin Flow guide (kotlinlang.org/docs/flow.html redirect failed); API reference used instead.

---

## Q2. Is the TFLite Interpreter / LiteRT CompiledModel thread-safe? One per thread vs single serialized NPU worker; cost of multiple NPU sessions

### Takeaway
The classic TFLite Java `InterpreterApi` is explicitly documented as not thread-safe; LiteRT's newer `CompiledModel` docs say nothing about thread safety, so treat it as not thread-safe too. The pragmatic design is one model instance confined to one dedicated thread (a single serialized NPU worker) per model, fed by a channel. Multiple NPU sessions cost init/compile time and memory, and concurrent sessions contend for the same Hexagon accelerator, so they rarely increase throughput.

### Cited Findings
- "InterpreterApi instances are not thread-safe." and "An InterpreterApi instance owns resources that must be explicitly freed by invoking close()" — [TFLite InterpreterApi.java source](https://raw.githubusercontent.com/tensorflow/tensorflow/master/tensorflow/lite/java/src/main/java/org/tensorflow/lite/InterpreterApi.java)
- GitHub issue: with the GPU delegate, calling from two different threads can block `interpreter.run()` indefinitely (delegate bound to the thread that created it) — [tensorflow#25657](https://github.com/tensorflow/tensorflow/issues/25657)
- Other community issues report threading problems (e.g., model works with multiprocessing but not multithreading; segfaults around `SetNumThreads`) — [tensorflow#54282](https://github.com/tensorflow/tensorflow/issues/54282); [tensorflow#40722](https://github.com/tensorflow/tensorflow/issues/40722) (anecdotal)
- LiteRT NPU/Qualcomm docs describe the `CompiledModel` API (`CompiledModel.create(assets, path, CompiledModel.Options(Accelerator.NPU, Accelerator.GPU))`, `createInputBuffers()`/`createOutputBuffers()`, automatic fallback to GPU/CPU) but do not address thread safety, async execution or concurrent runs — [Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/); [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu); [LiteRT Qualcomm docs](https://developers.google.com/edge/litert/next/qualcomm)
- Initialization cost: AOT "significantly reduces initialization costs and lowers memory usage"; JIT "can come with some latency and memory overhead to translate the user-provided model into NPU bytecode"; JIT compilation caching (same model, compiler version, build fingerprint) showed 37-97% latency reduction in sample benchmarks — [LiteRT NPU docs](https://developers.google.com/edge/litert/next/npu)
- AOT recommended "for large models where on-device compilation can result in longer initialization times and higher peak memory consumption" — [Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/)

### Inferences
- Create each NPU model (SigLIP2 image encoder, text embedder) once per worker run (or once per process in a singleton), confine it to one thread (`newSingleThreadContext("npu")` or `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`), and close it in `finally`. Do not use `Dispatchers.Default.limitedParallelism(1)` alone if the delegate/QNN context is thread-affine, because that view may hop threads between suspensions — a real single-thread executor guarantees the same OS thread (important given issue #25657's thread-affinity behaviour for GPU delegate; NPU dispatch thread-affinity is unverified).
- Both NPU models can share the one NPU thread (interleaving image and text inference) — the Hexagon is one accelerator, so two parallel sessions mostly time-slice; a second session doubles model memory and init time for little gain.
- Keep CPU-side preprocessing (resize, normalize, quantize) in the decoder pool so the NPU thread only does copy-in/invoke/copy-out.
- Use AOT-compiled models for the SoC (SM8850-class) to avoid JIT compile time on every worker start; if JIT, enable compilation caching.

### Gaps
- No official statement found on LiteRT `CompiledModel` (Kotlin or C++) thread-safety, on whether QNN contexts are thread-affine, or on the cost/limits of multiple concurrent QNN HTP contexts. Check LiteRT GitHub issues/source (`google-ai-edge/LiteRT`) or Qualcomm QNN docs before relying on concurrent sessions.

---

## Q3. Room/SQLCipher write throughput; WAL; cost of per-item progress writes and WorkManager setProgress

### Takeaway
Batch inserts into one transaction per N items (single writer), keep WAL on (Room default) with `synchronous=NORMAL`, and throttle progress: `setProgress` writes to WorkManager's own SQLite DB each call, so emit it at most every ~0.5-1 s or every N items rather than per photo.

### Cited Findings
- Android guidance: "Enable WAL unless you are using ATTACH DATABASE"; with WAL use `PRAGMA synchronous = NORMAL` — "a commit can return before the data is stored in a disk ... because of logging, your database isn't corrupted"; "If only your app crashes, your data still reaches the disk. For most apps, this setting yields performance improvements at no material cost" — [Android SQLite best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- "A transaction commits multiple operations, which improves not only efficiency but also correctness ... you can batch insertions" (beginTransaction/endTransaction example); "Only one write transaction can occur at a time" — serialize writes with a sequential executor — [Android SQLite best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- A programmatic loop of queries is "about 1000 times slower than a single SQL query" (reads guidance, same page) — [Android SQLite best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- Wrapping multiple writes in one transaction can increase write throughput by ~2-20x vs autocommit; committing each row in a loop is an anti-pattern — [PowerSync blog](https://powersync.com/blog/sqlite-optimizations-for-ultra-high-performance) (secondary source)
- Room `JournalMode.AUTOMATIC` (default) resolves to WRITE_AHEAD_LOGGING on non-low-RAM devices, TRUNCATE otherwise — [Room JournalMode reference](https://developer.android.com/reference/androidx/room/RoomDatabase.JournalMode)
- WorkManager progress: "updating progress is asynchronous, given that the update process involves storing progress information in a database"; progress `Data` is "subject to the same restrictions" as input/output Data; progress can only be set while running (later calls ignored) — [WorkManager observe progress](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe)

### Inferences
- Per-photo: today potentially 2+ SQLite commits per photo (app DB + WorkManager progress DB), each an fsync-ish WAL append + SQLCipher page encryption. At thousands of photos, batching to e.g. 100 rows/transaction removes ~99% of commits.
- Pattern: DB writer coroutine does `for (batch in resultsChannel.chunked(...))` style accumulation — take up to 100 items or flush after 500 ms — then `db.withTransaction { dao.insertAll(batch); dao.upsertCursor(lastId) }`. Store embeddings as BLOBs in the same transaction.
- Progress: publish UI progress from an in-memory `StateFlow` (for the in-app progress bar) and call `setProgress` only every ~1 s / ~50 items (for when UI reattaches via `getWorkInfoByIdFlow`). Also update the foreground notification at similar low frequency (notification updates are rate-limited by the system; unverified in this session).
- SQLCipher: ensure the SQLCipher open helper is configured for WAL (`PRAGMA journal_mode=WAL`) and that the same `synchronous` setting is used; keep one open database connection for the writer (key derivation on open is deliberately expensive in SQLCipher — unverified here).
- Avoid concurrent writers from multiple coroutines; Room serializes but contention adds latency.

### Gaps
- No source found giving WorkManager `setProgress` frequency limits (none documented); cost is a DB write per call.
- SQLCipher-specific WAL/throughput numbers and `kdf_iter` open cost not verified in this session.

---

## Q4. Android background execution: expedited work, long-running workers + setForeground, FGS types (dataSync, mediaProcessing, shortService), timeouts, permissions, Android 16 quota changes, Play policy

### Takeaway
For a many-minute, user-initiated local indexing job on targetSdk 37: run a long-running WorkManager worker that calls `setForeground(ForegroundInfo(id, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING))` while the app is visible (start it from the UI), declare `mediaProcessing` on WorkManager's `SystemForegroundService` plus `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and implement timeout/stop handling. Expedited work is for short tasks (quota-limited in background). `dataSync` and `mediaProcessing` each get 6 h per 24 h in background (Android 15+ targets). On Android 16, WorkManager jobs that run alongside an FGS or continue after the app leaves the foreground count against job runtime quota, and Google suggests launching the FGS directly if long-running workers exhaust quota — a strong reason to consider a plain `Service` with `mediaProcessing` type for the long pass.

### Cited Findings
**Long-running workers**
- "WorkManager has built-in support for long running workers ... These Workers can run longer than 10 minutes"; WorkManager "manages and runs a foreground service on your behalf ... while also showing a configurable notification" — [WorkManager long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- "If your app targets Android 14 (API level 34) or higher you must specify a foreground service type for all long-running workers"; declare `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="..." tools:node="merge"/>` and pass the type in `ForegroundInfo(id, notification, type)` — [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- "Starting with Android 16, long running workers (which use foreground services) can exhaust your app's job quota. If this happens, you can try launching the foreground service directly instead of using WorkManager." — [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- `createCancelPendingIntent(id)` provides a notification Cancel action — [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- `setForeground()` "can throw runtime exceptions on Android 12, and might throw an exception if the launch was restricted" — wrap in try/catch (`IllegalStateException` / `ForegroundServiceStartNotAllowedException`) — [WorkManager define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

**Expedited work**
- Expedited work "best fits short tasks that start immediately and complete within a few minutes"; `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` or `DROP_WORK_REQUEST`; quota "based on the App Standby Buckets and limits the maximum execution time within a rolling time window ... more restrictive than the ones used for other types of background jobs"; "While your app is in the foreground, quotas won't limit the execution of expedited work"; may be deferred under high system load; `getForegroundInfo()` must be implemented (pre-Android 12 WorkManager may run it as an FGS) — [WorkManager define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- Regular (non-foreground) workers are subject to a 10-minute execution limit (implied by "These Workers can run longer than 10 minutes" for foreground workers) — [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

**Android 16 job quota changes (affect WorkManager, JobScheduler, DownloadManager)**
- "active standby buckets will start being enforced by a generous runtime quota" — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- "Jobs started while the app is visible to the user and continues after the app becomes invisible, will adhere to the job runtime quota." — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- "jobs that are executing concurrently with a foreground service will adhere to the job runtime quota" — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- Diagnose with `WorkInfo.getStopReason()`; `JobScheduler#getPendingJobReasonsHistory`; test overrides: `adb shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_TOP_STARTED_JOBS <pkg>`, `OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS`, `adb shell am set-standby-bucket <pkg> active|working_set|frequent|rare|restricted` — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- `JobInfo.Builder#setImportantWhileForeground` is ignored starting in Android 16 — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)

**FGS types and timeouts**
- `mediaProcessing`: "Service for performing time-consuming operations on media assets, like converting media to different formats ... this time limit would be 6 hours out of every 24"; permission `FOREGROUND_SERVICE_MEDIA_PROCESSING`; constant `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`; no runtime prerequisites — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- `mediaProcessing` "is available if your app targets Android 15 or higher" — [dataSync migration](https://developer.android.com/about/versions/15/changes/datasync-migration)
- `dataSync`: permission `FOREGROUND_SERVICE_DATA_SYNC`; described uses include "Local file processing" and import/export; apps targeting Android 15+ may not launch it from `BOOT_COMPLETED` — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Timeouts: for apps targeting Android 15+, "The system permits dataSync and mediaProcessing foreground services to run for a total of 6 hours in a 24-hour period, after which the system calls ... `Service.onTimeout(int, int)`"; limits tracked separately per type and shared by all of an app's FGS of that type; "if the user brings the app to the foreground, the timer resets"; if the service doesn't `stopSelf()` within a few seconds -> `RemoteServiceException: "A foreground service of type ... did not stop within its timeout"` (FGS types page says ANR) — [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout); [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- `shortService`: ~3 minutes, no type-specific permission (still needs `FOREGROUND_SERVICE`), not sticky, cannot start other FGS; exceeding it causes ANR — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- `specialUse` requires `FOREGROUND_SERVICE_SPECIAL_USE` plus a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation reviewed in Play Console — [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- User-initiated data transfer (UIDT) jobs: Android 14+, `RUN_USER_INITIATED_JOBS`, must be scheduled while app visible, notification mandatory, network constraint strongly recommended — designed for network transfers, so a poor fit for local indexing — [UIDT docs](https://developer.android.com/develop/background-work/background-tasks/uidt); Google suggests UIDT instead of jobs-with-FGS for user-initiated transfers on Android 16 — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)

**Background-start restrictions**
- Apps targeting Android 12+ "can't start foreground services while the app is running in the background, except for a few special cases" (`ForegroundServiceStartNotAllowedException`); exemptions include transition from a user-visible state, user interaction with notification/widget, boot/`MY_PACKAGE_REPLACED` broadcasts, exact alarms for user-requested actions, and the user having turned battery optimization off for the app; on Android 15 targets the `SYSTEM_ALERT_WINDOW` exemption requires a currently visible overlay window; Android 14+ restricts launching some types (e.g., dataSync, mediaProcessing) from `BOOT_COMPLETED` — [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

**Doze / battery optimization / Play policy**
- Doze suspends network, ignores wake locks, defers alarms and prevents JobScheduler (hence WorkManager) from running; exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (direct) or `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; check with `isIgnoringBatteryOptimizations`; Google Play prohibits requesting direct exemption unless core function is adversely affected — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- The fetched summary of that page states foreground services are not automatically exempt from Doze; treat as needing verification — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

### Inferences
- Recommended plan for this app:
  1. User taps "Index gallery" (app visible) -> enqueue a unique `OneTimeWorkRequest` (policy KEEP to avoid restarting an in-progress run; REPLACE only for explicit "re-index") and immediately call `setForeground(...)` at the top of `doWork()` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (gallery analysis is media processing; `dataSync` would also be defensible via "local file processing", and both types have separate 6 h budgets).
  2. Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` (and/or `FOREGROUND_SERVICE_DATA_SYNC`), `POST_NOTIFICATIONS` (Android 13+ runtime permission for the progress notification — not verified in this session), and `SystemForegroundService` merged with `foregroundServiceType="mediaProcessing"`.
  3. Don't use `setExpedited` for the full index — it targets "a few minutes"; could be used for a small incremental "index new photos" job.
  4. Because of the Android 16 quota rule for jobs running with an FGS / continuing after the app becomes invisible, implement checkpoint/resume and handle `WorkInfo.getStopReason()`; if quota stops are observed on OriginOS 6 (Android 16), switch the bulk pass to a directly started `Service` with `mediaProcessing` type (started from the UI), implementing `onTimeout(int,int)` to checkpoint and `stopSelf()`.
  5. A 6 h/24 h budget is ample for thousands of photos; the timer resets whenever the user returns to the app.
  6. Battery-not-low constraint: fine for background; consider dropping it (or relaxing) when the user explicitly starts indexing in the foreground, and consider `requiresCharging` for automatic background re-indexing.
- Play policy: for a sideloaded/hackathon build, Play Console FGS-type declarations and battery-exemption policy don't apply, so requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is technically fine; if later published, `mediaProcessing` usage and any battery-exemption request would need justification.

### Gaps
- Exact expedited quota numbers per standby bucket are not published in the fetched docs.
- The "generous runtime quota" for the active bucket on Android 16 is not quantified.
- Whether vivo/OriginOS 6 modifies these AOSP job/FGS quotas is unknown.

---

## Q5. CPU/accelerator throttling in background vs foreground (cgroups, cpusets, freezer) and vivo/OriginOS battery management

### Takeaway
AOSP places non-visible apps into lower-priority cpusets/scheduling groups (background tasks "packed" onto few/slower CPUs), and cached processes are frozen 10 s after becoming cached; a foreground service keeps the process out of the cached/frozen state but does not give it top-app CPU priority. So the same pipeline will be noticeably slower after the user leaves the app; the demo should show indexing with the app on screen. vivo is rated moderately aggressive by dontkillmyapp (3/5) and has no known developer-side workaround — users must whitelist the app manually.

### Cited Findings
- "ActivityManager assigns apps to different cpusets based on the relative importance of those apps (top, foreground, background), with more important apps getting more access to CPU cores"; Pixel uses schedtune (EAS) as an extra boost signal for top apps — [AOSP: Identify capacity-related jank](https://source.android.com/docs/core/tests/debug/jank_capacity)
- Android scheduling (2016 LWN summary of AOSP practice): top app gets a "spread" policy with a 10% boost; foreground tasks "spread" without boost; background tasks a "pack" policy concentrating them "on relatively few CPUs — often just one"; "cpusets are used to keep background tasks on the two slower CPUs" (on the example device) — [LWN: Scheduling for Android devices](https://lwn.net/Articles/706374/) (older; details vary by device/kernel)
- Task profiles (`task_profiles.json`) now abstract cgroup joins (e.g., "top-app" group under schedtune/cpuset) — [AOSP cgroup abstraction layer](https://source.android.com/docs/core/perf/cgroups)
- Cached apps freezer: Android 11+; uses cgroup v2 freezer; "App processes in the cached state are frozen 10 seconds after entering the cached state" (Android 14+); apps with a foreground service are not in the cached state — [AOSP cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)
- vivo (dontkillmyapp): rating 3/5; "System restrictions on Vivo phones have not been fully uncovered yet"; developer solutions: "No known solution on dev end yet." User steps: Autostart (Settings > More settings > Applications > Autostart); Android 13+: App > Battery > unrestricted; Settings > Battery > Background power consumption management; Settings > Battery > "High background power consumption" -> enable for the app; App Info > Battery > Battery optimization -> "Not optimized"; lock the app in Recents ("Apps locked in the taskbar are safe from getting terminated when they run in the background") — [dontkillmyapp.com/vivo](https://dontkillmyapp.com/vivo)
- OriginOS 6 is based on Android 16 (released Oct 2025) — [Wikipedia: Origin OS](https://en.wikipedia.org/wiki/Origin_OS) (secondary)

### Inferences
- On the 8 Elite Gen 5 (no little cores), background cpusets will likely restrict to a subset of the six 3.62 GHz performance cores and/or clamp utilization — still far faster than a little core, but decoder parallelism > available background cores just adds contention. Consider adapting decoder count to `isForeground` (e.g., 4 when app visible, 2 when not).
- NPU/DSP access is via the QNN/FastRPC stack, not CPU cgroups; the Hexagon should run similarly in background, but the CPU-side feeder (decode) becomes the bottleneck. OEM power managers may still throttle overall. (Unverified.)
- In-app onboarding: detect `PowerManager.isIgnoringBatteryOptimizations()`; if false, show a vivo-specific checklist (High background power consumption, Autostart, lock in Recents) with deep links to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` / app details settings. vivo-specific Settings intents are undocumented and vary by version.
- For the evaluation demo: keep the app in foreground (top-app cpuset + UI boost) and keep the screen on (`FLAG_KEEP_SCREEN_ON`) during the progress view.

### Gaps
- No official vivo/iQOO developer documentation found on OriginOS background management, FGS handling, or whitelisting APIs; dontkillmyapp content is community-sourced and does not specifically cover OriginOS 5/6.
- No source for the iQOO 15's actual cpuset/uclamp configuration; can be inspected on-device with `adb shell cat /dev/cpuset/background/cpus` and `/dev/cpuset/top-app/cpus` (suggested method, not verified).
- Whether background-state affects Hexagon NPU clocks (DCVS votes) was not found.

---

## Q6. Thermal management APIs and ADPF hints

### Takeaway
Use `PowerManager.getThermalHeadroom(forecastSeconds)` (poll no more than once every 10 s) plus `addThermalStatusListener` to scale decoder/OCR parallelism down before throttling kicks in. ADPF `PerformanceHintManager` sessions (API 31+) can describe CPU worker threads and a target duration, but they are designed around frame-like repeated work for foreground apps; their value for a background batch job is uncertain.

### Cited Findings
- `getThermalHeadroom(forecastSeconds)` returns ~0.0 (no throttling) to 1.0 (`THERMAL_STATUS_SEVERE`); forecasting lets you predict x seconds ahead with current workload — [ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal)
- "Don't call the GetThermalHeadroom() API too frequently. If you do so, the API returns NaN. You shouldn't call it more than once every 10 seconds."; avoid multi-threaded calls; initial NaN means unsupported — [ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal)
- `addThermalStatusListener` callbacks; statuses NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN; recommended adaptation includes reducing "Number of worker threads"; heuristic thresholds: >0.85 ~LIGHT, >0.95 ~MODERATE, >1.0 ~SEVERE — [ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal)
- `PerformanceHintManager.createHintSession(int[] threadIds, long initialTargetNanos)` (API 31), `Session.reportActualWorkDuration` (API 31), `setThreads` (API 31), `setPreferPowerEfficiency` (API 33 per the fetched reference; other sources place it in API 35), `getPreferredUpdateRateNanos` — [PerformanceHintManager reference](https://developer.android.com/reference/android/os/PerformanceHintManager); [NDK Performance Hint](https://developer.android.com/ndk/reference/group/a-performance-hint); [AOSP Performance Hint API](https://source.android.com/docs/core/perf/performance-hint-api)
- The hint API lets apps "send performance hints to Android for CPU clock speed and core type", and the OS decides how to use them based on SoC and thermal solution — [AOSP Performance Hint API](https://source.android.com/docs/core/perf/performance-hint-api) (via search snippet)

### Inferences
- Adaptive controller: every 10-15 s read headroom(10 s forecast); if > 0.85 reduce decoders by 1 (min 1) and pause the OCR branch; if > 0.95 also insert inter-batch delays; restore when < 0.7 for a few intervals. React immediately to status listener >= SEVERE by pausing and checkpointing.
- ADPF hint session for decoder threads: target duration = measured per-image decode time; report actual durations per image. Helps the governor ramp clocks for bursty work; `setPreferPowerEfficiency(true)` could be used when in background to trade speed for thermals. Effect on OEM kernels (OriginOS) unverified — measure.
- Sustained-performance mode (`Window.setSustainedPerformanceMode`) is another option for the foreground demo (not researched here).

### Gaps
- API level of `setPreferPowerEfficiency` conflicts between sources (33 vs 35); verify in the API reference.
- Android 16 CPU/GPU headroom APIs (`SystemHealthManager.getCpuHeadroom` etc.) were not retrieved in this session.
- No evidence found on whether ADPF sessions are honored for background (non-top) processes.

---

## Q7. Measuring per-stage latency (Perfetto, androidx.tracing) for the demo

### Takeaway
Instrument each stage with `androidx.tracing` `trace("decode") { ... }` sections (and async sections for coroutine-spanning work), mark the app `profileable`, and capture Perfetto system traces with CPU scheduling/frequency, GPU, and (if exposed) NPU/DSP counters to show per-core utilization and stage overlap.

### Cited Findings
- `trace("name") { ... }` from the Jetpack tracing library labels code sections that appear in captured system traces and "automatically ends the trace when the lambda completes"; NDK API available for native code; "When using Perfetto to capture system traces, make sure your application is configured as profileable"; Macrobenchmark captures custom trace points automatically — [Android custom trace events](https://developer.android.com/topic/performance/tracing/custom-events)
- A thermal/sched/frequency Perfetto recording workflow for sustained on-device inference is described by a third-party blog — [MVP Factory](https://mvpfactory.io/blog/thermal-throttling-and-sustained-on-device-llm-inference-on-android-cpu) (secondary, anecdotal)

### Inferences
- Use synchronous `trace()` only within non-suspending blocks (sections must begin/end on the same thread); for sections spanning suspension (e.g., waiting on a channel), use async trace sections with a cookie (`Trace.beginAsyncSection`/`traceAsync` in androidx.tracing-ktx) — API names from general knowledge, verify.
- Emit counters (`Trace.setCounter("queue_decoded", size)`) for channel depths so the Perfetto UI shows whether the NPU is starved (decoded queue near 0) or decode is blocked (queue full).
- Perfetto config: `linux.ftrace` with `sched/sched_switch`, `power/cpu_frequency`, `power/cpu_idle`, `thermal`, GPU freq; `linux.process_stats`; atrace categories + app package. Qualcomm-specific NPU utilization may require Snapdragon Profiler/QNN profiling rather than Perfetto (unverified).
- For a demo UI, also show live per-stage throughput (items/s) and current thermal headroom in-app.

### Gaps
- Could not verify whether the iQOO 15 kernel exposes Hexagon/NPU utilization counters to Perfetto; Qualcomm's Snapdragon Profiler or QNN's own profiling output is the likely alternative.
