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

## Field signatures (Instagram 448.0.0.52.84, keyboard log `SB-Field`)
| Field | fieldId | inputType | imeOptions | contentMimeTypes | hint | Classified as |
|---|---|---|---|---|---|---|
| DM composer | 2131441646 | `0x000ac001` | `0x02000001` (no fullscreen, action NONE) | `image/png`, `image/jpeg`, `image/gif` | localized ("Message…") | DM |
| Story text tool | 2131443961 | `0x0002c001` | `0x40000006` (no enter action, action DONE) | `image/*` | empty | STORY |

Fallback rules for other Instagram versions (resource ids may change): exactly `image/png` + `image/jpeg` + `image/gif` → DM; `image/*` with action DONE and an empty hint → STORY; anything else → UNKNOWN (still image).

## Results
*Verified with a working prototype (single device, single Instagram version).*

| What was sent | Where | `commitContent` | Result |
|---|---|---|---|
| Samsung Keyboard's built-in catalog GIF | DM | – | ✅ sent and animates; exact item/provider unrecorded |
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
- In the tested Instagram DM configuration, StickerBridge GIFs were not posted despite `commitContent=true`; Instagram showed its "tap the sticker icon" toast. The tested size, frame count, resolution, description label, extra MIME and `linkUri` variants did not change that result. This is not a result for every third-party keyboard or Instagram version.
- Instagram **Stories** accept the same GIFs and animate them.
- Static images from our keyboard are sent to DMs normally.
- [E9](E9-gif-provider-access.md#controlled-comparison-and-conclusion) adds a controlled access comparison: confirmed DM attempt 15 was rejected with no captured provider/read events; confirmed Story attempt 20 has OPEN_CALL, OPEN_RETURN and FS_ACCESS, and the owner confirms the Story workflow works with the numbered synthetic GIF visible in the editor. The harness can observe opening and read-access notifications in Story; the DM capture differs. This establishes neither reader identity, complete reads, rejection cause nor an allowlist, and missing DM events do not prove no access was attempted.
- Attempts 1-14 and 17-19 are unclassified; no contexts or outcomes are inferred. Historical E1 success remains a reported observation with incomplete provenance. Private inline GIF delivery in DMs is unresolved; no GIF re-encoding or delivery variant is justified by the E9 evidence. E8 stays paused.

## Hypothesis (unverified)
An **allowlist** or other keyboard/provider-specific acceptance rule remains a hypothesis.
Samsung's successful reference was from built-in catalog search, not a private local-GIF import;
that does not distinguish catalog-only acceptance from acceptance of arbitrary GIFs from a
particular keyboard. Gboard acceptance was not verified. E9 provider access does not establish
such a rule.

## Decisions
These are historical E7 implementation decisions, not a finding that static images meet the
owner's current requirement. E8's video follow-up is paused; private inline DM GIF delivery is unresolved.

1. Animated stickers in **Instagram Stories** are sent as GIF (they animate).
2. Animated stickers in **Instagram DMs** (and in Instagram fields that cannot be classified yet) are sent as the **first frame, as a still PNG with transparency**, with a one-time hint in the keyboard: "Instagram DMs show animated stickers as still images. They animate in Stories."
3. The keyboard classifies Instagram fields as STORY / DM / UNKNOWN from `EditorInfo`: first the recorded signatures (field id + input type + IME options), then the content-type fallback rules above. Known signatures live in one config object (`InstagramFields`); the keyboard logs every field's signature (tag `SB-Field`, debug builds) so new versions can be added.
4. Later, optional: try sending animated stickers to DMs as a short **video** instead of GIF.
5. The debug experiments were removed from the app; they stay in git history.

Device: Samsung (Android 14+) · Instagram: 448.0.0.52.84 · Date: 2026-11-10
