# Roadmap

Rule: critical technical assumptions are proven with small experiments before product code is written.

## Phase 0 — Research & experiments (current)
- [x] Research: WhatsApp data access, Instagram insertion paths (see RESEARCH.md)
- [x] Repository skeleton
- [ ] **E1** IME MIME probe — which image types Instagram fields accept from a keyboard ← *next*
- [ ] **E0** WhatsApp Android sticker folder contents
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
- Insertion into Instagram (method decided by E1/E2), clear notice when an animated sticker becomes static
- Local library: content-hash deduplication, separate "file" and "collection entry" models

## Phase 2 — Android v0.2 (experimental sync)
- Optional linked-device source for favorites & order (off by default, risk warning) — only if E5 succeeds

## Phase 3 — iOS
- Keyboard extension with copy/paste flow, manual import via share sheet

## Phase 4 — Desktop
- Windows/macOS helper: global hotkey opens the sticker library, click to copy/paste
