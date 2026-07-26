package fileops

import (
	"bufio"
	"bytes"
	"io"
	"os"
)

// readHeadLines streams up to maxLines lines from the start of f, never buffering
// more than byteCap bytes. This keeps memory bounded even for GB-scale files: only
// the leading window is read, and reading stops at the first of {maxLines reached,
// byteCap reached, EOF}.
//
// Returns the collected bytes, number of complete lines captured, whether more
// content remains (truncated), and whether the content looks binary.
func readHeadLines(f *os.File, maxLines, byteCap int) (data []byte, lines int, truncated bool, binary bool, err error) {
	reader := bufio.NewReaderSize(f, 64*1024)

	// Peek the first chunk to detect binary content up front.
	head, _ := reader.Peek(512)
	if looksBinary(head) {
		// For binary files, return a small byte sample and flag it; the UI will
		// not render it as text.
		sample := make([]byte, min(byteCap, 4096))
		n, rerr := io.ReadFull(reader, sample)
		if rerr != nil && rerr != io.EOF && rerr != io.ErrUnexpectedEOF {
			return nil, 0, false, true, &PathError{Code: "READ_FAILED", Message: "cannot read file"}
		}
		return sample[:n], 0, int64(n) < fileSize(f), true, nil
	}

	data, lines, truncated, err = collectLines(reader, maxLines, byteCap)
	if err != nil {
		return nil, 0, false, false, err
	}
	return data, lines, truncated, false, nil
}

// readLinesWindow streams up to maxLines lines starting at 1-based line fromLine,
// never buffering more than byteCap bytes. The first fromLine-1 lines are skipped
// while their bytes are counted, so callers learn the window's byte offset and can
// continue paging downward with cheap byte-range reads. A fromLine beyond EOF
// yields an empty, non-truncated window anchored at the reached offset.
func readLinesWindow(f *os.File, fromLine, maxLines, byteCap int) (data []byte, startLine int, startOffset int64, lines int, truncated bool, err error) {
	if fromLine < 1 {
		fromLine = 1
	}
	reader := bufio.NewReaderSize(f, 64*1024)

	var off int64
	for skipped := 1; skipped < fromLine; skipped++ {
		_, n, rerr := readLineBounded(reader)
		off += int64(n)
		if rerr != nil {
			if rerr == io.EOF {
				return []byte{}, fromLine, off, 0, false, nil
			}
			return nil, 0, 0, 0, false, &PathError{Code: "READ_FAILED", Message: "cannot read file"}
		}
	}

	data, lines, truncated, err = collectLines(reader, maxLines, byteCap)
	if err != nil {
		return nil, 0, 0, 0, false, err
	}
	return data, fromLine, off, lines, truncated, nil
}

// collectLines gathers up to maxLines lines from reader, never buffering more than
// byteCap bytes. Reading stops at the first of {maxLines reached, byteCap reached,
// EOF}; truncated reports whether more content remains after the window. A final
// line cut short by byteCap still counts as one shown line.
func collectLines(reader *bufio.Reader, maxLines, byteCap int) (data []byte, lines int, truncated bool, err error) {
	var buf bytes.Buffer
	for lines < maxLines {
		if buf.Len() >= byteCap {
			truncated = true
			break
		}
		line, n, rerr := readLineBounded(reader)
		if len(line) > 0 {
			remaining := byteCap - buf.Len()
			if len(line) > remaining {
				buf.Write(line[:remaining])
				truncated = true
				lines++ // partial line still counts as shown
				break
			}
			buf.Write(line)
			lines++
			if n > len(line) {
				// 行超单行上限，只展示了前缀；后续内容（含本行剩余部分）未加载。
				truncated = true
				break
			}
		}
		if rerr != nil {
			// EOF or error: no more data.
			if rerr != io.EOF {
				return nil, 0, false, &PathError{Code: "READ_FAILED", Message: "cannot read file"}
			}
			return buf.Bytes(), lines, truncated, nil
		}
	}
	// We stopped due to maxLines or byteCap; check whether more content remains.
	if !truncated {
		if _, perr := reader.Peek(1); perr != io.EOF {
			truncated = true
		}
	}
	return buf.Bytes(), lines, truncated, nil
}

// readByteRange reads up to limit bytes starting at offset.
func readByteRange(f *os.File, offset int64, limit int, size int64) ([]byte, bool, error) {
	if offset > 0 {
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			return nil, false, &PathError{Code: "READ_FAILED", Message: "cannot seek file"}
		}
	}
	buf := make([]byte, limit)
	n, err := io.ReadFull(f, buf)
	if err != nil && err != io.EOF && err != io.ErrUnexpectedEOF {
		return nil, false, &PathError{Code: "READ_FAILED", Message: "cannot read file"}
	}
	data := buf[:n]
	truncated := size > offset+int64(n)
	return data, truncated, nil
}

func fileSize(f *os.File) int64 {
	if info, err := f.Stat(); err == nil {
		return info.Size()
	}
	return 0
}
