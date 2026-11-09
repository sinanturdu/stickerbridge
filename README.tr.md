# StickerBridge (Türkçe)

**WhatsApp stickerlarını Instagram'da kullan — kendi kendini güncelleyen bir sticker klavyesiyle.**

StickerBridge, açık kaynaklı bir sticker klavyesidir. Önce Android için, sonra iOS için geliştirilecek.
Klavyede rengini ve simgesini seçebileceğin bir sticker tuşu var. Instagram'da bu tuşa bastığında WhatsApp stickerların açılır; DM'de gönderebilir ya da hikâyeye ekleyebilirsin.
Yeni stickerlar kısa bir gecikmeyle kendiliğinden gelir. Ayrıca her zaman bir ↻ yenile düğmesi var.

> **Durum: araştırma ve deney aşaması.** Henüz kurulabilir bir sürüm yok.

## Dürüst sınırlar

- WhatsApp'ın sticker koleksiyonunu okumak için resmî bir API'si yok.
- Favoriler ve favori sırası yalnızca resmî olmayan "bağlı cihaz" yöntemiyle alınabiliyor. Bu seçenek isteğe bağlı, varsayılan olarak kapalı ve hesap riski için uyarılı.
- Android'deki sticker klasörü izinle okunabiliyor. Ama klasörde büyük ihtimalle yalnızca sohbetlerde görülen stickerlar var.
- iOS'ta hiçbir uygulama WhatsApp verisini okuyamıyor. iOS tarafı elle içe aktarma ve kopyala/yapıştır ile çalışacak.
- Instagram'ın kendi sticker çekmecesine ekleme yapılamıyor. StickerBridge özel bir klavye olarak çalışıyor.
- Gboard'a veya Samsung klavyesine tuş eklenemiyor. Sticker tuşu StickerBridge klavyesinin içinde.

## Gizlilik

- Her şey senin cihazında çalışır. Sunucu yok, hesap yok.
- Şifre istenmez.
- Klavye yazdıklarını kaydetmez ve hiçbir yere göndermez.

Ayrıntılar: [README.md](README.md) · [docs/RESEARCH.md](docs/RESEARCH.md) · [docs/ROADMAP.md](docs/ROADMAP.md)
