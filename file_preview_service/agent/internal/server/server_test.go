package server

import (
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/company/file-preview-agent/internal/audit"
	"github.com/company/file-preview-agent/internal/config"
	"github.com/company/file-preview-agent/internal/fileops"
)

const goodCN = "file-preview-main"
const goodAddr = "10.10.0.5:40000"

// newTestServer builds a Server with test defaults; mutate may adjust cfg
// before construction.
func newTestServer(t *testing.T, mutate func(*config.Config)) *Server {
	t.Helper()
	dir := t.TempDir()
	auditor, err := audit.New(filepath.Join(dir, "audit.log"), 50, 10, 15)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{
		ServerID: "test-01",
		TLS:      config.TLS{AllowedClientSubjects: []string{"CN=" + goodCN}},
		Security: config.Security{
			AllowedSourceCidrs: []string{"10.10.0.0/24"},
			AllowedRoots:       []string{dir},
			MaxPreviewBytes:    1 << 20,
			MaxPreviewLines:    100,
		},
		Sync: config.Sync{MaxChunkBytes: 1 << 20, MaxFiles: 1000},
	}
	if mutate != nil {
		mutate(cfg)
	}
	// Close the audit file before TempDir cleanup (Windows file locking).
	t.Cleanup(func() { _ = auditor.Close() })
	return New(cfg, auditor)
}

// newRequest builds a request; cn == "" leaves TLS nil (no client cert).
// The auth layer only inspects PeerCertificates[0].Subject, so a hand-built
// ConnectionState stands in for a verified mTLS handshake.
func newRequest(method, target, remoteAddr, cn, body string) *http.Request {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.RemoteAddr = remoteAddr
	if cn != "" {
		req.TLS = &tls.ConnectionState{
			PeerCertificates: []*x509.Certificate{
				{Subject: pkix.Name{CommonName: cn}},
			},
		}
	}
	return req
}

func goodPost(target, body string) *http.Request {
	return newRequest(http.MethodPost, target, goodAddr, goodCN, body)
}

func responseCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("response is not JSON: %v", err)
	}
	return body["code"]
}

func TestSecuredRejectsNonPost(t *testing.T) {
	s := newTestServer(t, nil)
	rec := httptest.NewRecorder()
	req := newRequest(http.MethodGet, "/agent/v1/files/list", goodAddr, goodCN, "")
	s.secured("list", s.handleList)(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
	if got := responseCode(t, rec); got != "METHOD_NOT_ALLOWED" {
		t.Fatalf("code = %s, want METHOD_NOT_ALLOWED", got)
	}
}

func TestSecuredRejectsDisallowedSourceIP(t *testing.T) {
	s := newTestServer(t, nil)
	rec := httptest.NewRecorder()
	req := newRequest(http.MethodPost, "/agent/v1/files/list", "192.168.9.9:40000", goodCN, "{}")
	s.secured("list", s.handleList)(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}
	if got := responseCode(t, rec); got != "SOURCE_NOT_ALLOWED" {
		t.Fatalf("code = %s, want SOURCE_NOT_ALLOWED", got)
	}
}

func TestSecuredRejectsMissingClientCert(t *testing.T) {
	s := newTestServer(t, nil)
	noTLS := newRequest(http.MethodPost, "/agent/v1/files/list", goodAddr, "", "{}")
	noCerts := newRequest(http.MethodPost, "/agent/v1/files/list", goodAddr, "", "{}")
	noCerts.TLS = &tls.ConnectionState{}
	for _, req := range []*http.Request{noTLS, noCerts} {
		rec := httptest.NewRecorder()
		s.secured("list", s.handleList)(rec, req)
		if rec.Code != http.StatusForbidden {
			t.Fatalf("status = %d, want 403", rec.Code)
		}
		if got := responseCode(t, rec); got != "CLIENT_CERT_REJECTED" {
			t.Fatalf("code = %s, want CLIENT_CERT_REJECTED", got)
		}
	}
}

func TestSecuredRejectsUnlistedCN(t *testing.T) {
	s := newTestServer(t, nil)
	rec := httptest.NewRecorder()
	req := newRequest(http.MethodPost, "/agent/v1/files/list", goodAddr, "rogue-client", "{}")
	s.secured("list", s.handleList)(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}
	if got := responseCode(t, rec); got != "CLIENT_CERT_REJECTED" {
		t.Fatalf("code = %s, want CLIENT_CERT_REJECTED", got)
	}
}

// TestSecuredValidCNPassesAuth: a syntactically invalid body fails only AFTER
// auth, so a 400 proves the request passed source-IP, cert and token checks.
func TestSecuredValidCNPassesAuth(t *testing.T) {
	s := newTestServer(t, nil)
	rec := httptest.NewRecorder()
	s.secured("list", s.handleList)(rec, goodPost("/agent/v1/files/list", "{not-json"))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 (auth passed, decode failed)", rec.Code)
	}
	if got := responseCode(t, rec); got != "BAD_REQUEST" {
		t.Fatalf("code = %s, want BAD_REQUEST", got)
	}
}

func TestSecuredValidAuthListsDirectory(t *testing.T) {
	var dir string
	s := newTestServer(t, func(c *config.Config) { dir = c.Security.AllowedRoots[0] })
	body, err := json.Marshal(fileops.ListRequest{Path: dir})
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	s.secured("list", s.handleList)(rec, goodPost("/agent/v1/files/list", string(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body: %s", rec.Code, rec.Body.String())
	}
}

func TestSecuredRejectsWrongToken(t *testing.T) {
	s := newTestServer(t, func(c *config.Config) { c.Security.APIToken = "s3cret" })
	rec := httptest.NewRecorder()
	req := goodPost("/agent/v1/files/list", "{}")
	req.Header.Set("X-Agent-Token", "wrong")
	s.secured("list", s.handleList)(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}
	if got := responseCode(t, rec); got != "TOKEN_REJECTED" {
		t.Fatalf("code = %s, want TOKEN_REJECTED", got)
	}
}

func TestSecuredAcceptsCorrectToken(t *testing.T) {
	s := newTestServer(t, func(c *config.Config) { c.Security.APIToken = "s3cret" })
	rec := httptest.NewRecorder()
	req := goodPost("/agent/v1/files/list", "{not-json")
	req.Header.Set("X-Agent-Token", "s3cret")
	s.secured("list", s.handleList)(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 (token accepted, decode failed)", rec.Code)
	}
}

func TestSyncWriteDisabled(t *testing.T) {
	s := newTestServer(t, nil) // Sync.WriteEnabled defaults to false
	rec := httptest.NewRecorder()
	req := goodPost("/agent/v1/sync/write", `{"path":"/x","contentBase64":"aGk="}`)
	s.secured("sync-write", s.handleSyncWrite)(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}
	if got := responseCode(t, rec); got != "SYNC_WRITE_DISABLED" {
		t.Fatalf("code = %s, want SYNC_WRITE_DISABLED", got)
	}
}

// TestHealthAndCapabilitiesSkipAuth: both endpoints are reachable without any
// credentials, from any source IP, on any HTTP method.
func TestHealthAndCapabilitiesSkipAuth(t *testing.T) {
	s := newTestServer(t, nil)
	cases := []struct {
		name    string
		method  string
		handler http.HandlerFunc
	}{
		{"health GET", http.MethodGet, s.handleHealth},
		{"health PUT", http.MethodPut, s.handleHealth},
		{"capabilities GET", http.MethodGet, s.handleCapabilities},
		{"capabilities PUT", http.MethodPut, s.handleCapabilities},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := newRequest(tc.method, "/agent/v1/x", "192.168.9.9:40000", "", "")
			tc.handler(rec, req)
			if rec.Code != http.StatusOK {
				t.Fatalf("status = %d, want 200", rec.Code)
			}
		})
	}
}

func TestMapError(t *testing.T) {
	cases := []struct {
		err        error
		wantCode   string
		wantStatus int
	}{
		{&fileops.PathError{Code: "ILLEGAL_PATH"}, "ILLEGAL_PATH", http.StatusBadRequest},
		{&fileops.PathError{Code: "BAD_REQUEST"}, "BAD_REQUEST", http.StatusBadRequest},
		{&fileops.PathError{Code: "NOT_A_DIRECTORY"}, "NOT_A_DIRECTORY", http.StatusBadRequest},
		{&fileops.PathError{Code: "NOT_A_FILE"}, "NOT_A_FILE", http.StatusBadRequest},
		{&fileops.PathError{Code: "DENIED_PATH"}, "DENIED_PATH", http.StatusForbidden},
		{&fileops.PathError{Code: "OUTSIDE_ALLOWED_ROOTS"}, "OUTSIDE_ALLOWED_ROOTS", http.StatusForbidden},
		{&fileops.PathError{Code: "SYMLINK_ESCAPE"}, "SYMLINK_ESCAPE", http.StatusForbidden},
		{&fileops.PathError{Code: "SPECIAL_FILE"}, "SPECIAL_FILE", http.StatusForbidden},
		{&fileops.PathError{Code: "MULTI_LINK"}, "MULTI_LINK", http.StatusForbidden},
		{&fileops.PathError{Code: "SYNC_WRITE_DISABLED"}, "SYNC_WRITE_DISABLED", http.StatusForbidden},
		{&fileops.PathError{Code: "NOT_FOUND"}, "NOT_FOUND", http.StatusNotFound},
		{&fileops.PathError{Code: "WRITE_FAILED"}, "WRITE_FAILED", http.StatusInternalServerError},
		{&fileops.PathError{Code: "SOMETHING_ELSE"}, "READ_FAILED", http.StatusInternalServerError},
		{errors.New("boom"), "READ_FAILED", http.StatusInternalServerError},
	}
	for _, tc := range cases {
		code, status := mapError(tc.err)
		if code != tc.wantCode || status != tc.wantStatus {
			t.Errorf("mapError(%v) = (%s, %d), want (%s, %d)", tc.err, code, status, tc.wantCode, tc.wantStatus)
		}
	}
}
