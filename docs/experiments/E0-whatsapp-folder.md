# E0 — What is inside WhatsApp's Android sticker folder?

## Hypothesis
The folder contains stickers that appeared in chats. Adding a favorite without sending it creates no file.

## Setup
- Android phone with USB debugging enabled
- `adb` installed on the computer (Android SDK Platform-Tools)

## Steps (PowerShell)
1. List the folder:
   `adb shell ls -la --full-time "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"`
2. Count the files and compare with the number of favorites and installed packs in WhatsApp.
3. Favorite a sticker you have **never sent**, then list the folder again. Does a new file appear?
4. Unfavorite a sticker, then list again. Does a file disappear?
5. Check whether a `.nomedia` file exists.

## Success / failure
If favoriting creates no file, the hypothesis is confirmed. The folder source then can't provide favorites, and we need E5.

## Results (counts only — never commit file names or images)
| Check | Result |
|---|---|
| Number of files in `WhatsApp Stickers` | ~11.9k `.webp` (names follow `STK-YYYYMMDD-WAnnnn.webp`) |
| Other sticker folders in `WhatsApp/Media/` | `WhatsApp Backup Excluded Stickers`: 266 `.webp`, base64-hash names · `WhatsApp Sticker Packs`: 64 `STK_PK-YYYYMMDD-WAnnnn.enc` files (encrypted) |
| Number of favorites in WhatsApp | not recorded |
| New file after favoriting a never-sent sticker? | **No** — no file changed in any of the three folders |
| File removed after unfavoriting? | **No** — no file changed in any of the three folders |
| `.nomedia` present? | **Yes** in `WhatsApp Stickers` and `WhatsApp Sticker Packs` |
| What does the file modification time correspond to? | Sending an already-cached sticker created no new file but set the existing file's mtime to the send time (single observation) |

Device: Samsung (Android), single device · Android: not recorded · WhatsApp version: not recorded · Method: `adb` · Date: 2026-11-09

## Findings
*Verified with a working prototype (single device)* — `adb` listings on one Samsung device, 2026-11-09.

- `Android/media/com.whatsapp/WhatsApp/Media/` contains, among others:
  - **`WhatsApp Stickers`** — ~11.9k `.webp` files named `STK-YYYYMMDD-WAnnnn.webp`, `.nomedia` present. These are stickers seen in chats.
  - **`WhatsApp Backup Excluded Stickers`** — 266 `.webp` files with base64-hash names. Purpose unknown.
  - **`WhatsApp Sticker Packs`** — 64 `STK_PK-YYYYMMDD-WAnnnn.enc` files (encrypted), `.nomedia` present.
- **Favorites are not observable from shared storage.** Favoriting and unfavoriting a never-sent sticker changed no file in any of these folders. The hypothesis is confirmed.
- **Possible "recently used" signal:** sending a sticker that was already cached created no new file but updated the existing file's mtime to the send time. This is a single observation and needs a re-check.

## Decisions
1. **Folder source = stickers seen in chats** (`WhatsApp Stickers`).
2. **Order by mtime**, presented as an *observed* "recently seen/used" order — never as WhatsApp's own order.
3. **Access via SAF** (the folders carry `.nomedia`, so MediaStore will not index them).
4. **Favorites are only possible through the experimental linked-device path (E5).**
5. **`WhatsApp Sticker Packs` `.enc` files are out of scope.**
