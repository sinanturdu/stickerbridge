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
| Pairing works | |
| Favorites count matches phone | |
| Order matches phone favorites tab | |
| Timestamps present | |
| Add detected (delay) | |
| Remove detected (delay) | |
| Works with phone offline (cached) | |
| Download + decrypt success rate | |
| Animated / Lottie handling | |

Device: … · Android: … · WhatsApp version: … · whatsmeow version: … · Date: …
