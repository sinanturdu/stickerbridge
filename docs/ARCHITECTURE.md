# Architecture (draft — will change after experiments)

## Overview
```
┌──────────────── Sources (adapters) ────────────────┐
│ FolderSource      SAF tree on WhatsApp Stickers dir │  default, official storage access
│ LinkedDeviceSource  favorites via app-state sync    │  optional, EXPERIMENTAL, off by default
│ ManualSource      share menu / file picker          │
└───────────────┬────────────────────────────────────┘
                ▼
        Sync engine (full scan + incremental)
                ▼
   Local library (on-device database + file store)
                ▼
   Keyboard UI (sticker key, grid, groups, ↻ refresh)
                ▼
   Insertion layer: commitContent → share-intent fallback → Share to Stories
```

## Data model principles
- **StickerFile**: identified by content hash (SHA-256 of bytes); mime, size, animated flag, static preview.
- **CollectionEntry**: (source, sourceId, group, sourceOrder, sourceTimestamp, firstSeenAt, lastSeenAt, missingScanCount, isFavorite).
  - The same file can be in several groups without duplicate files.
- Three separate time/order fields, never mixed:
  - `sourceOrder` / `sourceTimestamp`: only when the source really provides them
  - `firstSeenAt`: when *we* first observed it (never shown as "added to WhatsApp")
- Not seen in one scan ≠ deleted. An entry is archived only after N complete scans.
- Scans are idempotent: repeated events never create duplicates.
- The UI shows "last successful sync" time.

## Sync triggers (Android)
| Trigger | Latency |
|---|---|
| Keyboard opened | Instant scan (incremental) |
| ↻ button | Full scan |
| WorkManager periodic | ~15 min minimum (Android limit) |
| Linked-device event (experimental) | Seconds, while the app process is alive |
