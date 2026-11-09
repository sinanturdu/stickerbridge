# Roadmap

Rule: critical technical assumptions are proven with small experiments before product code is written.

## Phase 0 — Research & experiments (current)
- [x] Research: WhatsApp data access, Instagram insertion paths (see RESEARCH.md)
- [x] Repository skeleton
- [x] **E1** IME MIME probe — which image types Instagram fields accept from a keyboard (done 2026-11-09, see experiments/E1-ime-probe.md)
- [ ] **E0** WhatsApp Android sticker folder contents ← *next*
- [ ] **E2** Send/render matrix (transparency, animation, recipient view)
- [ ] **E3** Share to Stories with a transparent PNG
- [ ] **E4** iOS copy/paste tests (manual)
- [ ] **E5** Linked-device favorites (test account only)
- [ ] **E6** WhatsApp Web IndexedDB inspection

## Phase 1 — Android v0.1
- Keyboard with a customizable sticker key (icon & color chosen by the user)
- Sticker grid with groups (favorites / packs / all), ↻ refresh button
- Source: WhatsApp sticker folder via SAF, scanned when the keyboard opens + periodically
- Manual add via share menu
- Insertion into Instagram via `commitContent` (decided by E1):
  - Animated stickers converted to GIF (1-bit alpha, possible edge halo)
  - Static stickers sent as WEBP, with PNG conversion as fallback
  - Sticker buttons hidden when the field advertises no image types (e.g. comments)
  - Note to users that DMs show transparent stickers on a white background
- Local library: content-hash deduplication, separate "file" and "collection entry" models

## Phase 2 — Android v0.2 (experimental sync)
- Optional linked-device source for favorites & order (off by default, risk warning) — only if E5 succeeds

## Phase 3 — iOS
- Keyboard extension with copy/paste flow, manual import via share sheet

## Phase 4 — Desktop
- Windows/macOS helper: global hotkey opens the sticker library, click to copy/paste
