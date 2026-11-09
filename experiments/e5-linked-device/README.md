# E5 — Linked-device favorites probe (EXPERIMENTAL, UNOFFICIAL)

> ⚠️ **TEST ACCOUNT ONLY — unofficial client — read-only.**
> This program connects to WhatsApp as a *linked device* using the unofficial [whatsmeow](https://github.com/tulir/whatsmeow) library.
> **WhatsApp's Terms of Service forbid unofficial clients and automated access. Your account can be banned.**
> Use a **secondary number** that you can afford to lose. Never run this with your main account.

Plan and results: [docs/experiments/E5-linked-device.md](../../docs/experiments/E5-linked-device.md).

## Purpose
Find out whether a linked-device companion can **read** a user's WhatsApp favorite stickers:
- the list of favorites, their order and timestamps,
- whether adding/removing a favorite on the phone is detected quickly,
- whether the sticker images can be downloaded and decrypted.

## What it does
1. Pairs via QR code (WhatsApp → Settings → Linked devices → Link a device). The device appears as **"StickerBridge E5 (test)"**.
2. Logs `FavoriteStickers` / `RecentStickers` counts from the initial history sync (`events.HistorySync`).
3. Fetches the full `regular_low` app state collection and prints every `favoriteSticker` mutation (id, isFavorite, timestamp, mimetype, isLottie, avatar, size), newest first.
4. Keeps running and prints a `LIVE` line as soon as a `favoriteSticker` mutation arrives (added/removed, timestamp, short id, delay between mutation timestamp and local receive time).
5. With `--download`: downloads and decrypts each current favorite into `out/favorites/<sha256>.webp|.was|.bin` and prints success/failure counts and types (static WEBP, animated WEBP, Lottie).

## Read-only guarantees
- Never sends messages, never sends read receipts, never sends presence, never marks anything as read (`SendMessage`, `MarkRead`, `SendPresence` are never called).
- Never prints or stores media keys, URLs or direct paths.
- Writes **only** under `out/` (gitignored): `out/session.db` and, with `--download`, `out/favorites/`.

What the protocol still does by itself (cannot be turned off in whatsmeow): it uploads its own encryption prekeys, keeps the connection alive, and acknowledges incoming messages with *inactive* delivery receipts. Because presence is never set to "available", these are not read receipts (no blue ticks).

## Requirements
- Go **1.26+** (the latest whatsmeow requires Go 1.26). Check with `go version`.
- No CGO, no C compiler: SQLite is the pure-Go `modernc.org/sqlite`.
- A phone with the **test** WhatsApp account, online for pairing.

Dependencies: `go.mau.fi/whatsmeow`, `modernc.org/sqlite`, `github.com/mdp/qrterminal/v3`, plus `github.com/rs/zerolog` (already pulled in by whatsmeow; used directly to detect REMOVE mutations, see below).

## Run (Windows PowerShell)
```powershell
cd experiments\e5-linked-device

# pair (first run shows a QR code) or reconnect; list favorites; watch live changes
go run .

# same, plus download + decrypt current favorites into out\favorites\
go run . --download

# unlink this device from WhatsApp and delete out\session.db
go run . --logout
```
Optional: `go run . --debug` shows whatsmeow debug logs.
Stop with **Ctrl+C** (disconnects cleanly; the device stays linked).

The QR code uses Unicode half blocks. If it looks broken, use Windows Terminal, enlarge the window or zoom out (Ctrl + −).

## Unlink from the phone
WhatsApp → Settings (⋮ on Android) → **Linked devices** → tap **StickerBridge E5 (test)** → **Log out**.
Then delete the local session: `Remove-Item -Recurse -Force out` (or run `go run . --logout`).

## How favorites are read (technical notes)
- Favorites are app state mutations with index `["favoriteSticker", <id>]` in the `regular_low` collection (`appstate.IndexFavoriteSticker`, `appstate.WAPatchRegularLow`). The value is `SyncActionValue.stickerAction` (`waSyncAction.StickerAction`: `isFavorite`, `mimetype`, `isLottie`, `fileLength`, `directPath`, `mediaKey`, …) and `SyncActionValue.timestamp` (ms).
- History sync: `waHistorySync.HistorySync.favoriteStickers` (field 22) and `.recentStickers` (field 11), both `[]StickerMetadata`.
- `EmitAppStateEventsOnFullSync = true` makes whatsmeow emit `events.AppState` for the snapshot too.
- whatsmeow emits `events.AppState` only for **SET** operations. A favorite removed with `isFavorite=false` (SET) is shown with a delay. If WhatsApp uses a **REMOVE** operation instead, it is picked up from whatsmeow's structured debug log (index + operation only, no payload) and printed with `delay=n/a`. Which of the two WhatsApp uses is one of the things to record.
- `StickerAction` has no plaintext SHA-256, so downloads skip that check (the encrypted-file hash and HMAC are still verified); files are named by the SHA-256 of the decrypted bytes.

## Test checklist
Record results (text only — no screenshots, no sticker files, no logs) in [docs/experiments/E5-linked-device.md](../../docs/experiments/E5-linked-device.md).

- [ ] **Pair:** `go run .`, scan the QR with the test phone. Device shows as "StickerBridge E5 (test)".
- [ ] **Count and order:** compare the number of `isFavorite=true` rows and their order with the phone's favorites tab in the sticker picker.
- [ ] **Add:** favorite a sticker on the phone; note the `LIVE … ADDED` line and its delay.
- [ ] **Remove:** unfavorite a sticker; note the `LIVE … REMOVED` line, its delay and whether it was `(SET)` or `(REMOVE op)`.
- [ ] **Download:** Ctrl+C, then `go run . --download`; note ok/failed counts and types.
- [ ] **Phone offline:** put the phone in airplane mode, Ctrl+C and restart `go run .`; note what still loads (connect, table, live changes, download).
- [ ] **Clean up:** `go run . --logout` and confirm the device is gone from the phone's Linked devices list. Delete `out\` if anything is left.
