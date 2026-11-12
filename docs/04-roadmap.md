# 04 — Implementation roadmap

This is deliverable 11. The Project doc's status board is the live view; this file holds the stable plan. Each milestone ends with a device test on the Honor X9c. APKs are shared in the Claude chat.

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1 Feasibility prototype** (done: GO on 2026-11-01) | Access probes: SAF, all-files, MediaStore, share-in. Format sniffing, SHA-256 dedup, thumbnails, grid, animated preview, test send, exportable JSON report | The device report shows which sources work and the unique count, and animated decoding works. GO/PARTIAL/NO-GO recorded in the Project doc |
| **M2 Indexing** (done, 0.2.1) | Room schema (02-architecture), WorkManager incremental ingest (uri+size+mtime skip), statuses, missing/removed detection (only for sources readable in that scan), pure-Kotlin WebP keyframes + Lottie rendering, failure retries (3 attempts, then "Retry failed") | A rescan after adding 5 stickers processes only those 5. All animated stickers produce keyframes |
| **M3 AI annotation** (built, 0.3.0) | Two passes: SigLIP2 image embeddings for all ~5,000, then Gemma 3n annotations newest first | See 03-ai-model-evaluation |
| M3 detail | `Annotator` (Gemma 3n E2B via LiteRT-LM), prompt plus JSON schema, keyframe vs contact-sheet comparison, `Embedder`, in-app benchmark, model download/import | Measured per-sticker time and RAM. On a 50-sticker hand-checked sample, ≥ 80% of annotations are judged useful by the user |
| **M4 Semantic search** (built, 0.3.0) | Query embedding, FTS4, tag match, RRF fusion, query expansion (Hinglish lexicon), results grid, recent searches, recently used, annotation editing | ≥ 70% of a 40-query user evaluation set has a correct sticker in the top 6. Under 1 s latency |
| **M5 WhatsApp integration** (built, 0.3.0) | Dynamic "Results" pack through the WhatsApp sticker-pack ContentProvider (pack limits: 3–30 stickers; 512×512 WebP; ≤100 KB static, ≤500 KB animated; 96×96 tray icon). Device test: a plain share intent arrives as a photo, so it stays a fallback only | The selected sticker arrives as a real, still-animated sticker with the fewest taps |
| **M6 Automatic sync** (built, 0.3.0) | Periodic WorkManager scan (charging + idle), chunked annotation queue, retries with backoff, `mediaProcessing` FGS for bulk, MagicOS battery guidance, settings (storage usage, delete all, rescan) | New stickers are annotated within a day without opening the app. Process death mid-run resumes cleanly |

## Android version support

Done in 1.0.0: minSdk 28 (Android 9+), arm64.
- Newer-only APIs are version-checked in one place (`compat/Compat.kt`): WebP encode formats, all-files access vs. the storage permission (Android 9–10, with legacy storage on 10), share-intent extras, notification permission (13+), process-exit reasons (11+).
- Long runs are foreground "media processing" on 15+, "data sync" on 10–14, untyped on 9.
- WhatsApp's folder is found under `Android/media/…` (11+) or at the storage root (9–10); WhatsApp Business folders likewise.
- Sending targets WhatsApp or WhatsApp Business (asked once when both are installed); pack bookkeeping is kept per app.
- Phones under ~6 GB of memory don't describe on-device by default.
- Lint with minSdk 28 reports no NewApi/InlinedApi issues.

## Deferred

These items are tracked in the Project doc's deferred checklist:
- sticker packs (first-party and third-party);
- dual-app WhatsApp;
- Lottie stickers;
- Accessibility-based favourites flagging.
