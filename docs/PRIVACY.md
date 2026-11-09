# Privacy

## What StickerBridge accesses (planned)
| Data | Why | How |
|---|---|---|
| WhatsApp sticker folder (Android) | To show your stickers | Folder you select once via the Android system picker (SAF). Read-only. |
| Stickers you share to the app | Manual add | Android/iOS share menu |
| Current text field's type (e.g. "accepts images") | To know how to insert a sticker | Standard keyboard API |
| Favorite sticker list (optional, experimental) | Favorites & order | Linked-device sync, only if you enable it |

## What StickerBridge never does
- Read your messages, contacts or chats.
- Log or send anything you type.
- Ask for WhatsApp or Instagram passwords.
- Upload stickers to any server. There is no StickerBridge server.
- Include analytics or ads.

## Storage, backup, deletion
- Library is stored in the app's private storage on your device.
- Uninstalling the app deletes the library.
- Moving to a new phone: export/import of the library file is planned (no cloud).

## Repository rule
The source code is public; user data never is. Real stickers, screenshots, logs and session files are never committed (see `.gitignore` and CONTRIBUTING.md).
