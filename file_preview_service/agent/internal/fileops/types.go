package fileops

// User is the acting human identity propagated from the central service.
type User struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// ListOptions controls a directory listing.
type ListOptions struct {
	ShowHidden bool   `json:"showHidden"`
	SortBy     string `json:"sortBy"`
	SortOrder  string `json:"sortOrder"`
	Offset     int    `json:"offset"`
	Limit      int    `json:"limit"`
}

// PreviewOptions controls a file preview.
//
// Three modes:
//   - "head" (default): stream the first MaxLines lines (bounded by LimitBytes).
//     Suitable for GB-scale files: only the leading portion is ever read.
//   - "range": read LimitBytes bytes starting at Offset (byte window).
//   - "lines": read MaxLines lines starting at 1-based FromLine (line window,
//     used to jump straight to a search hit's context). Streams and skips; the
//     whole file is never buffered.
type PreviewOptions struct {
	Mode       string `json:"mode"`
	Offset     int64  `json:"offset"`
	LimitBytes int    `json:"limitBytes"`
	MaxLines   int    `json:"maxLines"`
	FromLine   int    `json:"fromLine"`
	Encoding   string `json:"encoding"`
}

// TailOptions controls a log tail.
type TailOptions struct {
	Lines    int    `json:"lines"`
	Encoding string `json:"encoding"`
}

// Request wrappers as received from the central service.
type ListRequest struct {
	RequestID string      `json:"requestId"`
	User      User        `json:"user"`
	Path      string      `json:"path"`
	Options   ListOptions `json:"options"`
}

type MetaRequest struct {
	RequestID string `json:"requestId"`
	User      User   `json:"user"`
	Path      string `json:"path"`
}

type PreviewRequest struct {
	RequestID string         `json:"requestId"`
	User      User           `json:"user"`
	Path      string         `json:"path"`
	Options   PreviewOptions `json:"options"`
}

type TailRequest struct {
	RequestID string      `json:"requestId"`
	User      User        `json:"user"`
	Path      string      `json:"path"`
	Options   TailOptions `json:"options"`
}

// SearchOptions controls an in-file search.
type SearchOptions struct {
	Query string `json:"query"`
	// QueryBase64, when set, supersedes Query: the needle is the base64-decoded
	// raw bytes (already encoded in the file's charset by the center), and
	// matching runs on raw bytes so non-UTF-8 files (e.g. GBK) work. Matched
	// lines are then returned as LineBase64 instead of Line.
	QueryBase64   string `json:"queryBase64"`
	CaseSensitive bool   `json:"caseSensitive"`
	MaxResults    int    `json:"maxResults"`
	MaxScanBytes  int64  `json:"maxScanBytes"`
	Encoding      string `json:"encoding"`
}

type SearchRequest struct {
	RequestID string        `json:"requestId"`
	User      User          `json:"user"`
	Path      string        `json:"path"`
	Options   SearchOptions `json:"options"`
}

// SearchMatch is one matching line.
type SearchMatch struct {
	LineNumber int    `json:"lineNumber"`
	Line       string `json:"line"`
	// LineBase64 carries the raw (trimmed) line bytes when the request used
	// QueryBase64; the center decodes it with the file's charset.
	LineBase64 string `json:"lineBase64,omitempty"`
}

type SearchResponse struct {
	Path        string        `json:"path"`
	RealPath    string        `json:"realPath"`
	Matches     []SearchMatch `json:"matches"`
	Truncated   bool          `json:"truncated"`
	ScannedBytes int64        `json:"scannedBytes"`
}

// Entry is one directory entry.
type Entry struct {
	Name        string `json:"name"`
	Path        string `json:"path"`
	Type        string `json:"type"`
	Size        int64  `json:"size"`
	Mode        string `json:"mode"`
	Owner       string `json:"owner"`
	Group       string `json:"group"`
	ModifiedAt  string `json:"modifiedAt"`
	Readable    bool   `json:"readable"`
	Previewable bool   `json:"previewable"`
}

type ListResponse struct {
	Path     string  `json:"path"`
	RealPath string  `json:"realPath"`
	Entries  []Entry `json:"entries"`
	HasMore  bool    `json:"hasMore"`
}

type MetaResponse struct {
	Path        string `json:"path"`
	RealPath    string `json:"realPath"`
	Type        string `json:"type"`
	Size        int64  `json:"size"`
	Mode        string `json:"mode"`
	Owner       string `json:"owner"`
	Group       string `json:"group"`
	ModifiedAt  string `json:"modifiedAt"`
	MimeType    string `json:"mimeType"`
	Readable    bool   `json:"readable"`
	Previewable bool   `json:"previewable"`
}

type PreviewResponse struct {
	Path          string `json:"path"`
	RealPath      string `json:"realPath"`
	MimeType      string `json:"mimeType"`
	Encoding      string `json:"encoding"`
	Size          int64  `json:"size"`
	Truncated     bool   `json:"truncated"`
	Binary        bool   `json:"binary"`
	ReturnedLines int    `json:"returnedLines"`
	BytesReturned int    `json:"bytesReturned"`
	// StartLine is the 1-based line number of the first returned line
	// ("lines" mode; 1 for "head", 0 for "range").
	StartLine int `json:"startLine"`
	// StartOffset is the byte offset of the first returned byte; clients use
	// StartOffset+BytesReturned to page downward with cheap "range" reads.
	StartOffset   int64  `json:"startOffset"`
	ContentBase64 string `json:"contentBase64"`
}

type TailResponse struct {
	Path          string `json:"path"`
	RealPath      string `json:"realPath"`
	Encoding      string `json:"encoding"`
	Size          int64  `json:"size"`
	ReturnedLines int    `json:"returnedLines"`
	Truncated     bool   `json:"truncated"`
	ContentBase64 string `json:"contentBase64"`
}

// Capabilities advertised to the central service.
type Capabilities struct {
	ServerID             string `json:"serverId"`
	Version              string `json:"version"`
	MaxPreviewBytes      int    `json:"maxPreviewBytes"`
	MaxPreviewLines      int    `json:"maxPreviewLines"`
	MaxTailLines         int    `json:"maxTailLines"`
	MaxDirectoryEntries  int    `json:"maxDirectoryEntries"`
	SearchSupported      bool   `json:"searchSupported"`
	ImagePreviewSupported bool  `json:"imagePreviewSupported"`
	SyncEnabled          bool   `json:"syncEnabled"`
	SyncWriteEnabled     bool   `json:"syncWriteEnabled"`
}
