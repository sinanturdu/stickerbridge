// E5 — EXPERIMENTAL, UNOFFICIAL linked-device probe for WhatsApp favorite stickers.
//
// TEST ACCOUNT ONLY. Read-only: this program never sends messages, never sends
// read receipts or presence and never marks anything as read.
//
// Usage (PowerShell, from experiments/e5-linked-device):
//
//	go run .              pair (QR) or reconnect, list favorites, watch live changes
//	go run . --download   same, plus download+decrypt current favorites into out/favorites/
//	go run . --logout     unlink this device from WhatsApp and delete out/session.db
package main

import (
	"context"
	"database/sql"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/mdp/qrterminal/v3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	_ "modernc.org/sqlite" // pure-Go SQLite driver, registers as "sqlite"
)

const (
	outDir      = "out"
	sessionFile = "session.db"
	deviceName  = "StickerBridge E5 (test)"
	warning     = "TEST ACCOUNT ONLY - unofficial client - read-only"
)

var (
	flagDownload = flag.Bool("download", false, "download and decrypt current favorite stickers into out/favorites/")
	flagLogout   = flag.Bool("logout", false, "unlink this device from WhatsApp and delete the local session")
	flagDebug    = flag.Bool("debug", false, "verbose whatsmeow logs (never includes media keys at this level)")
)

func main() {
	flag.Parse()
	printBanner()

	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "ERROR:", err)
		os.Exit(1)
	}
}

func printBanner() {
	line := "=================================================================="
	fmt.Println(line)
	fmt.Println("  StickerBridge E5 - linked-device favorites probe (EXPERIMENTAL)")
	fmt.Println("  " + warning)
	fmt.Println("  WhatsApp's Terms forbid unofficial clients. Account ban risk.")
	fmt.Println("  Use a secondary number only.")
	fmt.Println(line)
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := os.MkdirAll(outDir, 0o700); err != nil {
		return err
	}
	sessionPath := filepath.Join(outDir, sessionFile)

	// The name shown in WhatsApp > Linked devices.
	store.DeviceProps.Os = new(deviceName)

	logLevel := "WARN"
	if *flagDebug {
		logLevel = "DEBUG"
	}
	dbLog := waLog.Stdout("DB", "WARN", true)
	clientLog := waLog.Stdout("WA", logLevel, true)

	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(sessionPath)+
		"?_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return fmt.Errorf("open session db: %w", err)
	}
	db.SetMaxOpenConns(1)
	container := sqlstore.NewWithDB(db, "sqlite3", dbLog)
	if err := container.Upgrade(ctx); err != nil {
		_ = db.Close()
		return fmt.Errorf("prepare session db: %w", err)
	}

	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		_ = db.Close()
		return fmt.Errorf("load device: %w", err)
	}

	cli := whatsmeow.NewClient(device, clientLog)

	if *flagLogout {
		return logout(ctx, cli, db, sessionPath)
	}
	defer db.Close()

	p := newProbe(cli)
	installRemoveHook(p)
	// Emit events.AppState for every mutation, also during a full sync (needed to see the snapshot).
	cli.EmitAppStateEventsOnFullSync = true
	// Lets the REMOVE-mutation hook see app state mutations at debug level (without action payloads).
	cli.AppStateDebugLogs = true
	cli.AddEventHandler(p.handle)

	if cli.Store.ID == nil {
		if err := pair(ctx, cli); err != nil {
			return err
		}
	} else {
		fmt.Printf("Session found (%s). Connecting...\n", sessionPath)
		if err := cli.ConnectContext(ctx); err != nil {
			return fmt.Errorf("connect: %w", err)
		}
	}
	defer func() {
		fmt.Println("Disconnecting...")
		cli.Disconnect()
	}()

	go p.initialSync(ctx)

	<-ctx.Done()
	return nil
}

func pair(ctx context.Context, cli *whatsmeow.Client) error {
	qrChan, err := cli.GetQRChannel(ctx)
	if err != nil {
		return fmt.Errorf("get QR channel: %w", err)
	}
	if err := cli.ConnectContext(ctx); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	fmt.Println("Not paired yet. On the TEST phone: WhatsApp > Settings > Linked devices > Link a device, then scan:")
	for item := range qrChan {
		switch item.Event {
		case "code":
			qrterminal.GenerateWithConfig(item.Code, qrterminal.Config{
				Level:          qrterminal.L,
				Writer:         os.Stdout,
				HalfBlocks:     true,
				BlackChar:      qrterminal.BLACK_BLACK,
				WhiteChar:      qrterminal.WHITE_WHITE,
				BlackWhiteChar: qrterminal.BLACK_WHITE,
				WhiteBlackChar: qrterminal.WHITE_BLACK,
				QuietZone:      1,
			})
			fmt.Printf("(QR refreshes in %s)\n", item.Timeout.Round(time.Second))
		case "success":
			fmt.Println("Paired. Device name in WhatsApp:", deviceName)
			return nil
		case "timeout":
			return errors.New("pairing timed out, run again")
		case "error":
			return fmt.Errorf("pairing failed: %v", item.Error)
		default:
			return fmt.Errorf("pairing ended: %s", item.Event)
		}
	}
	return errors.New("pairing cancelled")
}

func logout(ctx context.Context, cli *whatsmeow.Client, db *sql.DB, sessionPath string) error {
	if cli.Store.ID != nil {
		fmt.Println("Connecting to unlink this device...")
		if err := cli.ConnectContext(ctx); err != nil {
			fmt.Println("Could not connect:", err, "- deleting local session anyway. Also unlink it on the phone.")
		} else {
			cli.WaitForConnection(15 * time.Second)
			if err := cli.Logout(ctx); err != nil {
				fmt.Println("Logout request failed:", err, "- deleting local session anyway. Also unlink it on the phone.")
			} else {
				fmt.Println("Device unlinked from WhatsApp.")
			}
			cli.Disconnect()
		}
	} else {
		fmt.Println("No paired device in the session.")
	}
	_ = db.Close()
	for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
		if err := os.Remove(sessionPath + suffix); err == nil {
			fmt.Println("Deleted", sessionPath+suffix)
		}
	}
	return nil
}

// probe holds the favorite sticker state built from app state mutations.
type probe struct {
	cli *whatsmeow.Client

	mu        sync.Mutex
	favorites map[string]*favorite // key: favoriteSticker index (index[1])

	snapshotDone atomic.Bool
	downloadOnce sync.Once
	syncedFirst  atomic.Bool
}

func newProbe(cli *whatsmeow.Client) *probe {
	return &probe{cli: cli, favorites: make(map[string]*favorite)}
}

func (p *probe) handle(rawEvt any) {
	switch evt := rawEvt.(type) {
	case *events.Connected:
		fmt.Println(stamp(), "Connected.")
	case *events.Disconnected:
		fmt.Println(stamp(), "Disconnected.")
	case *events.LoggedOut:
		fmt.Println(stamp(), "Logged out by the phone/server. Delete out/session.db or run --logout.")
	case *events.HistorySync:
		p.onHistorySync(evt)
	case *events.AppState:
		p.onAppState(evt)
	case *events.AppStateSyncComplete:
		if evt.Name == appstate.WAPatchRegularLow {
			p.onSnapshotComplete(evt.Version)
		}
	case *events.AppStateSyncError:
		if evt.Name == appstate.WAPatchRegularLow {
			fmt.Println(stamp(), "regular_low sync error:", evt.Error)
		}
	}
}

// initialSync fetches the full regular_low collection. Right after pairing the
// app state keys may not have arrived from the phone yet, so it retries.
func (p *probe) initialSync(ctx context.Context) {
	if !p.syncedFirst.CompareAndSwap(false, true) {
		return
	}
	if !p.cli.WaitForConnection(60 * time.Second) {
		fmt.Println(stamp(), "Not connected after 60s; is the phone/network OK?")
	}
	for attempt := 1; attempt <= 8 && !p.snapshotDone.Load(); attempt++ {
		fmt.Printf("%s Fetching full app state %q (attempt %d)...\n", stamp(), appstate.WAPatchRegularLow, attempt)
		err := p.cli.FetchAppState(ctx, appstate.WAPatchRegularLow, true, false)
		if err == nil || p.snapshotDone.Load() {
			return
		}
		fmt.Println(stamp(), "Full sync failed:", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}

func (p *probe) onSnapshotComplete(version uint64) {
	if !p.snapshotDone.CompareAndSwap(false, true) {
		return
	}
	fmt.Printf("%s Full sync of regular_low complete (version %d).\n", stamp(), version)
	p.printTable()
	fmt.Println(stamp(), "Watching for live favoriteSticker changes. Press Ctrl+C to quit.")
	if *flagDownload {
		p.downloadOnce.Do(func() { go p.downloadAll() })
	}
}

func stamp() string { return time.Now().Format("15:04:05.000") }
