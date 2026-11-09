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
| Number of files | |
| Number of favorites in WhatsApp | |
| New file after favoriting a never-sent sticker? | |
| File removed after unfavoriting? | |
| `.nomedia` present? | |
| What does the file modification time correspond to? | |

Device: … · Android: … · WhatsApp version: … · Date: …
