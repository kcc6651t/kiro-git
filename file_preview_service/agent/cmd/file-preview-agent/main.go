package main

import (
	"flag"
	"fmt"
	"log"
	"os"

	"github.com/company/file-preview-agent/internal/audit"
	"github.com/company/file-preview-agent/internal/config"
	"github.com/company/file-preview-agent/internal/server"
	"github.com/company/file-preview-agent/internal/version"
)

func main() {
	configPath := flag.String("config", "/opt/file-preview-agent/config/agent.yml", "path to agent.yml")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("file-preview-agent %s\n", version.Version)
		return
	}

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	auditor, err := audit.New(cfg.Logging.File, cfg.Logging.MaxSizeMb, cfg.Logging.MaxBackups, cfg.Logging.MaxAgeDays)
	if err != nil {
		log.Fatalf("failed to init audit log: %v", err)
	}

	srv := server.New(cfg, auditor)
	if err := srv.ListenAndServe(); err != nil {
		log.Printf("server exited: %v", err)
		os.Exit(1)
	}
}
