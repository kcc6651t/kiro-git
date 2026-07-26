package fileops

import (
	"encoding/base64"
	"io"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"time"
)

// SyncLimits bounds sync operations.
type SyncLimits struct {
	MaxChunkBytes int
	MaxFiles      int
}

// SyncService implements primary/standby replication primitives.
//   - readGuard  guards SOURCE reads (scan/read), based on allowedRoots.
//   - writeGuard guards STANDBY writes, based on targetRoots (nil ⇒ writes disabled).
type SyncService struct {
	readGuard  *PathGuard
	writeGuard *PathGuard
	limits     SyncLimits
}

func NewSyncService(readGuard, writeGuard *PathGuard, limits SyncLimits) *SyncService {
	if limits.MaxChunkBytes <= 0 {
		limits.MaxChunkBytes = 4 * 1024 * 1024
	}
	if limits.MaxFiles <= 0 {
		limits.MaxFiles = 100000
	}
	return &SyncService{readGuard: readGuard, writeGuard: writeGuard, limits: limits}
}

// Scan enumerates matching files under a source directory.
func (s *SyncService) Scan(req SyncScanRequest) (*SyncScanResponse, error) {
	realDir, err := s.readGuard.Resolve(req.Dir)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(realDir)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "dir does not exist")
	}
	if !info.IsDir() {
		return nil, newPathError("NOT_A_DIRECTORY", "path is not a directory")
	}

	files := make([]SyncFile, 0, 64)
	truncated := false
	// Entries skipped due to walk/stat errors are tallied and reported once at
	// the end instead of failing the whole scan.
	walkErrs, statErrs := 0, 0
	visit := func(path string, d fs.DirEntry) error {
		if d.IsDir() {
			return nil
		}
		fi, e := d.Info()
		if e != nil {
			statErrs++
			return nil
		}
		if !fi.Mode().IsRegular() {
			return nil // skip symlinks / special files
		}
		name := d.Name()
		if !matchAny(req.Includes, name, true) {
			return nil
		}
		if matchAny(req.Excludes, name, false) {
			return nil
		}
		rel, e := filepath.Rel(realDir, path)
		if e != nil {
			return nil
		}
		uid, gid := ownerIds(fi)
		owner, group := ownerGroup(fi)
		files = append(files, SyncFile{
			RelPath:   filepath.ToSlash(rel),
			Size:      fi.Size(),
			Mode:      uint32(fi.Mode().Perm()),
			ModTimeMs: fi.ModTime().UnixMilli(),
			Uid:       uid,
			Gid:       gid,
			Owner:     owner,
			Group:     group,
		})
		if len(files) >= s.limits.MaxFiles {
			truncated = true
			return io.EOF // stop walking
		}
		return nil
	}

	if req.Recursive {
		werr := filepath.WalkDir(realDir, func(path string, d fs.DirEntry, e error) error {
			if e != nil {
				walkErrs++
				return nil
			}
			if d.IsDir() && path != realDir && dirExcluded(req.ExcludeDirs, realDir, path, d.Name()) {
				return filepath.SkipDir
			}
			return visit(path, d)
		})
		if werr != nil && werr != io.EOF {
			return nil, newPathError("READ_FAILED", "cannot scan directory")
		}
	} else {
		entries, e := os.ReadDir(realDir)
		if e != nil {
			return nil, newPathError("READ_FAILED", "cannot read directory")
		}
		for _, d := range entries {
			if verr := visit(filepath.Join(realDir, d.Name()), d); verr == io.EOF {
				break
			}
		}
	}

	if walkErrs+statErrs > 0 {
		log.Printf("sync scan %s: skipped %d entries with walk errors, %d with stat errors", realDir, walkErrs, statErrs)
	}

	return &SyncScanResponse{Dir: req.Dir, RealDir: realDir, Files: files, Truncated: truncated}, nil
}

// Read returns a byte range from a source file.
func (s *SyncService) Read(req SyncReadRequest) (*SyncReadResponse, error) {
	real, err := s.readGuard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	if !info.Mode().IsRegular() {
		return nil, newPathError("NOT_A_FILE", "path is not a regular file")
	}
	length := req.Length
	if length <= 0 || length > s.limits.MaxChunkBytes {
		length = s.limits.MaxChunkBytes
	}
	f, err := os.Open(real)
	if err != nil {
		return nil, newPathError("READ_FAILED", "cannot open file")
	}
	defer f.Close()
	buf := make([]byte, length)
	n, err := f.ReadAt(buf, req.Offset)
	if err != nil && err != io.EOF {
		return nil, newPathError("READ_FAILED", "cannot read file")
	}
	eof := req.Offset+int64(n) >= info.Size()
	return &SyncReadResponse{
		Path:          real,
		Offset:        req.Offset,
		Eof:           eof,
		ContentBase64: base64.StdEncoding.EncodeToString(buf[:n]),
	}, nil
}

// Write writes a chunk to a standby target file (via a temp file), finalizing on last.
func (s *SyncService) Write(req SyncWriteRequest) (*SyncWriteResponse, error) {
	if s.writeGuard == nil {
		return nil, newPathError("SYNC_WRITE_DISABLED", "sync write is not enabled on this agent")
	}
	clean, err := s.writeGuard.ResolveForWrite(req.Path)
	if err != nil {
		return nil, err
	}
	data, derr := base64.StdEncoding.DecodeString(req.ContentBase64)
	if derr != nil {
		return nil, NewBadRequest("invalid content encoding")
	}
	if len(data) > s.limits.MaxChunkBytes {
		return nil, NewBadRequest("chunk exceeds max size")
	}

	dir := filepath.Dir(clean)
	tmp := clean + ".fpsync.tmp"

	if req.Offset == 0 {
		if e := os.MkdirAll(dir, 0o750); e != nil {
			return nil, newPathError("WRITE_FAILED", "cannot create target directory")
		}
		// Defend against a parent directory that escapes allowed roots via symlink.
		if realDir, e := filepath.EvalSymlinks(dir); e == nil {
			if !s.writeGuard.WithinAllowedReal(realDir) {
				return nil, newPathError("SYMLINK_ESCAPE", "target directory escapes allowed roots")
			}
		}
		// Fresh temp file.
		_ = os.Remove(tmp)
	}

	f, e := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY, 0o640)
	if e != nil {
		return nil, newPathError("WRITE_FAILED", "cannot open temp file")
	}
	if _, e := f.WriteAt(data, req.Offset); e != nil {
		f.Close()
		return nil, newPathError("WRITE_FAILED", "cannot write temp file")
	}
	if e := f.Close(); e != nil {
		return nil, newPathError("WRITE_FAILED", "cannot flush temp file")
	}

	resp := &SyncWriteResponse{Path: clean, BytesWritten: len(data)}
	if !req.Last {
		return resp, nil
	}

	// Finalize: permissions, mtime, ownership, atomic rename.
	if req.PreservePermissions && req.Mode != 0 {
		if e := os.Chmod(tmp, os.FileMode(req.Mode).Perm()); e == nil {
			resp.PermissionsSet = true
		}
	}
	if req.ModTimeMs > 0 {
		mt := time.UnixMilli(req.ModTimeMs)
		_ = os.Chtimes(tmp, mt, mt)
	}
	if req.PreserveOwnership && req.Uid >= 0 && req.Gid >= 0 {
		if e := os.Chown(tmp, req.Uid, req.Gid); e != nil {
			resp.OwnershipError = e.Error()
		} else {
			resp.OwnershipSet = true
		}
	}
	if e := os.Rename(tmp, clean); e != nil {
		_ = os.Remove(tmp)
		return nil, newPathError("WRITE_FAILED", "cannot finalize target file")
	}
	resp.Finalized = true
	return resp, nil
}

// matchAny reports whether name matches any glob in patterns. When patterns is
// empty, returns emptyResult (includes: true=match-all; excludes: false=none).
func matchAny(patterns []string, name string, emptyResult bool) bool {
	if len(patterns) == 0 {
		return emptyResult
	}
	for _, p := range patterns {
		if ok, err := filepath.Match(p, name); err == nil && ok {
			return true
		}
	}
	return false
}

// dirExcluded reports whether a directory should be pruned from a recursive
// walk. A pattern matches when it hits the directory base name ("cache") or
// the slash-separated path relative to root ("logs/archive").
func dirExcluded(patterns []string, root, dir, name string) bool {
	if len(patterns) == 0 {
		return false
	}
	rel, err := filepath.Rel(root, dir)
	if err != nil {
		return false
	}
	relSlash := filepath.ToSlash(rel)
	for _, p := range patterns {
		if ok, err := filepath.Match(p, name); err == nil && ok {
			return true
		}
		if ok, err := path.Match(p, relSlash); err == nil && ok {
			return true
		}
	}
	return false
}
