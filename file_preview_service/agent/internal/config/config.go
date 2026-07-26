package config

import (
	"fmt"
	"net"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config is the full agent configuration loaded from agent.yml.
type Config struct {
	ServerID string   `yaml:"serverId"`
	Listen   Listen   `yaml:"listen"`
	TLS      TLS      `yaml:"tls"`
	Security Security `yaml:"security"`
	Sync     Sync     `yaml:"sync"`
	Logging  Logging  `yaml:"logging"`
}

// Sync controls the primary/standby replication capability. Acting as a SOURCE
// (scan/read) is read-only and always available; only the standby write side is
// gated to preserve the read-only security baseline.
//   - WriteEnabled: allow this Agent to act as a STANDBY target (write files).
//     This grants write access and must be turned on only on backup nodes.
type Sync struct {
	WriteEnabled  bool     `yaml:"writeEnabled"`
	// Roots this Agent may WRITE into when acting as standby. Defaults to allowedRoots.
	TargetRoots   []string `yaml:"targetRoots"`
	MaxChunkBytes int      `yaml:"maxChunkBytes"`
	MaxFiles      int      `yaml:"maxFiles"`
}

type Listen struct {
	Host string `yaml:"host"`
	Port int    `yaml:"port"`
}

type TLS struct {
	CertFile              string   `yaml:"certFile"`
	KeyFile               string   `yaml:"keyFile"`
	ClientCaFile          string   `yaml:"clientCaFile"`
	AllowedClientSubjects []string `yaml:"allowedClientSubjects"`
}

type Security struct {
	AllowedSourceCidrs    []string `yaml:"allowedSourceCidrs"`
	AllowedRoots          []string `yaml:"allowedRoots"`
	DeniedPaths           []string `yaml:"deniedPaths"`
	ShowHiddenFilesDefault bool    `yaml:"showHiddenFilesDefault"`
	MaxDirectoryEntries   int      `yaml:"maxDirectoryEntries"`
	MaxPreviewBytes       int      `yaml:"maxPreviewBytes"`
	MaxPreviewLines       int      `yaml:"maxPreviewLines"`
	MaxTailLines          int      `yaml:"maxTailLines"`
	MaxSearchResults      int      `yaml:"maxSearchResults"`
	MaxSearchScanBytes    int64    `yaml:"maxSearchScanBytes"`
	RequestTimeoutSeconds int      `yaml:"requestTimeoutSeconds"`
	// RejectMultiLink refuses to preview files with more than one hard link
	// (defence against reading sensitive files through a hardlink alias).
	RejectMultiLink bool `yaml:"rejectMultiLink"`
	// Optional static token expected in the X-Agent-Token header as a second factor.
	APIToken string `yaml:"apiToken"`
}

type Logging struct {
	File string `yaml:"file"`
	// 日志滚动与保留：单文件上限(MB)、保留份数、保留天数（超出自动清理）
	MaxSizeMb  int `yaml:"maxSizeMb"`
	MaxBackups int `yaml:"maxBackups"`
	MaxAgeDays int `yaml:"maxAgeDays"`
}

// Load reads and validates the agent configuration.
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	var c Config
	if err := yaml.Unmarshal(data, &c); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	c.applyDefaults()
	if err := c.validate(); err != nil {
		return nil, err
	}
	return &c, nil
}

func (c *Config) applyDefaults() {
	if c.Listen.Host == "" {
		c.Listen.Host = "0.0.0.0"
	}
	if c.Listen.Port == 0 {
		c.Listen.Port = 9443
	}
	if c.Security.MaxDirectoryEntries == 0 {
		c.Security.MaxDirectoryEntries = 1000
	}
	if c.Security.MaxPreviewBytes == 0 {
		// 5 MiB safety cap; large enough to hold ~5000 typical log lines while
		// still bounding memory for GB-scale files.
		c.Security.MaxPreviewBytes = 5 * 1024 * 1024
	}
	if c.Security.MaxPreviewLines == 0 {
		c.Security.MaxPreviewLines = 5000
	}
	if c.Security.MaxTailLines == 0 {
		c.Security.MaxTailLines = 5000
	}
	if c.Security.MaxSearchResults == 0 {
		c.Security.MaxSearchResults = 200
	}
	if c.Security.MaxSearchScanBytes == 0 {
		// Cap how much of a (possibly huge) file we will scan per search request.
		c.Security.MaxSearchScanBytes = 64 * 1024 * 1024
	}
	if c.Sync.MaxChunkBytes == 0 {
		c.Sync.MaxChunkBytes = 4 * 1024 * 1024
	}
	if c.Sync.MaxFiles == 0 {
		c.Sync.MaxFiles = 100000
	}
	if len(c.Sync.TargetRoots) == 0 {
		c.Sync.TargetRoots = c.Security.AllowedRoots
	}
	if c.Security.RequestTimeoutSeconds == 0 {
		c.Security.RequestTimeoutSeconds = 10
	}
	if c.Logging.MaxSizeMb == 0 {
		c.Logging.MaxSizeMb = 50
	}
	if c.Logging.MaxBackups == 0 {
		c.Logging.MaxBackups = 10
	}
	if c.Logging.MaxAgeDays == 0 {
		c.Logging.MaxAgeDays = 15
	}
	// 兼容裸 IP 写法：allowedSourceCidrs 中的纯 IP 自动补前缀（IPv4→/32，IPv6→/128），
	// 存量配置无需改写；真正的非法条目仍由 validate 拒绝。
	for i, cidr := range c.Security.AllowedSourceCidrs {
		c.Security.AllowedSourceCidrs[i] = normalizeCIDR(cidr)
	}
}

// normalizeCIDR 把裸 IP 归一化为单主机 CIDR；已带前缀或无法识别的原样返回。
func normalizeCIDR(s string) string {
	t := strings.TrimSpace(s)
	if strings.Contains(t, "/") {
		return t
	}
	ip := net.ParseIP(t)
	if ip == nil {
		return t
	}
	if ip.To4() != nil {
		return t + "/32"
	}
	return t + "/128"
}

func (c *Config) validate() error {
	if c.ServerID == "" {
		return fmt.Errorf("serverId is required")
	}
	if c.TLS.CertFile == "" || c.TLS.KeyFile == "" {
		return fmt.Errorf("tls.certFile and tls.keyFile are required")
	}
	if c.TLS.ClientCaFile == "" {
		return fmt.Errorf("tls.clientCaFile is required for mTLS")
	}
	if len(c.Security.AllowedRoots) == 0 {
		return fmt.Errorf("security.allowedRoots must contain at least one path")
	}
	// 逐条校验来源 CIDR：非法条目必须启动失败（fail-closed），
	// 否则 auth 层会静默丢弃，全部失效后来源白名单形同虚设。
	for _, cidr := range c.Security.AllowedSourceCidrs {
		if _, _, err := net.ParseCIDR(strings.TrimSpace(cidr)); err != nil {
			return fmt.Errorf("security.allowedSourceCidrs contains invalid CIDR %q", cidr)
		}
	}
	return nil
}
