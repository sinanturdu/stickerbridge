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
| DM composer | – | – | image/png, image/jpeg, image/gif | ✅ true, sent | ✅ true, sent (not advertised) | ✅ true, sent, first frame only | ✅ true, sent, animates | not recorded / not checked / not checked |
| Story text tool | – | – | not recorded | ✅ true, sticker layer | ✅ true, sticker layer | ✅ true, static | ✅ true, animates | n/a (posted to Close Friends) |
| Comment field | – | – (imeOptions action=SEND) | null/empty | – | – | – | – | n/a |

Device: Samsung (Android) · Android: not recorded · Instagram version: Instagram for Android, not recorded · Date: 2026-11-09

Single device, single Instagram version. `packageName` and `inputType` values were not recorded; "true" means `commitContent` returned `true`.

## Findings
*Verified with a working prototype* (this E1 probe, one Samsung device, one Instagram Android version, 2026-11-09).

**DM composer**
- Advertises `image/png`, `image/jpeg`, `image/gif`.
- PNG, WEBP, animated WEBP and GIF all returned `commitContent=true` and were **sent immediately with one tap** (no preview or confirm step).
- WEBP is accepted although it is **not advertised**.
- Sender view: **transparency is lost** — the sticker is shown on a white card. Same on light, dark and patterned chat themes.
- GIF animates. Animated WEBP shows only the first frame.

**Story text tool**
- Same insertion behavior as the DM composer (all four formats return `true`).
- Images are inserted as **movable sticker layers**.
- **Transparency is preserved** in the editor and after posting (tested with a Close Friends story).
- GIF animates in the story. Animated WEBP is static.

**Comment field**
- `contentMimeTypes` = null/empty, `imeOptions` action = SEND. No rich content possible.

**Not yet checked**
- Recipient view on iOS and Web (→ E2).
- Other devices, Android versions and Instagram versions.

## Decisions
1. **Android insertion method = `commitContent`.** The share-intent fallback is not needed for DM and Stories.
2. **Animated stickers are converted to GIF** before insertion. Trade-off: GIF has 1-bit alpha, so semi-transparent edges may show a halo.
3. **Static stickers are sent as WEBP**, with **PNG conversion as fallback** (WEBP works today but is not advertised, so it may break).
4. **Hide the sticker buttons when the field advertises no image types** (e.g. the comment field).
5. **Tell users that DMs show transparent stickers on a white background.**
