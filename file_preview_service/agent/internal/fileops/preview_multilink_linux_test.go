//go:build linux

package fileops

import (
	"os"
	"path/filepath"
	"testing"
)

// The hard-link count comes from syscall.Stat_t.Nlink, which only exists on
// Linux (the Agent's target platform); the non-Linux nlink stub always
// returns 1, so RejectMultiLink can only be exercised on Linux.
func setupMultiLink(t *testing.T) (orig, link string) {
	t.Helper()
	root := t.TempDir()
	orig = filepath.Join(root, "original.log")
	if err := os.WriteFile(orig, []byte("hello\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	link = filepath.Join(root, "hardlink.log")
	if err := os.Link(orig, link); err != nil {
		t.Fatal(err)
	}
	return orig, link
}

func TestPreviewRejectsMultiLinkedFileWhenEnabled(t *testing.T) {
	orig, link := setupMultiLink(t)
	guard := NewPathGuard([]string{filepath.Dir(orig)}, nil)
	svc := NewService(guard, Limits{RejectMultiLink: true})

	_, err := svc.Preview(PreviewRequest{Path: link})
	if err == nil {
		t.Fatal("expected multi-linked file to be rejected")
	}
	pe, ok := err.(*PathError)
	if !ok || pe.Code != "MULTI_LINK" {
		t.Fatalf("expected MULTI_LINK, got %v", err)
	}
}

func TestPreviewAllowsMultiLinkedFileWhenDisabled(t *testing.T) {
	orig, link := setupMultiLink(t)
	guard := NewPathGuard([]string{filepath.Dir(orig)}, nil)
	svc := NewService(guard, Limits{RejectMultiLink: false})

	if _, err := svc.Preview(PreviewRequest{Path: link}); err != nil {
		t.Fatalf("expected preview to succeed, got %v", err)
	}
}
