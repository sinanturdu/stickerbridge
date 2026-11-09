# E5 — Can a linked device read WhatsApp favorite stickers?

> **EXPERIMENTAL, UNOFFICIAL. TEST ACCOUNT ONLY.** WhatsApp's Terms forbid unofficial clients; account ban risk. Use a secondary number. The probe is read-only.

Code: [`experiments/e5-linked-device/`](../../experiments/e5-linked-device/)

## Hypothesis
A linked-device (multi-device) companion receives the user's favorite stickers as `favoriteSticker` app state mutations in the `regular_low` collection. It can list them with timestamps, detect add/remove within seconds, and download and decrypt the sticker images. This is the only path to favorites, because E0 showed favorites leave no trace in shared storage.

## Setup
- A **secondary/test** WhatsApp account on an Android phone, with some favorite stickers (static, animated, ideally one Lottie/avatar)
- Windows PC with Go 1.26+
- `experiments/e5-linked-device` (run with `go run .`)

## Steps
1. **Pair:** `go run .`, scan the QR (WhatsApp → Linked devices → Link a device).
2. **Count and order:** compare the printed `isFavorite=true` rows (newest first) with the phone's favorites tab.
3. **Add:** favorite a sticker on the phone; note the `LIVE … ADDED` line and the delay.
4. **Remove:** unfavorite a sticker; note the `LIVE … REMOVED` line, the delay and the operation type (SET or REMOVE).
5. **Download:** restart with `go run . --download`; note ok/failed counts and types.
6. **Phone offline:** airplane mode on the phone, restart the program; note what still loads.
7. **Clean up:** `go run . --logout`; confirm the device is gone from Linked devices.

## Success / failure
- **Success:** favorites count and order match the phone, timestamps are present, add/remove is detected within ~10 s, and ≥ 90 % of favorites download and decrypt.
- **Partial:** the list is readable but order, live updates or downloads are unreliable → favorites sync only as a manual "refresh" in the experimental module.
- **Failure:** favorites are not readable, or pairing/reading causes account warnings → drop the linked-device path; StickerBridge stays with the folder source (E0) and manual add.

## Impact on architecture
Decides whether Phase 2 (optional, experimental linked-device sync for favorites & order) is built.

## Results
| Check | Result |
|---|---|
| Pairing works | ✅ Yes, via QR (first attempt failed with "couldn't link device", second worked) |
| Favorites count matches phone | Not checked exactly. 907 `favoriteSticker` entries, 906 with `isFavorite=true` |
| Order matches phone favorites tab | ✅ Yes. Downloaded gallery (newest first) matches the phone's favorites tab in order and look |
| Timestamps present | ✅ Yes, on every entry |
| Add detected (delay) | ✅ ~0.9 s |
| Remove detected (delay) | ✅ ~1.2 s (REMOVE op without timestamp, plus SET `isFavorite=false`) |
| Works with phone offline (cached) | Not tested |
| Download + decrypt success rate | 29/30 after the fix (1 failed: re-added favorite without `directPath`) |
| Animated / Lottie handling | 18 animated + 11 static WEBP downloaded and shown correctly. 3 Lottie entries (`application/was`) in the list, not rendered |

Device: Android phone (WhatsApp main device) + Windows Go desktop probe · Android: not recorded · WhatsApp version: not recorded · whatsmeow: `v0.0.0-20261107160454-79dc2a8c33df` · Date: 2026-11-09

## Findings
*Verified with a working prototype (single account, Go desktop probe, 2026-11-09).*

**Pairing**
- Linking via QR works. The first attempt failed with "couldn't link device"; the second worked.

**Favorites list and order**
- A full sync of `regular_low` takes < 1 s and returned **907 `favoriteSticker` entries, 906 with `isFavorite=true`**.
- **Every entry has a timestamp**, so the real favorites order (newest first) is available.
- The downloaded gallery's order and look match the phone's favorites tab.
- Types: **904 `image/webp`, 3 Lottie (`application/was`)**.

**Live changes**
- Add detected in **~0.9 s**, remove in **~1.2 s**.
- A remove arrives as a **REMOVE op (no timestamp) plus a SET with `isFavorite=false`**.
- Each change also **rewrites an older favorite as REMOVE + SET with its old timestamp**. The client must therefore apply mutations idempotently and derive the final list state; individual events must not be trusted as "user added/removed X".

**History sync**
- The history sync contained **no `FavoriteStickers` / `RecentStickers`** (only RECENT chunks). Recent stickers are not available this way.

**Download**
- The favorite's index hash is **not** the SHA-256 of the plaintext file.
- whatsmeow's standard download always checks the plaintext SHA-256, which `StickerAction` does not carry → 30/30 failed at first.
- Verifying by encrypted hash (`fileEncSHA256`) + media MAC instead: **29/30 ok** (11 static WEBP, 18 animated WEBP). 1 failed: a just re-added favorite without `directPath`.

**Not tested**
- Exact favorites count vs. the phone, phone offline, `--logout`.

## Decisions
1. **The linked-device favorites source is feasible but unofficial** (WhatsApp Terms, account-ban risk). It ships only as an **optional module, off by default, with a clear warning**, in v0.2.
2. **Android integration plan: a gomobile-bound Go library running on-device** (whatsmeow). This is an inference and untested.
3. **Lottie stickers need a separate conversion path** (they cannot be shown or sent as WEBP/GIF as-is).
4. Favorites state is built by **idempotently applying app state mutations** and deriving the final list, ordered by mutation timestamp.
