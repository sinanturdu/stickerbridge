# E9a — Does the file format explain GIPHY animation in Instagram DMs?

Status: **done — negative finding.** Date: 2026-11-10.

## Question
Instagram DMs animate GIFs sent from Samsung Keyboard's GIPHY catalog, but our animated stickers arrive frozen (animated WebP, E1) or not at all (GIF, E7). Is there a file-format difference that could explain this?

## Evidence before this experiment
- Instagram **Web** displays such a DM GIF by loading GIPHY item `g0vZogq3ESorwc8qzK`, rendition `200.webp`, through Meta's signed external media proxy. This is **display evidence** (what the Web client fetches to show the message), **not a captured send payload**; Android may use a different rendition.
- E1: our animated WebP (WhatsApp-style container) **was delivered** to the DM via `commitContent` but rendered as its first frame only.
- E7: our GIFs were **not delivered** at all (all sizes and delivery variants); Samsung Keyboard's catalog GIFs are delivered and animate.

## Method
- The public GIPHY file was fetched once (no cookies/credentials, parameters unchanged) into a temporary folder outside the repo and deleted afterwards. **No third-party media is stored in the repo.**
- A throwaway Python (standard library) script parsed the WebP container: RIFF/WEBP signature, chunk order, VP8X flags, canvas, ANIM background/loop, every ANMF frame (offset, size, duration, blending, disposal, VP8 vs VP8L, ALPH).
- Compared with (a) our synthetic E1 fixture and (b) a synthetic WhatsApp-shaped sticker generated with Pillow (512×512, 20 frames, lossy VP8 + ALPH, loop 0). No personal stickers were read.

## Results (measured)
| Property | GIPHY `200.webp` | E1 fixture `test_webp_animated.webp` | Synthetic WhatsApp-shaped |
|---|---|---|---|
| Size | 11,438 B (`Content-Type: image/webp`) | 30,370 B | 90,228 B |
| Chunk order | VP8X, ANIM, 9× ANMF, XMP | VP8X, ANIM, 4× ANMF | VP8X, ANIM, 20× ANMF |
| VP8X flags | ANIM + ALPHA + XMP | ANIM + ALPHA | ANIM + ALPHA |
| Canvas | 167×200 | 512×512 | 512×512 |
| ANIM background / loop count | transparent / 0 (forever) | transparent / 0 | transparent / 0 |
| Frames / total duration | 9 / 840 ms | 4 / 1,600 ms | 20 / 2,000 ms |
| Frame codecs | mixed: 7 VP8 (+ ALPH, lossless) and 2 VP8L | 4 VP8L | 20 VP8 (+ ALPH, lossless) |
| Frames covering the full canvas | 1 of 9 (rest are sub-rectangles) | 0 of 4 | 4 of 20 |
| Metadata | XMP (784 B): Adobe Photoshop CC 2018 document IDs/creator tool; nothing identifying GIPHY or Instagram | none | none |

All three are valid animated WebP files using the same container features (ANIM + ALPHA, loop 0, transparent background, sub-rectangle frames, blending/disposal). The differences (canvas and file size, frame count and timing, mixed VP8/VP8L, the XMP leftover) do not affect decoding, and none is a plausible delivery or animation switch.

## Findings
- **No measured format difference explains the DM behavior.**
- E1 is decisive: our animated WebP — the same container type as GIPHY's — is **delivered** to DMs but **frozen**, so the DM path does not reject the format; it renders uploaded animated images as a still.
- DM animation appears tied to Instagram's **GIPHY catalog path**: the message references a GIPHY item that clients load by id, rather than an uploaded file. *(Hypothesis based on Web display evidence; no Android send payload was captured.)*

## Decision
- No synthetic format reproduction is worth testing.
- DMs keep sending animated stickers as still images (E7). An optional, owner-decided follow-up is listed in the roadmap: publishing stickers through a GIPHY account so they become catalog items (public uploads; privacy and account implications to be decided by the user).
