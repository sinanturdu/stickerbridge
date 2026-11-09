package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"go.mau.fi/whatsmeow"
)

// downloadAll downloads and decrypts every current favorite into out/favorites/,
// named by the SHA-256 of the decrypted bytes. Media keys and paths are never printed.
func (p *probe) downloadAll() {
	favs := p.currentFavorites()
	dir := filepath.Join(outDir, "favorites")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		fmt.Println(stamp(), "download: cannot create", dir, err)
		return
	}
	fmt.Printf("%s Downloading %d current favorites into %s ...\n", stamp(), len(favs), dir)

	ok, failed := 0, 0
	kinds := map[string]int{}
	for i, f := range favs {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		data, err := p.downloadOne(ctx, f)
		cancel()
		if err != nil {
			failed++
			fmt.Printf("  [%d/%d] id=%s FAILED: %v\n", i+1, len(favs), short(f.Index), err)
			continue
		}
		kind, ext := detectKind(data, f)
		sum := sha256.Sum256(data)
		name := hex.EncodeToString(sum[:]) + ext
		if err := os.WriteFile(filepath.Join(dir, name), data, 0o600); err != nil {
			failed++
			fmt.Printf("  [%d/%d] id=%s FAILED to write: %v\n", i+1, len(favs), short(f.Index), err)
			continue
		}
		ok++
		kinds[kind]++
		fmt.Printf("  [%d/%d] id=%s ok %s %d bytes -> %s\n", i+1, len(favs), short(f.Index), kind, len(data), short(name))
	}
	fmt.Printf("%s Download done: %d ok, %d failed. Types:", stamp(), ok, failed)
	for _, k := range []string{"webp-static", "webp-animated", "lottie", "other"} {
		fmt.Printf(" %s=%d", k, kinds[k])
	}
	fmt.Println()
}

func (p *probe) downloadOne(ctx context.Context, f *favorite) ([]byte, error) {
	a := f.action
	if a == nil {
		return nil, fmt.Errorf("no sticker action")
	}
	if a.GetDirectPath() == "" {
		return nil, fmt.Errorf("no direct path in favorite")
	}
	if len(a.GetMediaKey()) == 0 {
		return nil, fmt.Errorf("no media key in favorite")
	}
	// StickerAction carries no plaintext fileSHA256, so the plaintext hash check is skipped
	// (allowNoHash=true). The encrypted file hash and HMAC are still verified by whatsmeow.
	return p.cli.DownloadMediaWithPath(ctx, a.GetDirectPath(), a.GetFileEncSHA256(), nil,
		a.GetMediaKey(), whatsmeow.MediaImage, "", true)
}

// detectKind classifies decrypted sticker bytes.
func detectKind(data []byte, f *favorite) (kind, ext string) {
	switch {
	case len(data) >= 12 && bytes.Equal(data[0:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")):
		if isAnimatedWebP(data) {
			return "webp-animated", ".webp"
		}
		return "webp-static", ".webp"
	case f.IsLottie || bytes.HasPrefix(data, []byte("PK\x03\x04")):
		// Lottie stickers are zip archives (.was) containing JSON animation data.
		return "lottie", ".was"
	default:
		return "other", ".bin"
	}
}

// isAnimatedWebP checks the VP8X header's animation flag or an ANIM chunk.
func isAnimatedWebP(data []byte) bool {
	pos := 12
	for pos+8 <= len(data) {
		fourcc := string(data[pos : pos+4])
		size := int(binary.LittleEndian.Uint32(data[pos+4 : pos+8]))
		if fourcc == "VP8X" && pos+8 < len(data) && data[pos+8]&0x02 != 0 {
			return true
		}
		if fourcc == "ANIM" || fourcc == "ANMF" {
			return true
		}
		pos += 8 + size + size%2
	}
	return false
}
