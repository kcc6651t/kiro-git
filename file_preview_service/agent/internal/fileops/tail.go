package fileops

import (
	"bytes"
	"io"
	"os"
)

// readTail returns the last `lines` lines of the file, reading at most `maxBytes`
// bytes from the end. It returns the collected data and whether earlier content
// was omitted (truncated).
func readTail(path string, lines, maxBytes int) ([]byte, bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, false, err
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return nil, false, err
	}
	size := stat.Size()
	if size == 0 {
		return []byte{}, false, nil
	}

	const block = 64 * 1024
	var collected []byte
	newlineCount := 0
	var pos = size
	truncated := false

	for pos > 0 {
		readSize := int64(block)
		if pos < readSize {
			readSize = pos
		}
		pos -= readSize
		chunk := make([]byte, readSize)
		if _, err := f.ReadAt(chunk, pos); err != nil && err != io.EOF {
			return nil, false, err
		}
		collected = append(chunk, collected...)

		// Enforce the byte cap.
		if len(collected) > maxBytes {
			overflow := len(collected) - maxBytes
			collected = collected[overflow:]
			truncated = true
			// Drop a possibly partial first line.
			if idx := bytes.IndexByte(collected, '\n'); idx >= 0 {
				collected = collected[idx+1:]
			}
			break
		}

		newlineCount = bytes.Count(collected, []byte{'\n'})
		if newlineCount > lines {
			break
		}
	}

	if pos > 0 {
		truncated = true
	}

	// Keep only the last `lines` lines.
	if n := countLines(collected); n > lines {
		collected = lastNLines(collected, lines)
		truncated = true
	}
	return collected, truncated, nil
}

func countLines(data []byte) int {
	if len(data) == 0 {
		return 0
	}
	n := bytes.Count(data, []byte{'\n'})
	if data[len(data)-1] != '\n' {
		n++
	}
	return n
}

// lastNLines returns the trailing n lines of data.
func lastNLines(data []byte, n int) []byte {
	if n <= 0 {
		return []byte{}
	}
	// Walk backwards counting newlines.
	count := 0
	i := len(data) - 1
	// Ignore a trailing newline for counting purposes.
	if i >= 0 && data[i] == '\n' {
		i--
	}
	for ; i >= 0; i-- {
		if data[i] == '\n' {
			count++
			if count == n {
				return data[i+1:]
			}
		}
	}
	return data
}
