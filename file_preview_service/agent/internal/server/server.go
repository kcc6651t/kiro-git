package server

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/company/file-preview-agent/internal/audit"
	"github.com/company/file-preview-agent/internal/auth"
	"github.com/company/file-preview-agent/internal/config"
	"github.com/company/file-preview-agent/internal/fileops"
	"github.com/company/file-preview-agent/internal/version"
)

// Server wires the HTTP handlers, security checks, and file service together.
type Server struct {
	cfg     *config.Config
	authz   *auth.Authorizer
	svc     *fileops.Service
	syncSvc *fileops.SyncService
	auditor *audit.Logger
	// maxBody bounds request bodies. It must accommodate a base64-encoded sync
	// write chunk (≈ chunk * 4/3) plus JSON overhead.
	maxBody int64
}

func New(cfg *config.Config, auditor *audit.Logger) *Server {
	guard := fileops.NewPathGuard(cfg.Security.AllowedRoots, cfg.Security.DeniedPaths)
	svc := fileops.NewService(guard, fileops.Limits{
		MaxDirectoryEntries:    cfg.Security.MaxDirectoryEntries,
		MaxPreviewBytes:        cfg.Security.MaxPreviewBytes,
		MaxPreviewLines:        cfg.Security.MaxPreviewLines,
		MaxTailLines:           cfg.Security.MaxTailLines,
		MaxSearchResults:       cfg.Security.MaxSearchResults,
		MaxSearchScanBytes:     cfg.Security.MaxSearchScanBytes,
		ShowHiddenFilesDefault: cfg.Security.ShowHiddenFilesDefault,
		RejectMultiLink:        cfg.Security.RejectMultiLink,
	})
	// Write guard is created only when this agent may act as a standby target.
	var writeGuard *fileops.PathGuard
	if cfg.Sync.WriteEnabled {
		writeGuard = fileops.NewPathGuard(cfg.Sync.TargetRoots, cfg.Security.DeniedPaths)
	}
	syncSvc := fileops.NewSyncService(guard, writeGuard, fileops.SyncLimits{
		MaxChunkBytes: cfg.Sync.MaxChunkBytes,
		MaxFiles:      cfg.Sync.MaxFiles,
	})
	authz := auth.New(cfg.TLS.AllowedClientSubjects, cfg.Security.AllowedSourceCidrs, cfg.Security.APIToken)
	// Allow a base64 sync chunk (chunk*4/3) plus JSON overhead; floor at 1 MiB.
	maxBody := int64(cfg.Sync.MaxChunkBytes)*2 + (1 << 16)
	if maxBody < (1 << 20) {
		maxBody = 1 << 20
	}
	return &Server{cfg: cfg, authz: authz, svc: svc, syncSvc: syncSvc, auditor: auditor, maxBody: maxBody}
}

// ListenAndServe builds the mTLS listener and serves until error.
func (s *Server) ListenAndServe() error {
	tlsConfig, err := s.buildTLSConfig()
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/agent/v1/health", s.handleHealth)
	mux.HandleFunc("/agent/v1/capabilities", s.handleCapabilities)
	mux.HandleFunc("/agent/v1/files/list", s.secured("list", s.handleList))
	mux.HandleFunc("/agent/v1/files/meta", s.secured("meta", s.handleMeta))
	mux.HandleFunc("/agent/v1/files/preview", s.secured("preview", s.handlePreview))
	mux.HandleFunc("/agent/v1/files/tail", s.secured("tail", s.handleTail))
	mux.HandleFunc("/agent/v1/files/search", s.secured("search", s.handleSearch))
	mux.HandleFunc("/agent/v1/sync/scan", s.secured("sync-scan", s.handleSyncScan))
	mux.HandleFunc("/agent/v1/sync/read", s.secured("sync-read", s.handleSyncRead))
	mux.HandleFunc("/agent/v1/sync/write", s.secured("sync-write", s.handleSyncWrite))

	addr := fmt.Sprintf("%s:%d", s.cfg.Listen.Host, s.cfg.Listen.Port)
	timeout := time.Duration(s.cfg.Security.RequestTimeoutSeconds) * time.Second
	httpServer := &http.Server{
		Addr:              addr,
		Handler:           mux,
		TLSConfig:         tlsConfig,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       timeout,
		WriteTimeout:      timeout + 5*time.Second,
		IdleTimeout:       60 * time.Second,
	}
	log.Printf("file-preview-agent %s listening on %s (serverId=%s)", version.Version, addr, s.cfg.ServerID)
	// Certs are provided via TLSConfig.Certificates, so empty strings here are fine.
	return httpServer.ListenAndServeTLS("", "")
}

func (s *Server) buildTLSConfig() (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(s.cfg.TLS.CertFile, s.cfg.TLS.KeyFile)
	if err != nil {
		return nil, fmt.Errorf("load server cert/key: %w", err)
	}
	caPem, err := os.ReadFile(s.cfg.TLS.ClientCaFile)
	if err != nil {
		return nil, fmt.Errorf("read client CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPem) {
		return nil, fmt.Errorf("no certificates parsed from client CA %s", s.cfg.TLS.ClientCaFile)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientCAs:    pool,
		// Enforce mutual TLS: chain is verified against the client CA.
		ClientAuth: tls.RequireAndVerifyClientCert,
		MinVersion: tls.VersionTLS12,
	}, nil
}

// opResult is what a handler reports back to the security wrapper for auditing.
type opResult struct {
	path     string
	realPath string
	userID   string
	userName string
	bytes    int64
	err      error
}

// secured wraps a handler with source-IP, client-cert-subject and token checks,
// plus per-request auditing.
func (s *Server) secured(operation string, next func(http.ResponseWriter, *http.Request) opResult) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		requestID := r.Header.Get("X-Request-Id")
		callerSubject := auth.ClientSubject(r.TLS)
		callerIP := clientIP(r)

		record := func(res opResult, success bool, denyReason string) {
			s.auditor.Record(audit.Event{
				RequestID:     requestID,
				CallerSubject: callerSubject,
				CallerIP:      callerIP,
				UserID:        res.userID,
				Username:      res.userName,
				Operation:     operation,
				Path:          res.path,
				RealPath:      res.realPath,
				Success:       success,
				DenyReason:    denyReason,
				BytesReturned: res.bytes,
				DurationMs:    time.Since(start).Milliseconds(),
			})
		}

		if r.Method != http.MethodPost {
			writeError(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "POST required")
			record(opResult{}, false, "METHOD_NOT_ALLOWED")
			return
		}
		if err := s.authz.CheckSourceIP(r.RemoteAddr); err != nil {
			writeError(w, http.StatusForbidden, "SOURCE_NOT_ALLOWED", "source not allowed")
			record(opResult{}, false, "SOURCE_NOT_ALLOWED")
			return
		}
		if err := s.authz.CheckClientCert(r.TLS); err != nil {
			writeError(w, http.StatusForbidden, "CLIENT_CERT_REJECTED", "client certificate rejected")
			record(opResult{}, false, "CLIENT_CERT_REJECTED")
			return
		}
		if err := s.authz.CheckToken(r.Header.Get("X-Agent-Token")); err != nil {
			writeError(w, http.StatusForbidden, "TOKEN_REJECTED", "token rejected")
			record(opResult{}, false, "TOKEN_REJECTED")
			return
		}

		res := next(w, r)
		if res.err != nil {
			code, status := mapError(res.err)
			writeError(w, status, code, res.err.Error())
			record(res, false, code)
			return
		}
		record(res, true, "")
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"serverId": s.cfg.ServerID,
		"status":   "UP",
		"version":  version.Version,
	})
}

func (s *Server) handleCapabilities(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, fileops.Capabilities{
		ServerID:              s.cfg.ServerID,
		Version:               version.Version,
		MaxPreviewBytes:       s.cfg.Security.MaxPreviewBytes,
		MaxPreviewLines:       s.cfg.Security.MaxPreviewLines,
		MaxTailLines:          s.cfg.Security.MaxTailLines,
		MaxDirectoryEntries:   s.cfg.Security.MaxDirectoryEntries,
		SearchSupported:       true,
		ImagePreviewSupported: false,
		SyncEnabled:           true, // scan/read always available (read-only)
		SyncWriteEnabled:      s.cfg.Sync.WriteEnabled,
	})
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) opResult {
	var req fileops.ListRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.svc.List(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.RealPath, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.Entries))}
}

func (s *Server) handleMeta(w http.ResponseWriter, r *http.Request) opResult {
	var req fileops.MetaRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.svc.Meta(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.RealPath, userID: req.User.ID, userName: req.User.Name}
}

func (s *Server) handlePreview(w http.ResponseWriter, r *http.Request) opResult {
	var req fileops.PreviewRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.svc.Preview(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.RealPath, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.ContentBase64))}
}

func (s *Server) handleTail(w http.ResponseWriter, r *http.Request) opResult {
	var req fileops.TailRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.svc.Tail(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.RealPath, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.ContentBase64))}
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) opResult {
	var req fileops.SearchRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.svc.Search(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.RealPath, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.Matches))}
}

func (s *Server) handleSyncScan(w http.ResponseWriter, r *http.Request) opResult {
	// scan is read-only (bounded by allowedRoots/deniedPaths), same safety as list/preview,
	// so it needs no separate opt-in. Only WRITE (standby) requires writeEnabled.
	var req fileops.SyncScanRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.syncSvc.Scan(req)
	if err != nil {
		return opResult{path: req.Dir, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Dir, realPath: resp.RealDir, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.Files))}
}

func (s *Server) handleSyncRead(w http.ResponseWriter, r *http.Request) opResult {
	// read is read-only (bounded by allowedRoots/deniedPaths); no separate opt-in needed.
	var req fileops.SyncReadRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.syncSvc.Read(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.Path, userID: req.User.ID, userName: req.User.Name, bytes: int64(len(resp.ContentBase64))}
}

func (s *Server) handleSyncWrite(w http.ResponseWriter, r *http.Request) opResult {
	if !s.cfg.Sync.WriteEnabled {
		return opResult{err: &fileops.PathError{Code: "SYNC_WRITE_DISABLED", Message: "sync write is not enabled on this agent"}}
	}
	var req fileops.SyncWriteRequest
	if err := s.decode(r, &req); err != nil {
		return opResult{err: err}
	}
	resp, err := s.syncSvc.Write(req)
	if err != nil {
		return opResult{path: req.Path, userID: req.User.ID, userName: req.User.Name, err: err}
	}
	writeJSON(w, http.StatusOK, resp)
	return opResult{path: req.Path, realPath: resp.Path, userID: req.User.ID, userName: req.User.Name, bytes: int64(resp.BytesWritten)}
}

func (s *Server) decode(r *http.Request, v interface{}) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, s.maxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return fileops.NewBadRequest("invalid request body")
	}
	return nil
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, status int, body interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"code": code, "message": message})
}

// mapError converts a fileops error code into an HTTP status.
func mapError(err error) (string, int) {
	code := fileops.CodeOf(err)
	switch code {
	case "ILLEGAL_PATH", "BAD_REQUEST", "NOT_A_DIRECTORY", "NOT_A_FILE":
		return code, http.StatusBadRequest
	case "DENIED_PATH", "OUTSIDE_ALLOWED_ROOTS", "SYMLINK_ESCAPE", "SPECIAL_FILE", "MULTI_LINK",
		"SYNC_WRITE_DISABLED":
		return code, http.StatusForbidden
	case "WRITE_FAILED":
		return code, http.StatusInternalServerError
	case "NOT_FOUND":
		return code, http.StatusNotFound
	default:
		return "READ_FAILED", http.StatusInternalServerError
	}
}
