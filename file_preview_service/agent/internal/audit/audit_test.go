package audit

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestRecordWritesJSONLine verifies one event is appended as one JSONL record
// carrying the full field set.
func TestRecordWritesJSONLine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.log")
	l, err := New(path, 50, 10, 15)
	if err != nil {
		t.Fatal(err)
	}
	// Release the file handle so Windows TempDir cleanup can remove it.
	defer l.Close()
	l.Record(Event{
		RequestID:     "req-1",
		CallerSubject: "CN=file-preview-main",
		CallerIP:      "10.10.0.5",
		UserID:        "u1",
		Username:      "admin",
		Operation:     "preview",
		Path:          "/data/logs/app.log",
		RealPath:      "/data/logs/app.log",
		Success:       true,
		BytesReturned: 42,
		DurationMs:    3,
	})

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 1 {
		t.Fatalf("expected exactly 1 JSONL line, got %d", len(lines))
	}
	var got map[string]interface{}
	if err := json.Unmarshal([]byte(lines[0]), &got); err != nil {
		t.Fatalf("line is not valid JSON: %v", err)
	}
	for _, key := range []string{
		"time", "requestId", "callerSubject", "callerIp", "userId", "username",
		"operation", "path", "realPath", "success", "bytesReturned", "durationMs",
	} {
		if _, ok := got[key]; !ok {
			t.Errorf("missing field %q in %v", key, got)
		}
	}
	if got["time"] == "" {
		t.Error("time should be auto-filled when empty")
	}
	if got["requestId"] != "req-1" || got["operation"] != "preview" ||
		got["path"] != "/data/logs/app.log" || got["success"] != true {
		t.Errorf("unexpected field values: %v", got)
	}
}

// TestRotationCreatesBackup drives size-based rotation with a tiny cap (built
// directly rather than via New, whose maxSizeMb granularity is whole MBs).
func TestRotationCreatesBackup(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.log")
	l := &Logger{
		path:    path,
		rotates: true,
		opts:    Options{MaxSizeBytes: 200, MaxBackups: 10},
	}
	if err := l.openFile(); err != nil {
		t.Fatal(err)
	}
	defer l.Close() // release the file handle for Windows TempDir cleanup
	for i := 0; i < 5; i++ {
		l.Record(Event{
			RequestID: fmt.Sprintf("req-%d", i),
			Operation: "preview",
			Path:      "/data/logs/app.log",
			Success:   true,
		})
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	backups := 0
	for _, de := range entries {
		if strings.HasPrefix(de.Name(), "audit.log.") {
			backups++
		}
	}
	if backups == 0 {
		t.Fatal("expected at least one rotated backup file")
	}
	// The active file must still hold valid JSONL after rotation.
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		var v map[string]interface{}
		if err := json.Unmarshal([]byte(line), &v); err != nil {
			t.Fatalf("active log contains invalid JSON line %q: %v", line, err)
		}
	}
}

// TestPruneKeepsOnlyMaxBackups seeds rotated files and checks count retention.
func TestPruneKeepsOnlyMaxBackups(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.log")
	if err := os.WriteFile(path, []byte("{}\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	// Distinct mod times keep the newest-first sort deterministic.
	names := []string{"audit.log.20200101-000001", "audit.log.20200102-000001", "audit.log.20200103-000001"}
	for i, name := range names {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte("old\n"), 0o640); err != nil {
			t.Fatal(err)
		}
		mt := time.Now().Add(time.Duration(-i) * time.Hour) // names[0] newest
		if err := os.Chtimes(p, mt, mt); err != nil {
			t.Fatal(err)
		}
	}

	l := &Logger{path: path, opts: Options{MaxBackups: 2}}
	l.prune()

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var remaining []string
	for _, de := range entries {
		if strings.HasPrefix(de.Name(), "audit.log.") {
			remaining = append(remaining, de.Name())
		}
	}
	if len(remaining) != 2 {
		t.Fatalf("expected 2 backups kept, got %v", remaining)
	}
	for _, r := range remaining {
		if r == names[2] {
			t.Fatalf("oldest backup %s should have been pruned", r)
		}
	}
}

// TestRotationBackupsAreUnique forces several rotations in quick succession
// (typically within the same second): each must produce its own backup file
// instead of overwriting the previous one.
func TestRotationBackupsAreUnique(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "audit.log")
	l := &Logger{
		path:    path,
		rotates: true,
		opts:    Options{MaxSizeBytes: 120, MaxBackups: 100},
	}
	if err := l.openFile(); err != nil {
		t.Fatal(err)
	}
	defer l.Close() // release the file handle for Windows TempDir cleanup
	for i := 0; i < 6; i++ {
		l.Record(Event{
			RequestID: fmt.Sprintf("req-%d", i),
			Operation: "preview",
			Path:      "/data/logs/app.log",
			Success:   true,
		})
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	backups := 0
	for _, de := range entries {
		if strings.HasPrefix(de.Name(), "audit.log.") {
			backups++
		}
	}
	// Every record exceeds the 120-byte cap, so all 6 writes rotate; even if a
	// couple of rotations land in different seconds there must be several.
	if backups < 3 {
		t.Fatalf("expected >=3 distinct backup files, got %d (same-second overwrite?)", backups)
	}
}
