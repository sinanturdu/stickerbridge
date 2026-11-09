# E7 — Can a third-party keyboard send animated GIFs into Instagram DMs?

## Question
StickerBridge converts animated WhatsApp stickers (animated WEBP) to GIF because Instagram freezes animated WEBP (E1). Do these GIFs reach the chat when sent from our keyboard into an Instagram DM, and into a Story?

## Setup
- Samsung phone (Android 14+), Instagram **448.0.0.52.84**, Samsung Keyboard as reference
- StickerBridge A1 debug builds with temporary debug tools (in git history on `feature/android-v0.1-a1`, removed from the app afterwards):
  - a GIF size test (the same sticker converted at 160, 240, 320 and 480 px)
  - four delivery variants of the 240 px GIF (A as normal; B with a `linkUri`; C with description label `image`; D with an extra `image/*` MIME type)
  - a "Receive test" text field that logs how a keyboard delivers content
- The E1 probe's synthetic GIF (52 KB, 4 frames) as a known-small control

## Instagram DM composer field (as reported to keyboards)
| Value | Seen on device |
|---|---|
| `contentMimeTypes` | `image/png`, `image/jpeg`, `image/gif` |
| `fieldId` | 2131441646 |
| `inputType` | `0x000ac001` (text, sentence caps, auto-correct, multi-line, no suggestions) |
| `imeOptions` | `0x02000001` (no fullscreen, action NONE) |

## Results
*Verified with a working prototype (single device, single Instagram version).*

| What was sent | Where | `commitContent` | Result |
|---|---|---|---|
| Samsung Keyboard's own GIF | DM | – | ✅ sent and animates |
| StickerBridge GIF, all sizes 160–480 px | DM | `true` | ❌ not posted; Instagram toast "Tap the sticker icon to find and send GIFs" |
| StickerBridge GIF, delivery variants A–D (linkUri, label, extra MIME) | DM | `true` | ❌ same toast, nothing posted |
| E1 probe GIF (52 KB, 4 frames) | DM | `true` | ❌ same toast, nothing posted |
| Our GIF via gallery / share sheet | DM | – | ❌ not posted |
| StickerBridge static PNG / WEBP | DM | `true` | ✅ sent (on a white card, E1) |
| StickerBridge GIF, all sizes, incl. pre-converted | Story text tool | `true` | ✅ inserted as a sticker layer and animates |

Other observations:
- The GIF files are valid: decoded correctly by the JDK's GIF reader and by Pillow; URI suffix `.gif`, `ContentResolver.getType` = `image/gif`, matching the announced MIME type.
- Samsung Keyboard refused to insert its GIFs/stickers into our own test field ("This content can't be entered here"), even when the field advertised exactly Instagram's MIME types, so its delivery details could not be captured.

## Findings
- In Instagram DMs, GIFs from a third-party keyboard are **silently dropped**: `commitContent` returns `true`, Instagram shows its "tap the sticker icon" toast, nothing is posted. File size, frame count, resolution, description label, extra MIME types and `linkUri` made no difference.
- Instagram **Stories** accept the same GIFs and animate them.
- Static images from our keyboard are sent to DMs normally.

## Hypothesis (unverified)
Instagram DMs only accept GIFs from an **allowlist** of keyboards/GIF providers (Samsung Keyboard and probably Gboard), and route all other GIF inserts to its own GIF picker. This is undocumented and may change with any Instagram version.

## Decisions
1. Animated stickers in **Instagram Stories** are sent as GIF (they animate).
2. Animated stickers in **Instagram DMs** (and in Instagram fields that cannot be classified yet) are sent as the **first frame, as a still PNG with transparency**, with a one-time hint in the keyboard: "Instagram DMs show animated stickers as still images. They animate in Stories."
3. The keyboard classifies Instagram fields as STORY / DM / UNKNOWN from `EditorInfo` (field id, input type, IME options; hint text only as a weak signal). Known signatures live in one config object (`InstagramFields`); the keyboard logs every field's signature (tag `SB-Field`, debug builds) so the Story signature can be added.
4. Later, optional: try sending animated stickers to DMs as a short **video** instead of GIF.
5. The debug experiments were removed from the app; they stay in git history.

Device: Samsung (Android 14+) · Instagram: 448.0.0.52.84 · Date: 2026-11-10
