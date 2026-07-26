//go:build linux

package fileops

import (
	"os"
	"os/user"
	"strconv"
	"syscall"
)

// ownerGroup resolves the owning user and group names on Linux via stat(2).
func ownerGroup(info os.FileInfo) (string, string) {
	st, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return "", ""
	}
	owner := strconv.FormatUint(uint64(st.Uid), 10)
	group := strconv.FormatUint(uint64(st.Gid), 10)
	if u, err := user.LookupId(owner); err == nil {
		owner = u.Username
	}
	if g, err := user.LookupGroupId(group); err == nil {
		group = g.Name
	}
	return owner, group
}

// nlink returns the hard link count (used for the optional high-sensitivity check).
func nlink(info os.FileInfo) uint64 {
	if st, ok := info.Sys().(*syscall.Stat_t); ok {
		return uint64(st.Nlink)
	}
	return 1
}

// ownerIds returns the numeric uid/gid for ownership preservation during sync.
func ownerIds(info os.FileInfo) (int, int) {
	if st, ok := info.Sys().(*syscall.Stat_t); ok {
		return int(st.Uid), int(st.Gid)
	}
	return -1, -1
}
