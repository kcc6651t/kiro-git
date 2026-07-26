package fileops

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"io"
	"os"
)

// maxMatchLineBytes caps a matched line kept in the response body.
const maxMatchLineBytes = 4096

// searchFile scans a file line-by-line for a substring match. It is bounded by:
//   - maxResults: stop after this many matches (sets truncated),
//   - maxScan: stop after scanning this many bytes (sets truncated),
//   - maxLineBytes: an over-long line is matched on a capped prefix only, so
//     memory stays bounded even for pathological single-line files.
//
// The needle is matched on raw bytes: for non-UTF-8 files the caller encodes
// the query into the file's charset and rawMode makes matched lines come back
// as LineBase64 for the caller to decode. Case folding is ASCII-only, which is
// exact for ASCII letters and safe for multi-byte encodings (their bytes never
// collide with ASCII letters). No shell or regexp engine is used on user input.
func searchFile(path string, needle []byte, caseSensitive bool, rawMode bool, maxResults int, maxScan int64) (matches []SearchMatch, scanned int64, truncated bool, err error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, 0, false, err
	}
	defer f.Close()

	if !caseSensitive {
		needle = foldASCII(needle)
	}

	reader := bufio.NewReaderSize(f, 64*1024)
	matches = make([]SearchMatch, 0, maxResults)
	lineNo := 0

	for {
		if scanned >= maxScan {
			truncated = true
			break
		}
		line, n, rerr := readLineBounded(reader)
		scanned += int64(n)
		if len(line) > 0 {
			lineNo++
			hay := line
			if !caseSensitive {
				hay = foldASCII(line)
			}
			if bytes.Contains(hay, needle) {
				trimmed := trimTrailingNewlineBytes(line)
				if len(trimmed) > maxMatchLineBytes {
					trimmed = trimmed[:maxMatchLineBytes]
				}
				m := SearchMatch{LineNumber: lineNo}
				if rawMode {
					m.LineBase64 = base64.StdEncoding.EncodeToString(trimmed)
				} else {
					m.Line = string(trimmed)
				}
				matches = append(matches, m)
				if len(matches) >= maxResults {
					truncated = true
					break
				}
			}
		}
		if rerr != nil {
			if rerr != io.EOF {
				return nil, scanned, false, rerr
			}
			break
		}
	}
	return matches, scanned, truncated, nil
}

// foldASCII lower-cases ASCII letters only; multi-byte sequences are untouched.
func foldASCII(b []byte) []byte {
	out := make([]byte, len(b))
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			c += 'a' - 'A'
		}
		out[i] = c
	}
	return out
}

func trimTrailingNewlineBytes(b []byte) []byte {
	b = bytes.TrimSuffix(b, []byte("\n"))
	return bytes.TrimSuffix(b, []byte("\r"))
}
