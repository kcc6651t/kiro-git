BINARY := file-preview-agent
PKG := github.com/company/file-preview-agent
VERSION ?= 1.0.0
LDFLAGS := -s -w -X $(PKG)/internal/version.Version=$(VERSION)

.PHONY: all build build-linux build-arm64 test tidy clean

all: build-linux

# Static Linux x86_64 binary, no runtime dependencies (CGO disabled).
build-linux:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY) ./cmd/file-preview-agent

# Optional ARM64 cross-compile.
build-arm64:
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "$(LDFLAGS)" \
		-o dist/$(BINARY)-arm64 ./cmd/file-preview-agent

# Local build for the current platform.
build:
	go build -ldflags "$(LDFLAGS)" -o dist/$(BINARY) ./cmd/file-preview-agent

test:
	go test ./...

tidy:
	go mod tidy

clean:
	rm -rf dist
