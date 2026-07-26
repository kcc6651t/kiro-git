package version

// Version is the Agent version, overridable at build time with:
//   go build -ldflags "-X github.com/company/file-preview-agent/internal/version.Version=1.2.3"
var Version = "1.0.0"
