# Roadmap

Rule: critical technical assumptions are proven with small experiments before product code is written.

## Phase 0 — Research & experiments (current)
- [x] Research: WhatsApp data access, Instagram insertion paths (see RESEARCH.md)
- [x] Repository skeleton
- [x] **E1** IME MIME probe — which image types Instagram fields accept from a keyboard (done 2026-11-09, see experiments/E1-ime-probe.md)
- [x] **E0** WhatsApp Android sticker folder contents (done 2026-11-09, see experiments/E0-whatsapp-folder.md)
- [ ] **E2** Send/render matrix (transparency, animation, recipient view)
- [ ] **E3** Share to Stories with a transparent PNG
- [ ] **E4** iOS copy/paste tests (manual)
- [x] **E5** Linked-device favorites (done 2026-11-09, test account only, see experiments/E5-linked-device.md)
- [ ] **E6** WhatsApp Web IndexedDB inspection

## Phase 1 — Android v0.1 keyboard (folder source + manual add) ← *next*

### A1 — sticker keyboard MVP (done, `android/`)
- Onboarding: enable the keyboard, connect the WhatsApp (and optional WhatsApp Business) stickers folder via SAF, scan status
- Local library (Room): one row per file, source `lastModified` shown as "recently seen", own `firstSeenAt`/`lastSeenAt`
- Scanner: one children query per folder, incremental diff, archive only after 3 complete scans without the file
- Scan triggers: keyboard opened (skip < 5 min, otherwise a one-query folder check), ↻ full scan, onboarding screen
- Keyboard: ◀ back to the previous keyboard, ↻ refresh, 4-column grid ordered by "recently seen"
- Insertion via `commitContent` as `image/webp` (decided by E1); no stickers in fields without image MIME types (e.g. comments)
- [x] Animated stickers converted to GIF on tap (libwebp decode + own GIF89a encoder; 1-bit alpha, possible edge halo) — moved from A2
- [x] Quick Settings tile and launcher shortcut to get back to the sticker keyboard
- [x] Keyboard tabs like WhatsApp: Recent (last 32 sent), Favorites (StickerBridge's own, long-press to add/remove), In chats; Room schema v2 with an explicit migration
- [x] Fast GIF conversion with background pre-conversion (100 MB LRU cache), animated previews
- [x] Instagram field classification: GIF in Stories, still image in DMs (E7)

### A2 — sending quality and manual add
- Static stickers sent as WEBP, with PNG conversion as fallback
- Note to users that DMs show transparent stickers on a white background
- Manual add via share menu
- Groups (favorites / packs / all) and a periodic background scan
- Local library: content-hash deduplication, separate "file" and "collection entry" models

## Phase 2 — Android v0.2 optional linked-device favorites ← *then*
- Optional linked-device source for favorites & order (off by default, clear account-risk warning) — feasible per E5
- Planned as a gomobile-bound Go library on-device (inference, untested)
- Favorites list derived idempotently from app state mutations, ordered by timestamp
- Separate conversion path for Lottie favorites
- **A3:** merge WhatsApp favorites into the existing Favorites tab as `sticker_favorite` rows with `source = WHATSAPP` (column already in schema v2, no migration needed); the tab label stays "Favorites"

## Phase 3 — iOS
- Keyboard extension with copy/paste flow, manual import via share sheet

## Phase 4 — Desktop
- Windows/macOS helper: global hotkey opens the sticker library, click to copy/paste

## Later
- Optional: send animated stickers to Instagram DMs as a short video instead of a GIF (DMs drop keyboard GIFs, E7)
- Full typing keyboard with a customizable sticker key (icon & color chosen by the user) — consider FlorisBoard as a base
- A3: linked-device WhatsApp favorites in the Favorites tab (see Phase 2)
- Add stickers from StickerBridge to WhatsApp via the official sticker API
