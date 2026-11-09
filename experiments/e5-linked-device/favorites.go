package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/types/events"
)

// favorite is one favoriteSticker mutation as last seen.
//
// The full StickerAction (with media key and direct path) is kept in memory
// only, for --download. Media keys, URLs and direct paths are never printed
// or written to disk.
type favorite struct {
	Index      string
	IsFavorite bool
	Timestamp  time.Time // SyncActionValue.timestamp
	Mimetype   string
	IsLottie   bool
	IsAvatar   bool
	FileLength uint64
	action     *waSyncAction.StickerAction
}

func short(s string) string {
	if len(s) > 12 {
		return s[:12]
	}
	return s
}

// onHistorySync logs how many favorite and recent stickers the initial
// history sync carries (waHistorySync.HistorySync fields favoriteStickers=22,
// recentStickers=11).
func (p *probe) onHistorySync(evt *events.HistorySync) {
	d := evt.Data
	if d == nil {
		return
	}
	fav, rec := d.GetFavoriteStickers(), d.GetRecentStickers()
	if len(fav) == 0 && len(rec) == 0 {
		fmt.Printf("%s HistorySync type=%s chunk=%d: no FavoriteStickers/RecentStickers\n",
			stamp(), d.GetSyncType(), d.GetChunkOrder())
		return
	}
	lottie := 0
	for _, s := range fav {
		if s.GetIsLottie() {
			lottie++
		}
	}
	fmt.Printf("%s HistorySync type=%s chunk=%d: FavoriteStickers=%d (lottie=%d) RecentStickers=%d\n",
		stamp(), d.GetSyncType(), d.GetChunkOrder(), len(fav), lottie, len(rec))
}

// onAppState handles every SET mutation; only favoriteSticker ones are used.
// Index layout: ["favoriteSticker", <sticker id>].
func (p *probe) onAppState(evt *events.AppState) {
	if len(evt.Index) < 2 || evt.Index[0] != appstate.IndexFavoriteSticker || evt.SyncActionValue == nil {
		return
	}
	act := evt.GetStickerAction()
	if act == nil {
		return
	}
	received := time.Now()
	f := &favorite{
		Index:      evt.Index[1],
		IsFavorite: act.GetIsFavorite(),
		Timestamp:  time.UnixMilli(evt.GetTimestamp()),
		Mimetype:   act.GetMimetype(),
		IsLottie:   act.GetIsLottie(),
		IsAvatar:   act.GetIsAvatarSticker(),
		FileLength: act.GetFileLength(),
		action:     act,
	}
	p.mu.Lock()
	p.favorites[f.Index] = f
	p.mu.Unlock()

	if !p.snapshotDone.Load() {
		return // part of the initial snapshot; printed as a table later
	}
	change := "ADDED  "
	if !f.IsFavorite {
		change = "REMOVED"
	}
	delay := "n/a"
	if evt.GetTimestamp() > 0 {
		delay = received.Sub(f.Timestamp).Round(time.Millisecond).String()
	}
	fmt.Printf("%s LIVE favoriteSticker %s id=%s ts=%s delay=%s mime=%s lottie=%t (SET)\n",
		received.Format("15:04:05.000"), change, short(f.Index),
		f.Timestamp.Format("2006-01-02 15:04:05.000"), delay, f.Mimetype, f.IsLottie)
}

// onRemoveMutation handles a REMOVE operation on a favoriteSticker index.
// whatsmeow emits events.AppState only for SET operations, so REMOVE
// operations are picked up from its structured debug log (see installRemoveHook).
// A REMOVE carries no readable timestamp here, so no delay can be computed.
func (p *probe) onRemoveMutation(index string) {
	p.mu.Lock()
	f, ok := p.favorites[index]
	delete(p.favorites, index)
	p.mu.Unlock()
	if !p.snapshotDone.Load() {
		return
	}
	known := ""
	if ok {
		known = " last-ts=" + f.Timestamp.Format("2006-01-02 15:04:05.000")
	}
	fmt.Printf("%s LIVE favoriteSticker REMOVED id=%s delay=n/a (REMOVE op)%s\n", stamp(), short(index), known)
}

func (p *probe) printTable() {
	p.mu.Lock()
	list := make([]*favorite, 0, len(p.favorites))
	for _, f := range p.favorites {
		list = append(list, f)
	}
	p.mu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].Timestamp.After(list[j].Timestamp) })

	current := 0
	for _, f := range list {
		if f.IsFavorite {
			current++
		}
	}
	fmt.Printf("\nfavoriteSticker mutations in regular_low: %d (isFavorite=true: %d), newest first\n", len(list), current)
	tw := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "#\tid\tisFavorite\ttimestamp\tmimetype\tisLottie\tavatar\tsize")
	for i, f := range list {
		size := "-"
		if f.FileLength > 0 {
			size = fmt.Sprintf("%d", f.FileLength)
		}
		ts := "-"
		if !f.Timestamp.IsZero() && f.Timestamp.UnixMilli() > 0 {
			ts = f.Timestamp.Format("2006-01-02 15:04:05")
		}
		fmt.Fprintf(tw, "%d\t%s\t%t\t%s\t%s\t%t\t%t\t%s\n",
			i+1, short(f.Index), f.IsFavorite, ts, orDash(f.Mimetype), f.IsLottie, f.IsAvatar, size)
	}
	_ = tw.Flush()
	fmt.Println()
}

func (p *probe) currentFavorites() []*favorite {
	p.mu.Lock()
	defer p.mu.Unlock()
	var out []*favorite
	for _, f := range p.favorites {
		if f.IsFavorite {
			out = append(out, f)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Timestamp.After(out[j].Timestamp) })
	return out
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

// removeHookWriter receives whatsmeow's structured app state debug logs and
// forwards REMOVE operations on favoriteSticker indexes. All other log lines
// are discarded; nothing is printed or stored from them. At debug level
// whatsmeow logs index and operation only, never the action payload.
type removeHookWriter struct{ p *probe }

func (w removeHookWriter) Write(b []byte) (int, error) {
	if !strings.Contains(string(b), appstate.IndexFavoriteSticker) {
		return len(b), nil
	}
	var line struct {
		Message   string   `json:"message"`
		Operation string   `json:"operation"`
		Index     []string `json:"index"`
	}
	if json.Unmarshal(b, &line) == nil &&
		line.Message == "Received app state mutation" &&
		line.Operation == "REMOVE" &&
		len(line.Index) >= 2 && line.Index[0] == appstate.IndexFavoriteSticker {
		w.p.onRemoveMutation(line.Index[1])
	}
	return len(b), nil
}

// installRemoveHook makes whatsmeow's context logger (zerolog.Ctx, which has no
// logger attached in the app state code path) fall back to our hook writer.
func installRemoveHook(p *probe) {
	l := zerolog.New(removeHookWriter{p: p}).Level(zerolog.DebugLevel)
	zerolog.DefaultContextLogger = &l
}
