# Research summary

Research date: 2026-11-09. Only rows labelled ✅ prototype were tested on a device (E1: one Samsung device, one Instagram Android version, 2026-11-09). Everything else is untested.

Each finding carries one label:
- ✅ official docs
- ✅ prototype — verified with a working prototype on a real device (see the linked experiment)
- 🔎 seen in third-party code or reports (needs verification)
- 💭 technical inference (not tested)
- ⛔ unsupported or not accessible

## A. Reading the WhatsApp sticker collection

| Finding | Label |
|---|---|
| WhatsApp has no official API to read a user's stickers or favorites. The official sticker API only sends packs *into* WhatsApp. | ✅ [1] |
| On Android, sticker files live in `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers`. This folder probably holds only stickers seen in chats. | 🔎 |
| The Android 11+ folder picker (SAF) blocks `Android/data` and `Android/obb`, but not `Android/media`. | ✅ [2] |
| Favorites, their order and pack membership live in WhatsApp's private `sticker.db`. Other apps can't read it, and backups are encrypted. | ✅ [3][4] |
| `MANAGE_EXTERNAL_STORAGE`, broad photo access and Accessibility-based screen reading are restricted by Google Play policy. | ✅ [5][6][7] |
| Favorites sync to linked devices as encrypted app state (`favoriteSticker` in `regular_low`, with timestamp and `isFavorite` flag). | 🔎 [8] |
| Linked-device libraries are unofficial. WhatsApp's Terms forbid automated access, so there is a risk of account ban. | ✅ [9] |
| On iOS, no app can read WhatsApp's data. App groups are shared only between apps from the same developer. | ✅ [10] |
| WhatsApp Business Platform receives only stickers sent *to* a business number. It cannot read a personal account's favorites. | ✅ [11] |

## B. Using stickers inside Instagram

| Finding | Label |
|---|---|
| There is no way to add a personal collection to Instagram's sticker tray. GIPHY channels are public, moderated and require brand verification. | ✅ [12] |
| Instagram's Android DM composer and Story text tool accept keyboard images via `commitContent`. DM advertises `image/png`, `image/jpeg`, `image/gif`; PNG, WEBP (not advertised), animated WEBP and GIF all return `true` and are sent immediately with one tap. Stories insert them as movable sticker layers. The comment field advertises no MIME types (no rich content). | ✅ prototype → [E1](experiments/E1-ime-probe.md) |
| Share to Stories places a static PNG or JPG as a sticker layer. It requires a Facebook App ID and does not support animation. | ✅ [13] |
| An iOS keyboard cannot insert images directly. It copies to the clipboard (needs Full Access) and the user pastes. | 🔎 💭 [14] |
| On iOS Stories, a pasted image becomes a sticker layer. Reports say transparency turns black and GIFs freeze after posting. | 🔎 [15] |
| DM transparency: lost — the sticker is shown on a white card (sender view, on light, dark and patterned themes). Stories keep transparency in the editor and after posting. Recipient view on iOS and Web not yet checked. | ✅ prototype → [E1](experiments/E1-ime-probe.md); recipient → **E2** |
| Animation via keyboard insertion: GIF animates in DM and Stories; animated WEBP shows only the first frame in both. | ✅ prototype → [E1](experiments/E1-ime-probe.md) |
| Instagram APIs serve only Business and Creator accounts. Personal accounts need no account linking. | ✅ [16] |

## Conclusion
Not every requirement can be met through official means at the same time:
- Favorites and their order are reachable only through the unofficial linked-device path.
- iOS has no automatic sync.
- A "real sticker" look (transparent, animated as GIF) works in Stories via the keyboard; DMs show stickers on a white card (E1).

## Sources
1. https://github.com/WhatsApp/stickers/blob/master/Android/README.md
2. https://developer.android.com/training/data-storage/shared/documents-files
3. https://developer.android.com/about/versions/11/privacy/storage
4. https://whatsapp.com/faq/en/android/28000019
5. https://support.google.com/googleplay/android-developer/answer/10467955
6. https://support.google.com/googleplay/android-developer/answer/14115180
7. https://support.google.com/googleplay/android-developer/answer/10964491
8. https://github.com/tulir/whatsmeow and https://engineering.fb.com/2021/07/14/security/whatsapp-multi-device/
9. https://www.whatsapp.com/legal/terms-of-service
10. https://developer.apple.com/documentation/xcode/configuring-app-groups
11. https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages/sticker/
12. https://support.giphy.com/hc/en-us/articles/360020020472
13. https://developers.facebook.com/docs/instagram-platform/sharing-to-stories
14. https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard
15. https://developers.secure.facebook.com/community/threads/466049146425815
16. https://developers.facebook.com/docs/instagram-platform/instagram-api-with-facebook-login
