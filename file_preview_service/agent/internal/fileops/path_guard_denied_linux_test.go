//go:build linux

package fileops

import (
	"path/filepath"
	"testing"
)

// Denied-path enforcement uses POSIX-style globs against absolute POSIX paths, so
// this end-to-end filesystem test only makes sense on Linux (the Agent's target).
func TestResolveRejectsDeniedPath(t *testing.T) {
	root := setupTree(t)
	allowed := filepath.Join(root, "allowed")
	// deny *.log anywhere
	g := NewPathGuard([]string{allowed}, []string{"/**/*.log"})

	_, err := g.Resolve(filepath.Join(allowed, "app.log"))
	if err == nil {
		t.Fatal("expected denied path to be rejected")
	}
	if err.(*PathError).Code != "DENIED_PATH" {
		t.Fatalf("expected DENIED_PATH, got %s", err.(*PathError).Code)
	}
}
