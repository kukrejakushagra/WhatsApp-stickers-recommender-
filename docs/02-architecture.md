# 02 — Architecture, technology decisions, database schema

Covers deliverables 7–9: the Android architecture proposal, the technology decision matrix and the database schema. M1 implements only the discovery layer; everything else is the target design for M2–M6.

## 7. Architecture

```
            ┌──────────────── Sources ────────────────┐
            │ SafTreeSource  FileSystemSource          │  M1
            │ MediaStoreSource  SharedInSource          │
            └───────────────┬──────────────────────────┘
                            │ DiscoveredFile(uri, path, size, mtime)
                     ┌──────▼──────┐
                     │  Ingest      │ sniff format → SHA-256 → dedup → thumbnail     M1/M2
                     │  (WorkManager)│ → keyframes (animated) → Room rows
                     └──────┬──────┘
                            │ status: discovered → pending
                     ┌──────▼──────┐
                     │  Annotate    │ on-device VLM over thumbnail/keyframes → JSON   M3
                     └──────┬──────┘ status: processing → indexed | needs_review | failed
                     ┌──────▼──────┐
                     │  Embed       │ text embedding of (description + intents + tags  M3
                     │              │ + phrases + user corrections); optional image emb.
                     └──────┬──────┘
          query ──► ┌──────▼──────┐
                    │  Search      │ query embedding · cosine (brute force) + FTS4/BM25    M4
                    │              │ + tag match → reciprocal rank fusion → grid
                    └──────┬──────┘
                    ┌──────▼──────┐
                    │  Send        │ share intent to WhatsApp | dynamic "Results"         M5
                    │              │ sticker pack (WhatsApp sticker-pack ContentProvider)
                    └─────────────┘
```

Single `:app` module for now, organised by package. Split it into Gradle modules only when build times call for it.

| Package | Responsibility | Testability |
|---|---|---|
| `model` | Plain data classes | — |
| `discovery` | Sources plus `PathRules` | `PathRules` is pure and unit-tested |
| `media` | `FormatSniffer`, `Hasher` (pure), `Thumbnailer` (platform) | Sniffer and Hasher are unit-tested with synthetic files |
| `diagnostics` | `ScanEngine`, `ScanAggregator` (pure), `ReportBuilder`, `EventLog` | Aggregator is unit-tested |
| `share` | Send and share-in experiments | Manual device test |
| `ui` | Compose screens plus ViewModel | — |
| *(M2+)* `data` | Room DB, DAOs, repositories | Room in-memory tests |
| *(M3+)* `ai` | `Annotator` and `Embedder` interfaces with LiteRT/ONNX implementations | Fake implementations in tests |
| *(M4+)* `search` | Ranker (pure), query expansion | Unit-tested against fixture vectors |

No DI framework yet: there is one ViewModel and constructor injection. Hilt is worth adding only if the graph grows.

## 8. Technology decision matrix

| Concern | Choice | Alternatives considered | Why |
|---|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 | Views | Requested; least code |
| Min SDK | 36 (Android 16) for now | 30 | User's decision; the checklist for lowering it is in the Project doc |
| File access | SAF tree (primary), all-files (diagnostic/optional) | MediaStore, Accessibility | SAF is narrow, persistent and Play-safe |
| Format detection | Own header sniffer | Trust extensions | WhatsApp uses `.webp` for everything |
| Previews/animation | `ImageDecoder` + `AnimatedImageDrawable` | Glide, Coil, Fresco | Platform-native, no dependency |
| Frame extraction (M2) | **libwebp `WebPAnimDecoder` via NDK** for WebP; small GIF decoder for GIF | Fresco animated (large); render `AnimatedImageDrawable` on a timer (imprecise) | Exact frame access and timestamps; about 300 KB native |
| Database | Room (SQLite) | Realm, ObjectBox | Requested; FTS4 support built in |
| Keyword index | Room `@Fts4` | FTS5 | FTS5 isn't guaranteed in the framework SQLite; FTS4 is enough for about 1k rows |
| Vector search | Brute-force cosine over float arrays in memory | sqlite-vec, FAISS, HNSW | 1,000 × 768 dims is about 3 MB; a full scan takes under 5 ms |
| Background | WorkManager (periodic scan, chunked annotation), `mediaProcessing` FGS for bulk | AlarmManager, plain services | Survives process death; respects Doze |
| VLM runtime | LiteRT-LM (Gemma 3n), GPU delegate | MediaPipe LLM Inference, MNN, llama.cpp | Google-supported on Android; multimodal; see 03 |
| Embedding runtime | LiteRT (EmbeddingGemma) or ONNX Runtime Mobile (e5) | — | Decided by the M3 benchmark |
| Sending | Share intent (`ACTION_SEND` with `setPackage("com.whatsapp")`) **and/or** dynamic sticker pack via the WhatsApp sticker-pack ContentProvider contract | Accessibility automation, deep links | Decided by the M5 device test |

## 9. Database schema (Room, M2)

```sql
-- One row per unique sticker (content hash).
CREATE TABLE stickers (
  id               INTEGER PRIMARY KEY,
  sha256           TEXT NOT NULL UNIQUE,
  format           TEXT NOT NULL,          -- WEBP_STATIC | WEBP_ANIMATED | GIF | APNG | ...
  is_animated      INTEGER NOT NULL,
  frame_count      INTEGER, width INTEGER, height INTEGER, duration_ms INTEGER,
  size_bytes       INTEGER NOT NULL,
  thumbnail_path   TEXT,
  status           TEXT NOT NULL,          -- DISCOVERED | PENDING | PROCESSING | INDEXED | FAILED | NEEDS_REVIEW | MISSING
  status_error     TEXT,
  attempts         INTEGER NOT NULL DEFAULT 0,
  -- AI annotation (overwritten by re-annotation; user corrections live elsewhere)
  description      TEXT,
  emotional_intent TEXT,                   -- JSON array
  conversational_intent TEXT,              -- JSON array
  search_phrases   TEXT,                   -- JSON array (EN/HI/Hinglish)
  ocr_text         TEXT,
  confidence       REAL,
  annotator_model  TEXT, annotator_version TEXT, prompt_version INTEGER,
  embedding        BLOB,                   -- float32 little-endian, L2-normalised
  embedding_model  TEXT,
  user_favourite   INTEGER NOT NULL DEFAULT 0,
  last_used_at     INTEGER,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL
);

-- Every place a sticker was seen (many paths/sources -> one sticker).
CREATE TABLE sticker_files (
  id INTEGER PRIMARY KEY,
  sticker_id INTEGER NOT NULL REFERENCES stickers(id) ON DELETE CASCADE,
  source TEXT NOT NULL,                    -- SAF_TREE | FILE_SYSTEM | SHARE_IN | ...
  uri TEXT NOT NULL UNIQUE,
  display_path TEXT, size_bytes INTEGER, last_modified INTEGER,
  first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, missing_since INTEGER
);

CREATE TABLE sticker_tags (
  id INTEGER PRIMARY KEY,
  sticker_id INTEGER NOT NULL REFERENCES stickers(id) ON DELETE CASCADE,
  tag TEXT NOT NULL,
  source TEXT NOT NULL,                    -- AI | USER
  confidence REAL,
  UNIQUE(sticker_id, tag, source)
);

CREATE TABLE sticker_frames (
  id INTEGER PRIMARY KEY,
  sticker_id INTEGER NOT NULL REFERENCES stickers(id) ON DELETE CASCADE,
  frame_index INTEGER NOT NULL, timestamp_ms INTEGER NOT NULL,
  frame_path TEXT NOT NULL, change_score REAL
);

-- User corrections always win and are never touched by re-indexing.
CREATE TABLE user_corrections (
  id INTEGER PRIMARY KEY,
  sticker_id INTEGER NOT NULL UNIQUE REFERENCES stickers(id) ON DELETE CASCADE,
  corrected_description TEXT,
  corrected_emotions TEXT, corrected_intents TEXT, -- JSON arrays, NULL = no override
  corrected_tags TEXT,                     -- JSON array; also mirrored as sticker_tags(source=USER)
  hidden INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

CREATE TABLE scan_runs (
  id INTEGER PRIMARY KEY, started_at INTEGER, finished_at INTEGER, trigger TEXT,
  files_seen INTEGER, new_stickers INTEGER, missing INTEGER, failed INTEGER, error TEXT
);

-- Keyword index over the merged (correction-overridden) text.
CREATE VIRTUAL TABLE sticker_fts USING fts4(description, tags, phrases, ocr_text, content="");
```

How the schema meets the requirements:

- **Incremental indexing.** A `sticker_files` row whose `(uri, size, last_modified)` is unchanged is skipped. A changed one is re-hashed, and a new hash creates a new sticker.
- **Corrections are preserved.** The effective annotation is `COALESCE(correction, ai)`, computed in the repository layer. The FTS row and the embedding are rebuilt from the effective text whenever either side changes.
- **Removals.** A file that isn't seen in a full scan gets `missing_since`. A sticker with no live files is `MISSING`, but it keeps its annotations in case the file comes back.
