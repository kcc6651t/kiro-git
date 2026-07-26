package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// writeConfig writes body to a temp agent.yml and returns its path.
func writeConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.yml")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// minimalYAML is the smallest configuration that passes validate().
const minimalYAML = `
serverId: test-01
tls:
  certFile: /tmp/certs/agent-server.crt
  keyFile: /tmp/certs/agent-server.key
  clientCaFile: /tmp/certs/main-ca.crt
security:
  allowedRoots:
    - /opt/apps
    - /data/logs
`

func TestLoadAppliesDefaults(t *testing.T) {
	c, err := Load(writeConfig(t, minimalYAML))
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen.Host != "0.0.0.0" {
		t.Errorf("listen.host default = %q, want 0.0.0.0", c.Listen.Host)
	}
	if c.Listen.Port != 9443 {
		t.Errorf("listen.port default = %d, want 9443", c.Listen.Port)
	}
	intChecks := []struct {
		name string
		got  int
		want int
	}{
		{"maxDirectoryEntries", c.Security.MaxDirectoryEntries, 1000},
		{"maxPreviewBytes", c.Security.MaxPreviewBytes, 5 * 1024 * 1024},
		{"maxPreviewLines", c.Security.MaxPreviewLines, 5000},
		{"maxTailLines", c.Security.MaxTailLines, 5000},
		{"maxSearchResults", c.Security.MaxSearchResults, 200},
		{"requestTimeoutSeconds", c.Security.RequestTimeoutSeconds, 10},
		{"sync.maxChunkBytes", c.Sync.MaxChunkBytes, 4 * 1024 * 1024},
		{"sync.maxFiles", c.Sync.MaxFiles, 100000},
		{"logging.maxSizeMb", c.Logging.MaxSizeMb, 50},
		{"logging.maxBackups", c.Logging.MaxBackups, 10},
		{"logging.maxAgeDays", c.Logging.MaxAgeDays, 15},
	}
	for _, chk := range intChecks {
		if chk.got != chk.want {
			t.Errorf("%s default = %d, want %d", chk.name, chk.got, chk.want)
		}
	}
	if c.Security.MaxSearchScanBytes != 64*1024*1024 {
		t.Errorf("maxSearchScanBytes default = %d, want %d", c.Security.MaxSearchScanBytes, 64*1024*1024)
	}
}

func TestLoadTargetRootsFallBackToAllowedRoots(t *testing.T) {
	c, err := Load(writeConfig(t, minimalYAML))
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Sync.TargetRoots) != len(c.Security.AllowedRoots) {
		t.Fatalf("targetRoots = %v, want fallback to allowedRoots %v", c.Sync.TargetRoots, c.Security.AllowedRoots)
	}
	for i := range c.Sync.TargetRoots {
		if c.Sync.TargetRoots[i] != c.Security.AllowedRoots[i] {
			t.Fatalf("targetRoots = %v, want %v", c.Sync.TargetRoots, c.Security.AllowedRoots)
		}
	}
}

func TestLoadKeepsExplicitTargetRoots(t *testing.T) {
	c, err := Load(writeConfig(t, minimalYAML+`
sync:
  targetRoots:
    - /backup/incoming
`))
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Sync.TargetRoots) != 1 || c.Sync.TargetRoots[0] != "/backup/incoming" {
		t.Fatalf("targetRoots = %v, want [/backup/incoming]", c.Sync.TargetRoots)
	}
}

// Removed keys (sync.enabled, logging.level) and any other unknown keys must
// still parse: Load uses non-strict yaml.Unmarshal, so old configs keep working.
func TestLoadToleratesUnknownKeys(t *testing.T) {
	c, err := Load(writeConfig(t, minimalYAML+`
sync:
  enabled: true
logging:
  level: debug
`))
	if err != nil {
		t.Fatalf("legacy/unknown keys should be ignored, got %v", err)
	}
	if c.ServerID != "test-01" {
		t.Fatalf("serverId = %q, want test-01", c.ServerID)
	}
}

func TestLoadValidationErrors(t *testing.T) {
	cases := []struct {
		name    string
		yaml    string
		wantErr string
	}{
		{
			name: "missing serverId",
			yaml: `
tls:
  certFile: /tmp/a.crt
  keyFile: /tmp/a.key
  clientCaFile: /tmp/ca.crt
security:
  allowedRoots: [/data/logs]
`,
			wantErr: "serverId",
		},
		{
			name: "missing tls cert files",
			yaml: `
serverId: test-01
security:
  allowedRoots: [/data/logs]
`,
			wantErr: "tls.certFile",
		},
		{
			name: "missing client CA",
			yaml: `
serverId: test-01
tls:
  certFile: /tmp/a.crt
  keyFile: /tmp/a.key
security:
  allowedRoots: [/data/logs]
`,
			wantErr: "tls.clientCaFile",
		},
		{
			name: "missing allowedRoots",
			yaml: `
serverId: test-01
tls:
  certFile: /tmp/a.crt
  keyFile: /tmp/a.key
  clientCaFile: /tmp/ca.crt
`,
			wantErr: "allowedRoots",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(writeConfig(t, tc.yaml))
			if err == nil {
				t.Fatalf("expected error containing %q", tc.wantErr)
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not mention %q", err, tc.wantErr)
			}
		})
	}
}

// 非法 allowedSourceCidrs 必须导致启动失败（fail-closed），合法条目正常加载。
func TestLoadValidatesSourceCidrs(t *testing.T) {
	yamlWithCidrs := func(cidrs string) string {
		return `
serverId: test-01
tls:
  certFile: /tmp/a.crt
  keyFile: /tmp/a.key
  clientCaFile: /tmp/ca.crt
security:
  allowedRoots: [/data/logs]
  allowedSourceCidrs:` + cidrs + `
`
	}

	t.Run("valid CIDRs accepted", func(t *testing.T) {
		c, err := Load(writeConfig(t, yamlWithCidrs(`
    - 10.10.0.0/24
    - 192.168.1.5/32
`)))
		if err != nil {
			t.Fatalf("valid CIDRs should load, got %v", err)
		}
		if len(c.Security.AllowedSourceCidrs) != 2 {
			t.Fatalf("allowedSourceCidrs = %v, want 2 entries", c.Security.AllowedSourceCidrs)
		}
	})

	t.Run("invalid CIDR rejected", func(t *testing.T) {
		_, err := Load(writeConfig(t, yamlWithCidrs(`
    - 10.10.0.5/33
`)))
		if err == nil {
			t.Fatal("expected invalid CIDR to fail validation")
		}
		if !strings.Contains(err.Error(), "10.10.0.5/33") {
			t.Fatalf("error %q should mention the offending entry", err)
		}
	})

	t.Run("bare IPs normalized to host CIDRs", func(t *testing.T) {
		c, err := Load(writeConfig(t, yamlWithCidrs(`
    - 12.244.104.102
    - 10.10.0.0/24
    - "::1"
`)))
		if err != nil {
			t.Fatalf("bare IPs should load, got %v", err)
		}
		got := c.Security.AllowedSourceCidrs
		want := []string{"12.244.104.102/32", "10.10.0.0/24", "::1/128"}
		if len(got) != len(want) {
			t.Fatalf("allowedSourceCidrs = %v, want %v", got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("entry %d = %q, want %q", i, got[i], want[i])
			}
		}
	})
}
