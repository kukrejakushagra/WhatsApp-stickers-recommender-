# 04 — Implementation roadmap

This is deliverable 11. The Project doc's status board is the live view; this file holds the stable plan. Each milestone ends with a device test on the Honor X9c. APKs are shared in the Claude chat.

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1 Feasibility prototype** (this build) | Access probes: SAF, all-files, MediaStore, share-in. Format sniffing, SHA-256 dedup, thumbnails, grid, animated preview, test send, exportable JSON report | The device report shows which sources work and the unique count, and animated decoding works. GO/PARTIAL/NO-GO recorded in the Project doc |
| **M2 Indexing** | Room schema (02-architecture), incremental ingest (path+size+mtime skip), statuses, missing/removed detection, libwebp keyframe extraction with change scores, failure retries | A rescan after adding 5 stickers processes only those 5. All animated stickers produce keyframes |
| **M3 AI annotation** | `Annotator` (Gemma 3n E2B via LiteRT-LM), prompt plus JSON schema, keyframe vs contact-sheet comparison, `Embedder`, in-app benchmark, model download/import | Measured per-sticker time and RAM. On a 50-sticker hand-checked sample, ≥ 80% of annotations are judged useful by the user |
| **M4 Semantic search** | Query embedding, FTS4, tag match, RRF fusion, query expansion (Hinglish lexicon), results grid, recent searches, recently used, annotation editing | ≥ 70% of a 40-query user evaluation set has a correct sticker in the top 6. Under 1 s latency |
| **M5 WhatsApp integration** | Compare (a) share intent and (b) a dynamic "Results" pack through the WhatsApp sticker-pack ContentProvider (pack limits: 3–30 stickers; 512×512 WebP; ≤100 KB static, ≤500 KB animated; 96×96 tray icon) | The user picks the flow with the fewest taps that arrives as a real, still-animated sticker |
| **M6 Automatic sync** | Periodic WorkManager scan (charging + idle), chunked annotation queue, retries with backoff, `mediaProcessing` FGS for bulk, MagicOS battery guidance, settings (storage usage, delete all, rescan) | New stickers are annotated within a day without opening the app. Process death mid-run resumes cleanly |

## Android version support

minSdk is 36 for now. The checklist for lowering it lives in the Project doc under "Android version support checklist".

## Deferred

These items are tracked in the Project doc's deferred checklist:
- sticker packs (first-party and third-party);
- WhatsApp Business as a first-class source;
- dual-app WhatsApp;
- Lottie stickers;
- Accessibility-based favourites flagging.
