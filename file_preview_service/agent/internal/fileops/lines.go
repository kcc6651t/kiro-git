package fileops

import (
	"bufio"
	"bytes"
)

// maxLineBytes bounds a single logical line; longer lines are fully consumed
// but only this prefix is retained, so preview/search memory stays bounded even
// for pathological single-line files (e.g. a GB-scale JSON log line).
const maxLineBytes = 1 << 20 // 1 MiB

// readLineBounded reads one line (through '\n') like bufio ReadBytes, but caps
// the retained line at maxLineBytes: an over-long line is consumed to its end
// while only the capped prefix is kept. total is the number of bytes actually
// consumed (the line's true length), so byte-offset accounting stays exact even
// when the retained prefix was cut. Error semantics match ReadBytes (a final
// line without newline returns io.EOF along with the data).
func readLineBounded(r *bufio.Reader) (line []byte, total int, err error) {
	var buf bytes.Buffer
	for {
		frag, rerr := r.ReadSlice('\n')
		total += len(frag)
		if keep := maxLineBytes - buf.Len(); keep > 0 {
			if keep > len(frag) {
				keep = len(frag)
			}
			buf.Write(frag[:keep])
		}
		if rerr == bufio.ErrBufferFull {
			continue // fragment without newline; keep consuming the long line
		}
		return buf.Bytes(), total, rerr
	}
}
