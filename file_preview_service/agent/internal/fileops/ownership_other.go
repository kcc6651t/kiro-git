//go:build !linux

package fileops

import "os"

// ownerGroup is a portable fallback for non-Linux build/test environments.
func ownerGroup(info os.FileInfo) (string, string) {
	return "", ""
}

func nlink(info os.FileInfo) uint64 {
	return 1
}

// ownerIds is a portable fallback for non-Linux build/test environments.
func ownerIds(info os.FileInfo) (int, int) {
	return -1, -1
}
