package fileops

// ---- Primary/standby sync wire types ----

// SyncScanRequest asks the SOURCE agent to enumerate files under a directory that
// match the include/exclude globs.
type SyncScanRequest struct {
	RequestID string   `json:"requestId"`
	User      User     `json:"user"`
	Dir       string   `json:"dir"`
	Includes  []string `json:"includes"`
	Excludes  []string `json:"excludes"`
	// ExcludeDirs prunes whole directory subtrees during recursive scans. Each
	// pattern is matched against both the directory base name ("cache") and its
	// slash-separated path relative to Dir ("logs/archive").
	ExcludeDirs []string `json:"excludeDirs"`
	Recursive   bool     `json:"recursive"`
}

// SyncFile describes one file to be replicated (relative to the scanned dir).
type SyncFile struct {
	RelPath    string `json:"relPath"`
	Size       int64  `json:"size"`
	Mode       uint32 `json:"mode"`      // permission bits (e.g. 0640)
	ModTimeMs  int64  `json:"modTimeMs"` // unix millis
	Uid        int    `json:"uid"`
	Gid        int    `json:"gid"`
	Owner      string `json:"owner"`
	Group      string `json:"group"`
}

type SyncScanResponse struct {
	Dir      string     `json:"dir"`
	RealDir  string     `json:"realDir"`
	Files    []SyncFile `json:"files"`
	Truncated bool      `json:"truncated"`
}

// SyncReadRequest reads a byte range from a SOURCE file.
type SyncReadRequest struct {
	RequestID string `json:"requestId"`
	User      User   `json:"user"`
	Path      string `json:"path"`
	Offset    int64  `json:"offset"`
	Length    int    `json:"length"`
}

type SyncReadResponse struct {
	Path          string `json:"path"`
	Offset        int64  `json:"offset"`
	Eof           bool   `json:"eof"`
	ContentBase64 string `json:"contentBase64"`
}

// SyncWriteRequest writes a chunk to a STANDBY target file. offset==0 truncates
// (start of file). When last==true the file is finalized: chmod to mode, set
// mtime, best-effort chown to uid/gid, then atomically renamed into place.
type SyncWriteRequest struct {
	RequestID     string `json:"requestId"`
	User          User   `json:"user"`
	Path          string `json:"path"`
	Offset        int64  `json:"offset"`
	Last          bool   `json:"last"`
	ContentBase64 string `json:"contentBase64"`
	Mode          uint32 `json:"mode"`
	ModTimeMs     int64  `json:"modTimeMs"`
	Uid           int    `json:"uid"`
	Gid           int    `json:"gid"`
	PreservePermissions bool `json:"preservePermissions"`
	PreserveOwnership   bool `json:"preserveOwnership"`
}

type SyncWriteResponse struct {
	Path            string `json:"path"`
	BytesWritten    int    `json:"bytesWritten"`
	Finalized       bool   `json:"finalized"`
	PermissionsSet  bool   `json:"permissionsSet"`
	OwnershipSet    bool   `json:"ownershipSet"`
	OwnershipError  string `json:"ownershipError,omitempty"`
}
