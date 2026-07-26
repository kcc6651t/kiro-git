package fileops

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// PathError carries a stable code alongside a message so the HTTP layer can map
// it to a status and the central service can react uniformly.
type PathError struct {
	Code    string
	Message string
}

func (e *PathError) Error() string { return e.Message }

func newPathError(code, msg string) *PathError { return &PathError{Code: code, Message: msg} }

// PathGuard enforces the authoritative, server-local path policy. It is the last
// line of defence regardless of what the central service allowed.
type PathGuard struct {
	allowedRoots []string // normalized, symlink-resolved where possible
	deniedGlobs  []*regexp.Regexp
}

// NewPathGuard resolves allowed roots to their real paths so symlinked roots are
// compared correctly, and precompiles denied-path globs.
func NewPathGuard(allowedRoots, deniedPaths []string) *PathGuard {
	roots := make([]string, 0, len(allowedRoots))
	for _, r := range allowedRoots {
		clean := filepath.Clean(r)
		if resolved, err := filepath.EvalSymlinks(clean); err == nil {
			roots = append(roots, resolved)
		} else {
			roots = append(roots, clean)
		}
	}
	globs := make([]*regexp.Regexp, 0, len(deniedPaths))
	for _, d := range deniedPaths {
		globs = append(globs, compileGlob(d))
	}
	return &PathGuard{allowedRoots: roots, deniedGlobs: globs}
}

// Resolve validates a requested path and returns its real (symlink-resolved) path.
//
// Steps (mirroring the security requirements):
//  1. absolute, non-empty, no control characters
//  2. filepath.Clean
//  3. reject if the cleaned path matches a denied glob
//  4. cleaned path must be within an allowed root
//  5. EvalSymlinks to get the real path
//  6. real path must still be within an allowed root (symlink escape defence)
//  7. real path must not match a denied glob (hardlink/symlink target defence)
func (g *PathGuard) Resolve(reqPath string) (string, error) {
	if reqPath == "" {
		return "", newPathError("ILLEGAL_PATH", "path must not be empty")
	}
	if !filepath.IsAbs(reqPath) {
		return "", newPathError("ILLEGAL_PATH", "path must be absolute")
	}
	for _, r := range reqPath {
		if r < 0x20 || r == 0x7f {
			return "", newPathError("ILLEGAL_PATH", "path must not contain control characters")
		}
	}

	clean := filepath.Clean(reqPath)

	if g.isDenied(clean) {
		return "", newPathError("DENIED_PATH", "path is explicitly denied")
	}
	if !g.withinAllowed(clean) {
		return "", newPathError("OUTSIDE_ALLOWED_ROOTS", "path is outside allowed roots")
	}

	real, err := filepath.EvalSymlinks(clean)
	if err != nil {
		if os.IsNotExist(err) {
			return "", newPathError("NOT_FOUND", "path does not exist")
		}
		return "", newPathError("ILLEGAL_PATH", "cannot resolve path")
	}

	if !g.withinAllowed(real) {
		return "", newPathError("SYMLINK_ESCAPE", "resolved path escapes allowed roots")
	}
	if g.isDenied(real) {
		return "", newPathError("DENIED_PATH", "resolved path is explicitly denied")
	}
	return real, nil
}

// ResolveForWrite validates a target path for WRITE operations (the file may not
// exist yet, so EvalSymlinks is not applied to the leaf). It enforces absolute
// path, no control chars, cleaning, deniedPaths and allowedRoots. Callers must
// additionally verify the real (symlink-resolved) parent directory stays within
// an allowed root after creating it — use WithinAllowedReal for that.
func (g *PathGuard) ResolveForWrite(reqPath string) (string, error) {
	if reqPath == "" {
		return "", newPathError("ILLEGAL_PATH", "path must not be empty")
	}
	if !filepath.IsAbs(reqPath) {
		return "", newPathError("ILLEGAL_PATH", "path must be absolute")
	}
	for _, r := range reqPath {
		if r < 0x20 || r == 0x7f {
			return "", newPathError("ILLEGAL_PATH", "path must not contain control characters")
		}
	}
	clean := filepath.Clean(reqPath)
	if g.isDenied(clean) {
		return "", newPathError("DENIED_PATH", "path is explicitly denied")
	}
	if !g.withinAllowed(clean) {
		return "", newPathError("OUTSIDE_ALLOWED_ROOTS", "path is outside allowed roots")
	}
	return clean, nil
}

// WithinAllowedReal reports whether a (symlink-resolved) path is within an allowed
// root. Used to defend against a parent directory escaping via a symlink.
func (g *PathGuard) WithinAllowedReal(realPath string) bool {
	return g.withinAllowed(realPath)
}

func (g *PathGuard) withinAllowed(path string) bool {
	for _, root := range g.allowedRoots {
		if path == root || strings.HasPrefix(path, ensureSlash(root)) {
			return true
		}
	}
	return false
}

func (g *PathGuard) isDenied(path string) bool {
	// deniedPaths globs are POSIX (forward-slash); normalize the path so matching
	// is consistent. On Linux this is a no-op.
	slashPath := filepath.ToSlash(path)
	for _, re := range g.deniedGlobs {
		if re.MatchString(slashPath) {
			return true
		}
	}
	return false
}

func ensureSlash(p string) string {
	sep := string(filepath.Separator)
	if strings.HasSuffix(p, sep) {
		return p
	}
	return p + sep
}

// compileGlob converts a glob (supporting *, **, ?) into a regexp that also matches
// any descendant of a denied directory. Patterns without a leading slash are
// treated as matching anywhere (prefixed with **/).
func compileGlob(glob string) *regexp.Regexp {
	pattern := glob
	// Trailing slashes never match anything: requested paths pass through
	// filepath.Clean first, which strips them. Strip them here too so a deny
	// entry like "/root/" actually denies "/root" and its subtree.
	for len(pattern) > 1 && strings.HasSuffix(pattern, "/") {
		pattern = strings.TrimSuffix(pattern, "/")
	}
	if !strings.HasPrefix(pattern, "/") {
		pattern = "/**/" + pattern
	}
	body := globToRegex(pattern)
	// match the path itself or anything beneath it
	full := "^(" + body + ")(/.*)?$"
	return regexp.MustCompile(full)
}

func globToRegex(glob string) string {
	var sb strings.Builder
	i := 0
	for i < len(glob) {
		c := glob[i]
		switch c {
		case '*':
			if i+1 < len(glob) && glob[i+1] == '*' {
				if i+2 < len(glob) && glob[i+2] == '/' {
					// "**/" means "any number of directory levels, or none";
					// segment-aware so "log" cannot match "catalog".
					sb.WriteString("(.*/)?")
					i += 3
				} else {
					sb.WriteString(".*")
					i += 2
				}
				continue
			}
			sb.WriteString("[^/]*")
		case '?':
			sb.WriteString("[^/]")
		case '.', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\':
			sb.WriteByte('\\')
			sb.WriteByte(c)
		default:
			sb.WriteByte(c)
		}
		i++
	}
	return sb.String()
}

// fileKind classifies a path for read decisions.
func fileKind(info os.FileInfo) string {
	mode := info.Mode()
	switch {
	case mode.IsDir():
		return "dir"
	case mode.IsRegular():
		return "file"
	case mode&os.ModeSymlink != 0:
		return "symlink"
	default:
		return "special"
	}
}

// ensureRegularOrDir rejects special files (device, socket, pipe, etc.).
func ensureReadableType(info os.FileInfo) error {
	if info.IsDir() || info.Mode().IsRegular() {
		return nil
	}
	return newPathError("SPECIAL_FILE", fmt.Sprintf("refusing to read special file: %s", info.Mode().String()))
}
