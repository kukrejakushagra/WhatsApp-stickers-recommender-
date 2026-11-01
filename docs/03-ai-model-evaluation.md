# 03 — AI model evaluation (preliminary)

Status: **preliminary estimates.** The user chose fully on-device inference, a model download of up to ~4 GB, and overnight indexing while charging. Every number below is an order-of-magnitude estimate for a Snapdragon 6 Gen 1 with an Adreno 710 GPU (Honor X9c). The numbers are replaced by measurements in M3, which adds an in-app benchmark screen.

## Update after the device run: about 5,000 stickers, two-pass indexing

The device holds about 5,027 unique stickers, not 1,000. At 15–30 s per VLM annotation, that is 20–40+ hours. The user chose two passes:

1. **Pass 1 (first night, about 25–40 min):** an image-text embedding for every sticker (multilingual SigLIP2, about 0.3–0.5 s each on the X9c). Visual natural-language search works on everything right away, using keyframes for animated stickers.
2. **Pass 2 (following nights, newest first):** Gemma 3n annotations (description, intents, tags, Hindi/Hinglish phrases). These replace or augment the pass-1 ranking as they arrive.

## As built (0.3.0)

| Role | Model | Runtime | File |
|---|---|---|---|
| Understanding | Gemma 3n E2B it int4 | MediaPipe LLM Inference 0.10.29 (GPU, CPU fallback) | `gemma-3n-E2B-it-int4.task`, ~3.1 GB, gated |
| Meaning vectors | multilingual-e5-small int8, 384-d, mean-pooled | ONNX Runtime 1.29 + XNNPACK | ~118 MB |
| Looks | SigLIP2 base/16-224 int8, image and text towers, 768-d | ONNX Runtime | ~95 MB + ~283 MB |

- **Tokenizers** are implemented in Kotlin: SentencePiece Unigram for XLM-R/e5 and BPE with byte fallback for Gemma/SigLIP2. They match the reference library ID-for-ID on English, Hindi, Hinglish, emoji and NFKC cases (unit tests).
- **Checks run off-device in Python** with the same int8 files:
  - SigLIP2 ranks the right caption first for synthetic images, in English and Hindi.
  - e5 ranks the right annotation first for most of the user's example queries.
  - Embeddings don't handle negation, so the parser removes negated words and the ranker penalises matching labels.
- **Animated stickers** are shown to Gemma as a 2×2 contact sheet of keyframes (one image pass). The model is told the frames are in time order and asked to describe the motion.

## What has to be produced per sticker

- A description.
- Emotional intents.
- Conversational intents.
- 5–10 tags.
- Search phrases in English, Hindi and Hinglish.
- Text visible in the sticker (OCR, often Hindi or Hinglish).
- For animated stickers, the motion ("repeatedly bangs head against wall").
- A confidence score.

Only a **vision-language model (VLM)** can produce the intent and phrase fields. Classifiers and embedding models alone cannot.

## Approach comparison

| Approach | Quality for intent/sarcasm | Hindi/Hinglish | On-device | Verdict |
|---|---|---|---|---|
| Image classifier (MobileNet/EfficientNet labels) | Poor: objects only, no intent | None | Easy | Rejected |
| Image-text embedding only (CLIP/SigLIP zero-shot) | Fair for visual concepts, weak for sarcasm or "I told you so" | Multilingual SigLIP2 helps; no OCR reasoning | Yes, ~400 MB | **Secondary signal** in hybrid ranking |
| Local VLM annotation + text embeddings | Good; reads in-sticker text, reasons about intent | Depends on the model | Yes, 2–4 GB | **Primary** |
| Cloud VLM | Best | Best | No | **Excluded** by the user's decision |
| Hybrid: VLM annotations → text embedding + FTS + optional image embedding | Best available offline | Phrases generated in all three languages | Yes | **Chosen design** |

## VLM candidates

| Model | Runtime | Download (int4) | Peak RAM (est.) | Per-sticker time on X9c (est.) | Notes |
|---|---|---|---|---|---|
| **Gemma 3n E2B-it** | LiteRT-LM, GPU/CPU | ~3 GB | ~2–3 GB | 15–30 s | Google's on-device multimodal model with a MobileNet-V5 vision encoder and 140-language text. **Primary candidate** |
| Gemma 3n E4B-it | LiteRT-LM | ~4.4 GB | ~3–4 GB | 30–60 s | Better quality, but slightly over the size budget and slower; benchmark only |
| Qwen2.5-VL-3B-Instruct | MNN or llama.cpp | ~2 GB | ~3 GB | 20–45 s | Strong OCR; challenger for Hindi text in stickers |
| SmolVLM2-500M/2.2B | ONNX Runtime / llama.cpp | 0.5–1.5 GB | 1–2 GB | 5–20 s | Video-aware, but weak at Hindi and sarcasm; low-RAM fallback tier |

**Estimated time for 1,000 stickers on the X9c: about 4–10 hours** with Gemma 3n E2B.

- That is more than the 1–3 hours given in the planning questions, which assumed a flagship SoC.
- Two to three overnight sessions may be needed.
- The pipeline is resumable and processes recent stickers first, so search works on a growing subset from the first night.

Ways to cut the time:
- A compact JSON output schema, about 120 output tokens.
- One combined prompt per sticker.
- A contact sheet of keyframes instead of several separate images.
- Skipping stickers whose hash was already annotated.

**Battery.** Sustained GPU or CPU inference draws roughly 3–5 W. It runs only while charging and idle (WorkManager constraints), so there is no user-visible battery drain.

## Animated stickers: frames vs a motion model

Plan: send sampled keyframes, not every frame. Evaluate two inputs in M3:

1. **Keyframe set.** First meaningful frame, last frame, and 1–4 middle frames where the pixel-difference score peaks. Sent as multiple images (Gemma 3n accepts several images).
2. **Contact sheet.** The same keyframes tiled into one 2×2 or 3×2 image, with the prompt saying "frames in time order, left to right, top to bottom". One vision-encoder pass, so cheaper.

The sticker metadata (frame count, duration, loop) goes into the prompt as text.

A true video or motion model (for example SmolVLM2 in video mode) is only considered if keyframes miss repetitive motion, such as head-banging versus a single bump. The evaluation uses a hand-labelled set of about 30 animated stickers taken from the user's own collection.

## Text embedding candidates (search)

| Model | Size | Dims | Languages | Query latency on X9c (est.) | Notes |
|---|---|---|---|---|---|
| **EmbeddingGemma-300M** | ~200 MB (int8) | 768, Matryoshka-truncatable to 256 | 100+ | 30–80 ms | **Primary**; on-device first, LiteRT available |
| multilingual-e5-small | ~120 MB (int8 ONNX) | 384 | ~100 | 15–40 ms | Fallback, smaller |
| Multilingual SigLIP2 (text and image towers) | ~400 MB | 768 | Multilingual | 50–100 ms | Optional image-side signal |

**Hinglish risk.** Romanised Hindi ("kya bakwas", "bhai sahab") is weakly represented in all of these models. Mitigations:

1. The VLM writes Hinglish and Devanagari search phrases into each annotation, so lexical (FTS) matching works.
2. A small transliteration and synonym lexicon expands queries.
3. M4 builds an evaluation set of about 40 real queries from the user, each with its expected stickers.

## Search design

1. Embed the query, then score cosine similarity against every sticker's L2-normalised vector. For 1,000 × 768 floats this brute-force scan takes under 5 ms, so no vector database is needed.
2. Run in parallel:
   - an FTS4 match over description, tags, phrases and OCR text;
   - an exact tag and intent match after query expansion.
3. Merge the lists with **reciprocal rank fusion**, add a small boost for user favourites and recent use, and apply user corrections first.
4. Latency budget: under 300 ms end-to-end, well inside the 1 s target.

## Storage budget

| Item | Size |
|---|---|
| VLM (Gemma 3n E2B int4) | ~3 GB, deletable after indexing; it is re-downloaded only when new stickers need annotating |
| Embedding model | ~200 MB, must stay for queries |
| Thumbnails (1,000 × ~15 KB) | ~15 MB |
| Keyframes (~400 animated × 4 × ~20 KB) | ~32 MB |
| Database including embeddings | ~10 MB |

## Model download

- **Not in the M1 build:** it has no `INTERNET` permission.
- **Planned for M3:** the model is fetched from Hugging Face or Kaggle (the Gemma licence requires accepting terms), or the user sideloads the file through the SAF picker.
- Model weights are the only network traffic. Sticker data never leaves the device.
