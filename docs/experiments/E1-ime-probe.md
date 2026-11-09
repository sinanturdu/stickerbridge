# E1 — Which image types do Instagram fields accept from a keyboard?

## Hypothesis
Instagram's DM composer advertises image MIME types via `EditorInfo.contentMimeTypes`, so a keyboard can insert stickers directly with `commitContent`. The story text tool and the comment field may not.

## Setup
- Android phone (Android 8+), Instagram installed and logged in
- A second account or a friend to receive test messages
- `experiments/e1-ime-probe` installed (debug APK)

## Steps
1. Enable "IME Probe" in system keyboard settings.
2. For each field below, focus it, switch to IME Probe, and write down the info shown.
3. Press the PNG, WEBP, animated WEBP and GIF buttons. Note whether `commitContent` returned true or false and what happened.
4. Check what the recipient sees on Android, iOS and Web.

## Success / failure
- **Success:** the DM field lists at least one image type, and a sticker arrives.
- **Failure:** `null` or an empty list, or `commitContent` returns false. In that case we use the share-intent fallback.

## Impact on architecture
This experiment decides the Android insertion method for v0.1.

## Results
| Instagram field | packageName | inputType | contentMimeTypes | PNG | WEBP | anim. WEBP | GIF | Recipient (Android / iOS / Web) |
|---|---|---|---|---|---|---|---|---|
| DM composer | | | | | | | | |
| Story text tool | | | | | | | | |
| Comment field | | | | | | | | |

Device: … · Android: … · Instagram version: … · Date: …
