package fileops

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newTestService(t *testing.T, limits Limits) (*Service, string) {
	t.Helper()
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "app.log"), []byte("line1\nline2\nline3\nline4\nline5\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".hidden"), []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "subdir"), 0o755); err != nil {
		t.Fatal(err)
	}
	// a binary file
	if err := os.WriteFile(filepath.Join(root, "bin.dat"), []byte{0x00, 0x01, 0x02, 0x00}, 0o644); err != nil {
		t.Fatal(err)
	}
	guard := NewPathGuard([]string{root}, nil)
	return NewService(guard, limits), root
}

func TestListHidesDotFilesByDefault(t *testing.T) {
	svc, root := newTestService(t, Limits{})
	resp, err := svc.List(ListRequest{Path: root})
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range resp.Entries {
		if strings.HasPrefix(e.Name, ".") {
			t.Fatalf("hidden file leaked: %s", e.Name)
		}
	}
	// directories should sort first
	if resp.Entries[0].Type != "dir" {
		t.Fatalf("expected directory first, got %s", resp.Entries[0].Type)
	}
}

func TestListShowsHiddenWhenRequested(t *testing.T) {
	svc, root := newTestService(t, Limits{})
	resp, err := svc.List(ListRequest{Path: root, Options: ListOptions{ShowHidden: true}})
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, e := range resp.Entries {
		if e.Name == ".hidden" {
			found = true
		}
	}
	if !found {
		t.Fatal("expected hidden file to be listed")
	}
}

func TestPreviewRespectsByteLimit(t *testing.T) {
	svc, root := newTestService(t, Limits{MaxPreviewBytes: 6})
	resp, err := svc.Preview(PreviewRequest{Path: filepath.Join(root, "app.log")})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if len(data) > 6 {
		t.Fatalf("expected <=6 bytes, got %d", len(data))
	}
	if !resp.Truncated {
		t.Fatal("expected truncated flag")
	}
}

func TestPreviewDetectsBinary(t *testing.T) {
	svc, root := newTestService(t, Limits{})
	resp, err := svc.Preview(PreviewRequest{Path: filepath.Join(root, "bin.dat")})
	if err != nil {
		t.Fatal(err)
	}
	if !resp.Binary {
		t.Fatal("expected binary detection")
	}
}

func TestTailReturnsLastLines(t *testing.T) {
	svc, root := newTestService(t, Limits{MaxTailLines: 5000, MaxPreviewBytes: 1 << 20})
	resp, err := svc.Tail(TailRequest{Path: filepath.Join(root, "app.log"), Options: TailOptions{Lines: 2}})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	text := string(data)
	if !strings.Contains(text, "line5") || strings.Contains(text, "line1") {
		t.Fatalf("expected last 2 lines only, got %q", text)
	}
}

func TestPreviewRejectsDirectory(t *testing.T) {
	svc, root := newTestService(t, Limits{})
	_, err := svc.Preview(PreviewRequest{Path: filepath.Join(root, "subdir")})
	if err == nil {
		t.Fatal("expected error previewing a directory")
	}
}

// Descending order must keep a strict weak ordering: entries with equal sort
// keys retain their input order under SliceStable, and directories stay first.
func TestSortEntriesDescStableForEqualKeys(t *testing.T) {
	entries := []Entry{
		{Name: "same1.txt", Type: "file", Size: 100},
		{Name: "aaa.txt", Type: "file", Size: 50},
		{Name: "same2.txt", Type: "file", Size: 100},
		{Name: "dir1", Type: "dir", Size: 0},
	}
	sortEntries(entries, "size", "desc") // must not panic on equal keys

	got := make([]string, len(entries))
	for i, e := range entries {
		got[i] = e.Name
	}
	want := []string{"dir1", "same1.txt", "same2.txt", "aaa.txt"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("desc size order = %v, want %v (ties keep input order)", got, want)
		}
	}
}

func TestSortEntriesDescByName(t *testing.T) {
	entries := []Entry{
		{Name: "b.txt", Type: "file"},
		{Name: "A.txt", Type: "file"}, // ASCII 'A' < 'b'
		{Name: "zdir", Type: "dir"},
	}
	sortEntries(entries, "name", "desc")

	got := make([]string, len(entries))
	for i, e := range entries {
		got[i] = e.Name
	}
	want := []string{"zdir", "b.txt", "A.txt"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("desc name order = %v, want %v", got, want)
		}
	}
}
