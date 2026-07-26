package fileops

import (
	"os"
	"path/filepath"
	"testing"
)

func setupTree(t *testing.T) (root string) {
	t.Helper()
	root = t.TempDir()
	// allowed root: <root>/allowed
	allowed := filepath.Join(root, "allowed")
	if err := os.MkdirAll(filepath.Join(allowed, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(allowed, "app.log"), []byte("hello\nworld\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	// a secret file outside the allowed root
	secretDir := filepath.Join(root, "secret")
	if err := os.MkdirAll(secretDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(secretDir, "shadow"), []byte("root:x"), 0o600); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestResolveAcceptsFileWithinRoot(t *testing.T) {
	root := setupTree(t)
	allowed := filepath.Join(root, "allowed")
	g := NewPathGuard([]string{allowed}, nil)

	real, err := g.Resolve(filepath.Join(allowed, "app.log"))
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if real == "" {
		t.Fatal("expected non-empty real path")
	}
}

func TestResolveRejectsTraversal(t *testing.T) {
	root := setupTree(t)
	allowed := filepath.Join(root, "allowed")
	g := NewPathGuard([]string{allowed}, nil)

	// allowed/../secret/shadow -> resolves outside allowed root
	_, err := g.Resolve(filepath.Join(allowed, "..", "secret", "shadow"))
	if err == nil {
		t.Fatal("expected traversal to be rejected")
	}
	pe := err.(*PathError)
	if pe.Code != "OUTSIDE_ALLOWED_ROOTS" {
		t.Fatalf("expected OUTSIDE_ALLOWED_ROOTS, got %s", pe.Code)
	}
}

func TestResolveRejectsSymlinkEscape(t *testing.T) {
	root := setupTree(t)
	allowed := filepath.Join(root, "allowed")
	secret := filepath.Join(root, "secret")
	// symlink inside allowed pointing to the secret dir outside allowed
	link := filepath.Join(allowed, "escape")
	if err := os.Symlink(secret, link); err != nil {
		t.Skipf("symlink not supported: %v", err)
	}
	g := NewPathGuard([]string{allowed}, nil)

	_, err := g.Resolve(filepath.Join(link, "shadow"))
	if err == nil {
		t.Fatal("expected symlink escape to be rejected")
	}
	pe := err.(*PathError)
	if pe.Code != "SYMLINK_ESCAPE" && pe.Code != "OUTSIDE_ALLOWED_ROOTS" {
		t.Fatalf("expected escape rejection, got %s", pe.Code)
	}
}

func TestResolveRejectsRelativeAndControlChars(t *testing.T) {
	g := NewPathGuard([]string{"/tmp"}, nil)
	if _, err := g.Resolve("relative/path"); err == nil {
		t.Fatal("expected relative path rejection")
	}
	if _, err := g.Resolve("/tmp/\x00evil"); err == nil {
		t.Fatal("expected control char rejection")
	}
	if _, err := g.Resolve(""); err == nil {
		t.Fatal("expected empty path rejection")
	}
}

func TestGlobMatching(t *testing.T) {
	cases := []struct {
		glob  string
		path  string
		match bool
	}{
		{"/etc/shadow", "/etc/shadow", true},
		{"/root", "/root/.bashrc", true},
		{"/home/*/.ssh/**", "/home/alice/.ssh/id_rsa", true},
		{"/**/id_rsa", "/opt/app/id_rsa", true},
		{"/**/*.key", "/data/tls/server.key", true},
		{"/proc", "/proc/1/status", true},
		{"/etc/shadow", "/etc/passwd", false},
		{"/home/*/.ssh/**", "/home/alice/documents/file", false},
	}
	for _, c := range cases {
		re := compileGlob(c.glob)
		if got := re.MatchString(c.path); got != c.match {
			t.Errorf("glob %q vs %q: got %v want %v", c.glob, c.path, got, c.match)
		}
	}
}

// A trailing slash in a deny entry must not silently disable it: requested
// paths are cleaned before matching, so "/tmp/root/" has to match the cleaned
// "/tmp/root" and its subtree.
func TestCompileGlobStripsTrailingSlash(t *testing.T) {
	re := compileGlob("/tmp/root/")
	cases := []struct {
		path  string
		match bool
	}{
		{"/tmp/root", true},
		{"/tmp/root/x", true},
		{"/tmp/rootx", false},
		{"/tmp/other", false},
	}
	for _, c := range cases {
		if got := re.MatchString(c.path); got != c.match {
			t.Errorf("glob %q vs %q: got %v want %v", "/tmp/root/", c.path, got, c.match)
		}
	}
}

// A bare name deny entry must match on a path segment boundary only: "/x/log"
// is denied, but "/home/user/catalog" must survive.
func TestNameGlobMatchesSegmentBoundary(t *testing.T) {
	g := NewPathGuard(nil, []string{"log"})
	cases := []struct {
		path   string
		denied bool
	}{
		{"/log", true},
		{"/x/log", true},
		{"/a/b/log", true},
		{"/a/b/log/deep", true},
		{"/home/user/catalog", false},
		{"/x/logs", false},
	}
	for _, c := range cases {
		if got := g.isDenied(c.path); got != c.denied {
			t.Errorf("isDenied(%q): got %v want %v", c.path, got, c.denied)
		}
	}
}
