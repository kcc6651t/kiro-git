package fileops

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

func newSyncEnv(t *testing.T) (src string, dst string) {
	t.Helper()
	src = t.TempDir()
	dst = t.TempDir()
	os.WriteFile(filepath.Join(src, "a.log"), []byte("alpha\n"), 0o640)
	os.WriteFile(filepath.Join(src, "b.txt"), []byte("beta\n"), 0o644)
	os.MkdirAll(filepath.Join(src, "sub"), 0o755)
	os.WriteFile(filepath.Join(src, "sub", "c.log"), []byte("charlie\n"), 0o600)
	return src, dst
}

func TestSyncScanGlobRecursive(t *testing.T) {
	src, dst := newSyncEnv(t)
	svc := NewSyncService(NewPathGuard([]string{src}, nil), NewPathGuard([]string{dst}, nil), SyncLimits{})

	resp, err := svc.Scan(SyncScanRequest{Dir: src, Includes: []string{"*.log"}, Recursive: true})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Files) != 2 {
		t.Fatalf("expected 2 .log files recursively, got %d", len(resp.Files))
	}
}

func TestSyncScanNonRecursiveAndExcludes(t *testing.T) {
	src, dst := newSyncEnv(t)
	svc := NewSyncService(NewPathGuard([]string{src}, nil), NewPathGuard([]string{dst}, nil), SyncLimits{})

	resp, err := svc.Scan(SyncScanRequest{Dir: src, Includes: []string{"*"}, Excludes: []string{"*.txt"}, Recursive: false})
	if err != nil {
		t.Fatal(err)
	}
	// top-level only: a.log, b.txt(excluded) -> just a.log
	if len(resp.Files) != 1 || resp.Files[0].RelPath != "a.log" {
		t.Fatalf("expected only a.log, got %+v", resp.Files)
	}
}

func TestSyncReadChunk(t *testing.T) {
	src, dst := newSyncEnv(t)
	svc := NewSyncService(NewPathGuard([]string{src}, nil), NewPathGuard([]string{dst}, nil), SyncLimits{MaxChunkBytes: 3})

	resp, err := svc.Read(SyncReadRequest{Path: filepath.Join(src, "a.log"), Offset: 0, Length: 3})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if string(data) != "alp" {
		t.Fatalf("expected 'alp', got %q", string(data))
	}
	if resp.Eof {
		t.Fatal("did not expect EOF at offset 0 len 3 of a 6-byte file")
	}
}

func TestSyncWriteChunkedAndFinalize(t *testing.T) {
	src, dst := newSyncEnv(t)
	svc := NewSyncService(NewPathGuard([]string{src}, nil), NewPathGuard([]string{dst}, nil), SyncLimits{MaxChunkBytes: 1024})

	target := filepath.Join(dst, "sub", "out.log")
	part1 := []byte("hello ")
	part2 := []byte("world\n")

	if _, err := svc.Write(SyncWriteRequest{
		Path: target, Offset: 0, ContentBase64: base64.StdEncoding.EncodeToString(part1), Last: false,
	}); err != nil {
		t.Fatal(err)
	}
	resp, err := svc.Write(SyncWriteRequest{
		Path: target, Offset: int64(len(part1)), ContentBase64: base64.StdEncoding.EncodeToString(part2),
		Last: true, Mode: 0o640, PreservePermissions: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !resp.Finalized {
		t.Fatal("expected finalized=true on last chunk")
	}
	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("target not written: %v", err)
	}
	if string(got) != "hello world\n" {
		t.Fatalf("unexpected content: %q", string(got))
	}
	if _, err := os.Stat(target + ".fpsync.tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file should be removed after finalize")
	}
}

// excludeEnv builds a tree with directories at several depths:
//
//	keep/a.log
//	cache/b.log
//	nested/cache/c.log
//	logs/d.log
//	logs/archive/e.log
//	archive/f.log
//	tmp1/g.log
func excludeEnv(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	for _, rel := range []string{
		"keep/a.log",
		"cache/b.log",
		"nested/cache/c.log",
		"logs/d.log",
		"logs/archive/e.log",
		"archive/f.log",
		"tmp1/g.log",
	} {
		p := filepath.Join(root, filepath.FromSlash(rel))
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte("x\n"), 0o640)
	}
	return root
}

func scanAll(t *testing.T, root string, excludeDirs []string) []string {
	t.Helper()
	svc := NewSyncService(NewPathGuard([]string{root}, nil), nil, SyncLimits{})
	resp, err := svc.Scan(SyncScanRequest{
		Dir: root, Includes: []string{"*"}, ExcludeDirs: excludeDirs, Recursive: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, 0, len(resp.Files))
	for _, f := range resp.Files {
		names = append(names, f.RelPath)
	}
	return names
}

func TestSyncScanExcludeDirsByName(t *testing.T) {
	root := excludeEnv(t)
	got := scanAll(t, root, []string{"cache"})
	// "cache" pruned at any depth; everything else kept.
	want := map[string]bool{
		"keep/a.log": true, "logs/d.log": true, "logs/archive/e.log": true,
		"archive/f.log": true, "tmp1/g.log": true,
	}
	if len(got) != len(want) {
		t.Fatalf("expected %d files, got %v", len(want), got)
	}
	for _, n := range got {
		if !want[n] {
			t.Fatalf("unexpected file in scan: %s", n)
		}
	}
}

func TestSyncScanExcludeDirsByRelPath(t *testing.T) {
	root := excludeEnv(t)
	got := scanAll(t, root, []string{"logs/archive"})
	// only that subtree pruned; top-level "archive" survives.
	for _, n := range got {
		if n == "logs/archive/e.log" {
			t.Fatalf("logs/archive should have been pruned, got %v", got)
		}
	}
	found := false
	for _, n := range got {
		if n == "archive/f.log" {
			found = true
		}
	}
	if !found {
		t.Fatalf("top-level archive should not be pruned, got %v", got)
	}
}

func TestSyncScanExcludeDirsGlob(t *testing.T) {
	root := excludeEnv(t)
	got := scanAll(t, root, []string{"tmp*"})
	for _, n := range got {
		if n == "tmp1/g.log" {
			t.Fatalf("tmp1 should have been pruned by glob, got %v", got)
		}
	}
}

func TestSyncWriteRejectedWhenDisabled(t *testing.T) {
	src, _ := newSyncEnv(t)
	// writeGuard nil -> writes disabled
	svc := NewSyncService(NewPathGuard([]string{src}, nil), nil, SyncLimits{})
	_, err := svc.Write(SyncWriteRequest{Path: filepath.Join(src, "x"), ContentBase64: "", Last: true})
	if err == nil || err.(*PathError).Code != "SYNC_WRITE_DISABLED" {
		t.Fatalf("expected SYNC_WRITE_DISABLED, got %v", err)
	}
}

func TestSyncWriteOutsideTargetRoots(t *testing.T) {
	src, dst := newSyncEnv(t)
	svc := NewSyncService(NewPathGuard([]string{src}, nil), NewPathGuard([]string{dst}, nil), SyncLimits{})
	outside := filepath.Join(src, "evil.log") // src is not a target root
	_, err := svc.Write(SyncWriteRequest{
		Path: outside, Offset: 0, ContentBase64: base64.StdEncoding.EncodeToString([]byte("x")), Last: true,
	})
	if err == nil || err.(*PathError).Code != "OUTSIDE_ALLOWED_ROOTS" {
		t.Fatalf("expected OUTSIDE_ALLOWED_ROOTS, got %v", err)
	}
}
