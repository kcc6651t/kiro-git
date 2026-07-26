package audit

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Event is the server-local audit record. It never contains file content.
type Event struct {
	Time          string `json:"time"`
	RequestID     string `json:"requestId"`
	CallerSubject string `json:"callerSubject"`
	CallerIP      string `json:"callerIp"`
	UserID        string `json:"userId"`
	Username      string `json:"username"`
	Operation     string `json:"operation"`
	Path          string `json:"path"`
	RealPath      string `json:"realPath"`
	Success       bool   `json:"success"`
	DenyReason    string `json:"denyReason,omitempty"`
	BytesReturned int64  `json:"bytesReturned"`
	DurationMs    int64  `json:"durationMs"`
}

// Options controls rotation/retention.
type Options struct {
	MaxSizeBytes int64
	MaxBackups   int
	MaxAgeDays   int
}

// Logger writes structured JSON audit events, one per line, with size-based
// rotation and age/count-based retention to bound disk usage.
type Logger struct {
	mu      sync.Mutex
	out     io.Writer
	path    string
	size    int64
	opts    Options
	rotates bool
}

// New returns a Logger. When path is empty it writes to stderr (journald).
func New(path string, maxSizeMb, maxBackups, maxAgeDays int) (*Logger, error) {
	if path == "" {
		return &Logger{out: os.Stderr}, nil
	}
	if dir := filepath.Dir(path); dir != "" {
		_ = os.MkdirAll(dir, 0o750)
	}
	l := &Logger{
		path:    path,
		rotates: true,
		opts: Options{
			MaxSizeBytes: int64(maxSizeMb) * 1024 * 1024,
			MaxBackups:   maxBackups,
			MaxAgeDays:   maxAgeDays,
		},
	}
	if err := l.openFile(); err != nil {
		return nil, err
	}
	return l, nil
}

func (l *Logger) openFile() error {
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
	if err != nil {
		return err
	}
	if info, e := f.Stat(); e == nil {
		l.size = info.Size()
	}
	l.out = f
	return nil
}

// Record serializes and writes an event. Errors are logged but never propagated.
func (l *Logger) Record(e Event) {
	if e.Time == "" {
		e.Time = time.Now().Format(time.RFC3339)
	}
	data, err := json.Marshal(e)
	if err != nil {
		log.Printf("audit marshal error: %v", err)
		return
	}
	data = append(data, '\n')

	l.mu.Lock()
	defer l.mu.Unlock()

	if l.rotates && l.opts.MaxSizeBytes > 0 && l.size+int64(len(data)) > l.opts.MaxSizeBytes {
		l.rotate()
	}
	n, werr := l.out.Write(data)
	if werr != nil {
		log.Printf("audit write error: %v", werr)
		return
	}
	l.size += int64(n)
}

// rotate closes the current file, renames it with a timestamp, reopens a fresh
// file and prunes old backups. Best-effort: failures fall back to current file.
func (l *Logger) rotate() {
	if f, ok := l.out.(*os.File); ok {
		_ = f.Close()
	}
	// Nanosecond suffix keeps backup names unique even when two rotations
	// happen within the same second (a plain second stamp would overwrite).
	// Retention still works: prune() matches backups by the "path." prefix.
	ts := time.Now().Format("20060102-150405.000000000")
	rotated := fmt.Sprintf("%s.%s", l.path, ts)
	if err := os.Rename(l.path, rotated); err != nil {
		log.Printf("audit rotate rename error: %v", err)
	}
	if err := l.openFile(); err != nil {
		log.Printf("audit reopen error: %v", err)
		l.out = os.Stderr
		l.rotates = false
		return
	}
	l.size = 0
	l.prune()
}

// prune deletes rotated files exceeding MaxBackups or older than MaxAgeDays.
func (l *Logger) prune() {
	dir := filepath.Dir(l.path)
	base := filepath.Base(l.path)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	type backup struct {
		name string
		mod  time.Time
	}
	var backups []backup
	for _, de := range entries {
		name := de.Name()
		if name == base || !strings.HasPrefix(name, base+".") {
			continue
		}
		info, e := de.Info()
		if e != nil {
			continue
		}
		backups = append(backups, backup{name: name, mod: info.ModTime()})
	}
	// newest first
	sort.Slice(backups, func(i, j int) bool { return backups[i].mod.After(backups[j].mod) })

	cutoff := time.Now().AddDate(0, 0, -l.opts.MaxAgeDays)
	for i, b := range backups {
		remove := false
		if l.opts.MaxBackups > 0 && i >= l.opts.MaxBackups {
			remove = true
		}
		if l.opts.MaxAgeDays > 0 && b.mod.Before(cutoff) {
			remove = true
		}
		if remove {
			_ = os.Remove(filepath.Join(dir, b.name))
		}
	}
}

// Close releases the underlying log file. It is a no-op for the stderr logger.
func (l *Logger) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if f, ok := l.out.(*os.File); ok {
		err := f.Close()
		l.out = os.Stderr
		l.rotates = false
		return err
	}
	return nil
}
