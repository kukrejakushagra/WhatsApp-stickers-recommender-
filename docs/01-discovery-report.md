# 01 — Discovery and feasibility report

Status: **provisional**. Statements marked *(verify on device)* are hypotheses that the Milestone 1 diagnostic app is built to confirm or refute on the user's Honor X9c (Android 16, MagicOS). Nothing here claims that all favourites can be imported until the device report is in.

Covers deliverables 1–6 and 12: problem understanding, technical discovery, WhatsApp storage analysis, favourites-extraction feasibility, alternatives comparison, risk register and feasibility decision.

---

## 1. Problem understanding

- The user has about 1,000 WhatsApp favourite stickers: static WebP, animated WebP, and possibly GIF-like assets. They come from chats and, less often, from packs.
- Finding the right one means scrolling WhatsApp's Favourites tab by eye.
- The goal is a retrieval layer over the user's existing stickers, not a new sticker collection:
  1. Discover the stickers on the device.
  2. Understand each one automatically: visual content, emotion, conversational intent, and the motion of animated stickers.
  3. Answer natural-language queries in English, Hindi or Hinglish, offline.
  4. Hand the chosen sticker back to WhatsApp with minimal effort. The user always taps Send.
- Constraints:
  - Android 16 only for now.
  - Personal sideload build.
  - Fully on-device AI.
  - Never read WhatsApp's private data.
  - Never send anything automatically.

## 2. Technical discovery: what Android allows

These are the platform rules that decide what is possible. Sources: Android developer docs on scoped storage, SAF and all-files access.

| Rule (Android 11+) | Consequence |
|---|---|
| `Android/data/<pkg>` and `Android/obb/<pkg>` of other apps are inaccessible to SAF (`OPEN_DOCUMENT_TREE` refuses them) and to `MANAGE_EXTERNAL_STORAGE` | WhatsApp's app-specific external storage is off limits |
| `/data/data/com.whatsapp` (internal app storage) is sandboxed | The favourites database and first-party pack stickers are unreachable without root, which is out of scope |
| SAF `OPEN_DOCUMENT_TREE` refuses the storage root, `Download/`, `Android/data`, `Android/obb`, but **not** `Android/media/<pkg>` | A persistent, user-granted tree on `Android/media/com.whatsapp/...` should work *(verify on device)* |
| `MANAGE_EXTERNAL_STORAGE` gives `java.io.File` access to all shared storage including `Android/media`, but not `Android/data` | Works for sideload; Google Play only allows it for file managers, backup apps and similar |
| MediaStore does not index folders containing `.nomedia` | WhatsApp likely hides the sticker folder from the gallery, so MediaStore is expected to be empty for stickers *(verify on device)* |
| `ImageDecoder` / `AnimatedImageDrawable` (API 28+) decode animated WebP, GIF and APNG | Previews and animation need no extra library; frame-level access needs another decoder (see 02-architecture) |
| Background work is deferred by Doze and App Standby; OEM skins (MagicOS) kill apps more aggressively | Indexing must be resumable, constraint-based WorkManager work, with an expedited or foreground mode for the overnight bulk run |

## 3. WhatsApp sticker storage analysis

Based on publicly observable behaviour of WhatsApp for Android (not on any private API). Every row is *(verify on device)*.

| What | Expected location | Reachable? |
|---|---|---|
| Stickers received in chats (downloaded) | `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/` as `.webp` files named by content hash | Yes: via SAF tree (Option B) or all-files access (Option A) |
| Stickers you sent | Same folder; some versions use a `Sent/` subfolder | Yes |
| Favourites list (which stickers are starred, and their order) | WhatsApp private database under `/data/data/com.whatsapp` | **No.** Not attempted, by policy |
| Favourite sticker bytes | Possibly a private copy in `/data/data/com.whatsapp/files/Stickers`. The received copy in `Android/media` may also exist, unless removed by WhatsApp's "Manage storage" cleanup | Only the `Android/media` copy, when it still exists |
| First-party pack stickers (downloaded from WhatsApp's sticker store) | Private storage | No |
| Third-party pack stickers | Owned by the pack app, exposed through its ContentProvider | Possible per pack app (deferred) |
| Lottie/avatar animated stickers | Possibly `.was` (zip/Lottie) or private storage | Unknown; M1 sniffs for zip/Lottie |
| WhatsApp Business | `Android/media/com.whatsapp.w4b/...` | M1 probes it |
| Legacy pre-2021 location | `/sdcard/WhatsApp/Media/WhatsApp Stickers` | M1 probes it |

**Animated stickers.** WhatsApp's own animated stickers are animated WebP (VP8X chunk with the animation flag, ANIM + ANMF chunks), not GIF. Files keep the `.webp` extension either way, so the app sniffs headers instead of trusting extensions.

**Distinguishing favourites.** No public signal marks a file as a favourite. Per the user's decision, we **index every accessible sticker** and let ranking surface the right one. The UI can later offer an in-app "favourite" star.

## 4. Feasibility of favourite-sticker extraction

- Every favourite still present as a received file in `Android/media` can be imported automatically and incrementally.
- Favourites that exist only in WhatsApp's private storage cannot be imported through public APIs. That covers pack stickers and stickers whose received copy was cleaned up.
- The M1 report measures the gap: the number of unique stickers found against the user's ~1,000 favourites.
  - The found set is a superset (all received stickers), so a high count is necessary but not sufficient.
  - The user's spot check in the grid (are the stickers I use most present?) is the real test.

## 5. Alternatives comparison (Options A–F)

| Option | Works? | Permission | Reliability | Notes |
|---|---|---|---|---|
| **A. Direct filesystem scan** | Expected yes, for `Android/media` | `MANAGE_EXTERNAL_STORAGE` | High | Also discovers unexpected sticker folders through a directory inventory. Not Play-compliant; acceptable for sideload |
| **B. SAF tree** | Expected yes | None beyond a one-time folder pick | High; persisted URI survives reboots, revoked if the user clears it | **Primary path.** Play-compliant and narrow |
| **C. MediaStore** | Expected no for stickers | `READ_MEDIA_IMAGES` | n/a | Measured only, to rule it out |
| **D. Share from WhatsApp** | Unknown | None | Manual, one sticker at a time | Tests whether WhatsApp offers Share for stickers. Useful for adding a missing favourite by hand |
| **E. One-time bulk import** | Same mechanism as B | None | High | Covered by B: pick the folder once |
| **F. Accessibility on the sticker picker** | Possible but unreliable | Accessibility service (+ `takeScreenshot`, API 30+) | Low; breaks with WhatsApp UI updates, tiles likely lack identifiers | Screenshots give static, low-resolution tiles. Only useful to *flag* which indexed files are favourites by visual matching. Deferred fallback; Play policy forbids non-accessibility use |
| Root / private DB | Technically possible with root | Root | — | **Rejected:** violates the sandbox and the project's rules |

## 6. Risk register (failure scenarios)

Classification key:
- **IMP**: technically impossible
- **UNR**: possible but unreliable
- **PERM**: possible with additional permissions
- **WA**: possible through a workaround
- **API**: supported through public APIs

| # | Scenario | Class | Mitigation |
|---|---|---|---|
| 1 | Favourites cannot be accessed directly | IMP (the list itself) / API (received files) | Index all received stickers; share-in for missing ones; optional in-app star |
| 2 | Sticker files duplicated | API | SHA-256 dedup; one sticker row, many file rows |
| 3 | Files missing (cleanup by WhatsApp) | WA | Mark the sticker `missing`, keep its annotations and thumbnail, revive if the file reappears |
| 4 | Animated WebP decoding fails | WA | Header sniffing still classifies it; thumbnail is skipped; status `failed` with the error; retry with a fallback decoder (libwebp) |
| 5 | AI annotation incorrect | WA | User corrections table that always wins; per-field confidence; "needs review" status below a threshold |
| 6 | Visually similar stickers, different meanings | UNR | Annotate the in-sticker text (OCR through the VLM) and motion; corrections; hybrid ranking with keywords |
| 7 | Same sticker in multiple packs | API | Dedup by hash; occurrences tracked per path |
| 8 | WhatsApp changes its UI | UNR (for Accessibility) / API (for share and sticker-pack paths) | Don't depend on UI automation; use share intents or the documented sticker-pack provider |
| 9 | Accessibility nodes unavailable | UNR | Not the primary path |
| 10 | Android kills background processing | WA | WorkManager with charging + idle constraints, chunked and resumable jobs, `mediaProcessing` foreground service for bulk runs, guidance on MagicOS battery settings |
| 11 | Storage permission revoked | API | Check persisted permissions each scan; prompt to re-pick; never delete the index on revoke |
| 12 | Sticker directory changes | WA | Directory inventory in the scan report; configurable roots; SAF re-pick |
| 13 | New favourites added | API | Incremental scan: unchanged (path, size, mtime) is skipped; a new hash is queued |
| 14 | Favourites removed | API | Missing file marks the sticker missing, hidden from results after N days |
| 15 | Insufficient RAM | WA | Model tiers (E4B → E2B → small VLM); one inference at a time; `onTrimMemory` aborts and retries |
| 16 | Local inference too slow | WA | Overnight chunks while charging; prioritise recently modified stickers; smaller model tier |
| 17 | Poor semantic search results | WA | Hybrid ranking (embedding + FTS + tag match), query expansion, evaluation set of user queries, corrections feed back into the text index |

## 12. Feasibility decision

**Conditional GO, pending the Milestone 1 device report.**

- **GO** if the SAF or file scan finds a unique sticker set covering the user's frequently used favourites (target: most of ~1,000), and animated WebP decodes with `animatedDecodeFailures ≈ 0`.
- **PARTIAL** if a large share is missing. The likely causes are pack stickers or media cleanup. The fallback (share-in, Accessibility-assisted flagging, or third-party pack providers) is chosen with the user before M2.
- **NO-GO** only if `Android/media/com.whatsapp` is unreachable by both A and B. The fallback is then manual import of stickers shared from WhatsApp.
