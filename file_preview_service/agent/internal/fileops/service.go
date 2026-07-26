package fileops

import (
	"bytes"
	"encoding/base64"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Limits captures the configured read caps applied by the service.
type Limits struct {
	MaxDirectoryEntries    int
	MaxPreviewBytes        int
	MaxPreviewLines        int
	MaxTailLines           int
	MaxSearchResults       int
	MaxSearchScanBytes     int64
	ShowHiddenFilesDefault bool
	// RejectMultiLink, when true, refuses to preview regular files with nlink > 1
	// (defence against hardlinked sensitive files in high-sensitivity setups).
	RejectMultiLink bool
}

// Service implements read-only file operations behind the path guard.
type Service struct {
	guard  *PathGuard
	limits Limits
}

func NewService(guard *PathGuard, limits Limits) *Service {
	if limits.MaxDirectoryEntries <= 0 {
		limits.MaxDirectoryEntries = 1000
	}
	if limits.MaxPreviewBytes <= 0 {
		limits.MaxPreviewBytes = 5 * 1024 * 1024
	}
	if limits.MaxPreviewLines <= 0 {
		limits.MaxPreviewLines = 5000
	}
	if limits.MaxTailLines <= 0 {
		limits.MaxTailLines = 5000
	}
	if limits.MaxSearchResults <= 0 {
		limits.MaxSearchResults = 200
	}
	if limits.MaxSearchScanBytes <= 0 {
		limits.MaxSearchScanBytes = 64 * 1024 * 1024
	}
	return &Service{guard: guard, limits: limits}
}

// List returns directory entries under path.
func (s *Service) List(req ListRequest) (*ListResponse, error) {
	real, err := s.guard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	if !info.IsDir() {
		return nil, newPathError("NOT_A_DIRECTORY", "path is not a directory")
	}

	dirEntries, err := os.ReadDir(real)
	if err != nil {
		return nil, newPathError("READ_FAILED", "cannot read directory")
	}

	showHidden := req.Options.ShowHidden || s.limits.ShowHiddenFilesDefault
	entries := make([]Entry, 0, len(dirEntries))
	for _, de := range dirEntries {
		name := de.Name()
		if !showHidden && strings.HasPrefix(name, ".") {
			continue
		}
		fi, statErr := de.Info()
		if statErr != nil {
			continue
		}
		entries = append(entries, s.toEntry(filepath.Join(real, name), name, fi))
	}

	sortEntries(entries, req.Options.SortBy, req.Options.SortOrder)

	total := len(entries)
	offset := req.Options.Offset
	if offset < 0 {
		offset = 0
	}
	limit := req.Options.Limit
	if limit <= 0 || limit > s.limits.MaxDirectoryEntries {
		limit = s.limits.MaxDirectoryEntries
	}
	hasMore := false
	if offset < total {
		end := offset + limit
		if end < total {
			hasMore = true
		} else {
			end = total
		}
		entries = entries[offset:end]
	} else {
		entries = []Entry{}
	}

	return &ListResponse{
		Path:     req.Path,
		RealPath: real,
		Entries:  entries,
		HasMore:  hasMore,
	}, nil
}

// Meta returns metadata for a single path.
func (s *Service) Meta(req MetaRequest) (*MetaResponse, error) {
	real, err := s.guard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	owner, group := ownerGroup(info)
	kind := fileKind(info)
	mime := ""
	if info.Mode().IsRegular() {
		mime = detectMime(real)
	}
	return &MetaResponse{
		Path:        req.Path,
		RealPath:    real,
		Type:        kind,
		Size:        info.Size(),
		Mode:        info.Mode().String(),
		Owner:       owner,
		Group:       group,
		ModifiedAt:  info.ModTime().Format(time.RFC3339),
		MimeType:    mime,
		Readable:    true,
		Previewable: info.Mode().IsRegular() && isTextMime(mime),
	}, nil
}

// Preview reads up to the configured byte cap from the start (or a range) of a file.
func (s *Service) Preview(req PreviewRequest) (*PreviewResponse, error) {
	real, err := s.guard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	if err := ensureReadableType(info); err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, newPathError("NOT_A_FILE", "path is not a regular file")
	}
	if s.limits.RejectMultiLink && nlink(info) > 1 {
		return nil, newPathError("MULTI_LINK", "refusing to read multiply-linked file")
	}

	byteCap := req.Options.LimitBytes
	if byteCap <= 0 || byteCap > s.limits.MaxPreviewBytes {
		byteCap = s.limits.MaxPreviewBytes
	}

	f, err := os.Open(real)
	if err != nil {
		return nil, newPathError("READ_FAILED", "cannot open file")
	}
	defer f.Close()

	mime := detectMime(real)
	resp := &PreviewResponse{
		Path:     req.Path,
		RealPath: real,
		MimeType: mime,
		Encoding: firstNonEmpty(req.Options.Encoding, "UTF-8"),
		Size:     info.Size(),
	}

	if req.Options.Mode == "range" {
		// Byte-window read (used for paging deeper into a file).
		data, truncated, err := readByteRange(f, req.Options.Offset, byteCap, info.Size())
		if err != nil {
			return nil, err
		}
		resp.Binary = looksBinary(data)
		resp.Truncated = truncated
		resp.BytesReturned = len(data)
		resp.StartOffset = req.Options.Offset
		resp.ContentBase64 = base64.StdEncoding.EncodeToString(data)
		return resp, nil
	}

	if req.Options.Mode == "lines" {
		// Line-window read (used to jump straight to a search hit's context).
		maxLines := req.Options.MaxLines
		if maxLines <= 0 || maxLines > s.limits.MaxPreviewLines {
			maxLines = s.limits.MaxPreviewLines
		}
		data, startLine, startOffset, lines, truncated, err := readLinesWindow(f, req.Options.FromLine, maxLines, byteCap)
		if err != nil {
			return nil, err
		}
		resp.Binary = looksBinary(data)
		resp.Truncated = truncated
		resp.ReturnedLines = lines
		resp.BytesReturned = len(data)
		resp.StartLine = startLine
		resp.StartOffset = startOffset
		resp.ContentBase64 = base64.StdEncoding.EncodeToString(data)
		return resp, nil
	}

	// Default "head" mode: stream the first N lines without loading the whole file.
	maxLines := req.Options.MaxLines
	if maxLines <= 0 || maxLines > s.limits.MaxPreviewLines {
		maxLines = s.limits.MaxPreviewLines
	}
	data, lines, truncated, binary, err := readHeadLines(f, maxLines, byteCap)
	if err != nil {
		return nil, err
	}
	resp.Binary = binary
	resp.StartLine = 1
	resp.Truncated = truncated
	resp.ReturnedLines = lines
	resp.BytesReturned = len(data)
	resp.ContentBase64 = base64.StdEncoding.EncodeToString(data)
	return resp, nil
}

// Search scans a file line-by-line for a substring, bounded by result count and
// scanned bytes so a GB-scale file cannot exhaust CPU or memory.
func (s *Service) Search(req SearchRequest) (*SearchResponse, error) {
	real, err := s.guard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	if err := ensureReadableType(info); err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, newPathError("NOT_A_FILE", "path is not a regular file")
	}
	// 文本模式按 UTF-8 查询串匹配；queryBase64 模式按原始字节匹配（用于 GBK 等
	// 非 UTF-8 编码文件，由中心把查询串编码成文件字符集后下发）。
	needle := []byte(req.Options.Query)
	rawMode := false
	if req.Options.QueryBase64 != "" {
		raw, derr := base64.StdEncoding.DecodeString(req.Options.QueryBase64)
		if derr != nil || len(raw) == 0 {
			return nil, NewBadRequest("invalid queryBase64")
		}
		needle = raw
		rawMode = true
	} else if req.Options.Query == "" {
		return nil, NewBadRequest("query must not be empty")
	}

	maxResults := req.Options.MaxResults
	if maxResults <= 0 || maxResults > s.limits.MaxSearchResults {
		maxResults = s.limits.MaxSearchResults
	}
	maxScan := req.Options.MaxScanBytes
	if maxScan <= 0 || maxScan > s.limits.MaxSearchScanBytes {
		maxScan = s.limits.MaxSearchScanBytes
	}

	matches, scanned, truncated, err := searchFile(real, needle, req.Options.CaseSensitive, rawMode, maxResults, maxScan)
	if err != nil {
		return nil, newPathError("READ_FAILED", "cannot read file")
	}
	return &SearchResponse{
		Path:         req.Path,
		RealPath:     real,
		Matches:      matches,
		Truncated:    truncated,
		ScannedBytes: scanned,
	}, nil
}

// Tail returns the last N lines of a file.
func (s *Service) Tail(req TailRequest) (*TailResponse, error) {
	real, err := s.guard.Resolve(req.Path)
	if err != nil {
		return nil, err
	}
	info, err := os.Lstat(real)
	if err != nil {
		return nil, newPathError("NOT_FOUND", "path does not exist")
	}
	if err := ensureReadableType(info); err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, newPathError("NOT_A_FILE", "path is not a regular file")
	}

	lines := req.Options.Lines
	if lines <= 0 || lines > s.limits.MaxTailLines {
		lines = s.limits.MaxTailLines
	}

	data, truncated, err := readTail(real, lines, s.limits.MaxPreviewBytes)
	if err != nil {
		return nil, newPathError("READ_FAILED", "cannot read file")
	}
	returned := bytes.Count(data, []byte{'\n'})
	if len(data) > 0 && data[len(data)-1] != '\n' {
		returned++
	}
	return &TailResponse{
		Path:          req.Path,
		RealPath:      real,
		Encoding:      firstNonEmpty(req.Options.Encoding, "UTF-8"),
		Size:          info.Size(),
		ReturnedLines: returned,
		Truncated:     truncated,
		ContentBase64: base64.StdEncoding.EncodeToString(data),
	}, nil
}

func (s *Service) toEntry(fullPath, name string, fi os.FileInfo) Entry {
	owner, group := ownerGroup(fi)
	kind := fileKind(fi)
	previewable := fi.Mode().IsRegular()
	return Entry{
		Name:        name,
		Path:        fullPath,
		Type:        kind,
		Size:        fi.Size(),
		Mode:        fi.Mode().String(),
		Owner:       owner,
		Group:       group,
		ModifiedAt:  fi.ModTime().Format(time.RFC3339),
		Readable:    true,
		Previewable: previewable,
	}
}

func sortEntries(entries []Entry, sortBy, order string) {
	asc := !strings.EqualFold(order, "desc")
	// compareKeys is the ascending key order within one entry kind.
	compareKeys := func(a, b Entry) bool {
		switch sortBy {
		case "size":
			return a.Size < b.Size
		case "modified":
			return a.ModifiedAt < b.ModifiedAt
		case "type":
			return a.Type < b.Type
		default:
			return a.Name < b.Name
		}
	}
	less := func(i, j int) bool {
		a, b := entries[i], entries[j]
		// directories first, regardless of sort direction
		if (a.Type == "dir") != (b.Type == "dir") {
			return a.Type == "dir"
		}
		// Descending swaps the operands instead of negating the result, so
		// equal keys keep less(i,j)==less(j,i)==false (strict weak ordering)
		// and SliceStable preserves their input order.
		if asc {
			return compareKeys(a, b)
		}
		return compareKeys(b, a)
	}
	sort.SliceStable(entries, less)
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// detectMime uses content sniffing on the first 512 bytes plus extension hints.
func detectMime(path string) string {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".log", ".txt", ".conf", ".ini", ".properties", ".sh", ".yml", ".yaml":
		return "text/plain"
	case ".json":
		return "application/json"
	case ".xml":
		return "application/xml"
	}
	f, err := os.Open(path)
	if err != nil {
		return "application/octet-stream"
	}
	defer f.Close()
	buf := make([]byte, 512)
	n, _ := f.Read(buf)
	return http.DetectContentType(buf[:n])
}

func isTextMime(mime string) bool {
	return strings.HasPrefix(mime, "text/") ||
		mime == "application/json" ||
		mime == "application/xml"
}

// looksBinary returns true when the data contains a NUL byte within the first 8KB.
func looksBinary(data []byte) bool {
	limit := len(data)
	if limit > 8192 {
		limit = 8192
	}
	return bytes.IndexByte(data[:limit], 0) >= 0
}
