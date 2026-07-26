package fileops

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeLines(t *testing.T, path string, n int) {
	t.Helper()
	var sb strings.Builder
	for i := 1; i <= n; i++ {
		sb.WriteString(fmt.Sprintf("line-%d ERROR sample\n", i))
	}
	if err := os.WriteFile(path, []byte(sb.String()), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestPreviewHeadLimitsToMaxLines(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "big.log")
	writeLines(t, p, 10000)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxPreviewLines: 5000, MaxPreviewBytes: 50 * 1024 * 1024})

	resp, err := svc.Preview(PreviewRequest{Path: p}) // default head mode
	if err != nil {
		t.Fatal(err)
	}
	if resp.ReturnedLines != 5000 {
		t.Fatalf("expected 5000 lines, got %d", resp.ReturnedLines)
	}
	if !resp.Truncated {
		t.Fatal("expected truncated=true for a 10000-line file capped at 5000")
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if strings.Contains(string(data), "line-5001 ") {
		t.Fatal("content should stop at line 5000")
	}
}

func TestPreviewHeadRespectsExplicitMaxLines(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 100)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	resp, err := svc.Preview(PreviewRequest{Path: p, Options: PreviewOptions{Mode: "head", MaxLines: 10}})
	if err != nil {
		t.Fatal(err)
	}
	if resp.ReturnedLines != 10 {
		t.Fatalf("expected 10 lines, got %d", resp.ReturnedLines)
	}
	if !resp.Truncated {
		t.Fatal("expected truncated when only 10 of 100 lines returned")
	}
}

func TestPreviewByteCapTruncatesLongContent(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 1000)
	// tiny byte cap forces truncation before line cap
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxPreviewLines: 5000, MaxPreviewBytes: 100})

	resp, err := svc.Preview(PreviewRequest{Path: p})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if len(data) > 100 {
		t.Fatalf("expected <=100 bytes, got %d", len(data))
	}
	if !resp.Truncated {
		t.Fatal("expected truncated when byte cap hit")
	}
}

func TestSearchFindsMatchesWithLimit(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 50) // every line contains "ERROR"
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxSearchResults: 10, MaxSearchScanBytes: 1 << 20})

	resp, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{Query: "ERROR"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Matches) != 10 {
		t.Fatalf("expected 10 matches (capped), got %d", len(resp.Matches))
	}
	if !resp.Truncated {
		t.Fatal("expected truncated when result cap reached")
	}
	if resp.Matches[0].LineNumber != 1 {
		t.Fatalf("expected first match at line 1, got %d", resp.Matches[0].LineNumber)
	}
}

func TestSearchCaseInsensitive(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	if err := os.WriteFile(p, []byte("Alpha\nBETA\ngamma\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	resp, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{Query: "beta"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Matches) != 1 || resp.Matches[0].Line != "BETA" {
		t.Fatalf("expected case-insensitive match on BETA, got %+v", resp.Matches)
	}
}

func TestSearchEmptyQueryRejected(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 3)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	if _, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{Query: ""}}); err == nil {
		t.Fatal("expected empty query to be rejected")
	}
}

func TestPreviewLinesWindowMiddle(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 100)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	resp, err := svc.Preview(PreviewRequest{Path: p, Options: PreviewOptions{Mode: "lines", FromLine: 50, MaxLines: 10}})
	if err != nil {
		t.Fatal(err)
	}
	if resp.StartLine != 50 {
		t.Fatalf("expected startLine=50, got %d", resp.StartLine)
	}
	if resp.ReturnedLines != 10 {
		t.Fatalf("expected 10 lines, got %d", resp.ReturnedLines)
	}
	if !resp.Truncated {
		t.Fatal("expected truncated=true when window ends before EOF")
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	text := string(data)
	if !strings.Contains(text, "line-50 ERROR") || !strings.Contains(text, "line-59 ERROR") {
		t.Fatalf("window should cover lines 50-59, got:\n%s", text)
	}
	if strings.Contains(text, "line-49 ") || strings.Contains(text, "line-60 ") {
		t.Fatal("window should not include lines 49 or 60")
	}
	// startOffset must equal the byte length of the first 49 lines
	var want int64
	for i := 1; i <= 49; i++ {
		want += int64(len(fmt.Sprintf("line-%d ERROR sample\n", i)))
	}
	if resp.StartOffset != want {
		t.Fatalf("expected startOffset=%d, got %d", want, resp.StartOffset)
	}
}

func TestPreviewLinesWindowFromLineOneMatchesHead(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 100)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	resp, err := svc.Preview(PreviewRequest{Path: p, Options: PreviewOptions{Mode: "lines", FromLine: 1, MaxLines: 10}})
	if err != nil {
		t.Fatal(err)
	}
	if resp.StartLine != 1 || resp.StartOffset != 0 {
		t.Fatalf("fromLine=1 should anchor at file start, got startLine=%d startOffset=%d", resp.StartLine, resp.StartOffset)
	}
	if resp.ReturnedLines != 10 || !resp.Truncated {
		t.Fatalf("expected 10 lines truncated, got %d truncated=%v", resp.ReturnedLines, resp.Truncated)
	}
}

func TestPreviewLinesWindowBeyondEOF(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 100)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	resp, err := svc.Preview(PreviewRequest{Path: p, Options: PreviewOptions{Mode: "lines", FromLine: 5000, MaxLines: 10}})
	if err != nil {
		t.Fatal(err)
	}
	if resp.ReturnedLines != 0 {
		t.Fatalf("expected 0 lines beyond EOF, got %d", resp.ReturnedLines)
	}
	if resp.Truncated {
		t.Fatal("beyond-EOF window must not be truncated")
	}
	if resp.StartLine != 5000 {
		t.Fatalf("startLine should echo fromLine, got %d", resp.StartLine)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if len(data) != 0 {
		t.Fatalf("expected empty content beyond EOF, got %d bytes", len(data))
	}
}

func TestPreviewLinesWindowRespectsByteCap(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "app.log")
	writeLines(t, p, 1000)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxPreviewBytes: 100})

	resp, err := svc.Preview(PreviewRequest{Path: p, Options: PreviewOptions{Mode: "lines", FromLine: 500, MaxLines: 5000}})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if len(data) > 100 {
		t.Fatalf("expected <=100 bytes, got %d", len(data))
	}
	if !resp.Truncated {
		t.Fatal("expected truncated when byte cap hit")
	}
	if resp.StartLine != 500 {
		t.Fatalf("expected startLine=500, got %d", resp.StartLine)
	}
}

func TestSearchRawBytesMode(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "gbk.log")
	// 模拟 GBK 编码文件：0xb4 0xed 是"错"的 GBK 字节，非法 UTF-8
	line := []byte("info ")
	line = append(line, 0xb4, 0xed)
	line = append(line, []byte(" Error occurred\n")...)
	if err := os.WriteFile(p, append([]byte("plain line\n"), line...), 0o644); err != nil {
		t.Fatal(err)
	}
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	// 查询串已由中心编码为 GBK 字节：错 + " error"（大小写不敏感走 ASCII 折叠）
	needle := append([]byte{0xb4, 0xed}, []byte(" error")...)
	resp, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{
		QueryBase64: base64.StdEncoding.EncodeToString(needle),
	}})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Matches) != 1 || resp.Matches[0].LineNumber != 2 {
		t.Fatalf("expected 1 match at line 2, got %+v", resp.Matches)
	}
	raw, derr := base64.StdEncoding.DecodeString(resp.Matches[0].LineBase64)
	if derr != nil || !bytes.Contains(raw, needle[:2]) {
		t.Fatalf("lineBase64 should decode to the raw matched line, got %v err=%v", raw, derr)
	}
	if resp.Matches[0].Line != "" {
		t.Fatal("raw mode must not populate Line (center decodes LineBase64)")
	}
}

func TestSearchInvalidQueryBase64Rejected(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "a.log")
	if err := os.WriteFile(p, []byte("x\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{})

	if _, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{QueryBase64: "!!!not-base64"}}); err == nil {
		t.Fatal("expected invalid queryBase64 to be rejected")
	}
}

func TestSearchLongLineIsCapped(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "long.log")
	// 2MB 单行，needle 位于 1MB 单行上限之后：不应匹配，且扫描必须在有界内存内完成
	os.WriteFile(p, []byte(strings.Repeat("x", 2<<20)+"NEEDLE\n"), 0o644)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxSearchScanBytes: 8 << 20})

	resp, err := svc.Search(SearchRequest{Path: p, Options: SearchOptions{Query: "NEEDLE", MaxScanBytes: 8 << 20}})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Matches) != 0 {
		t.Fatalf("needle beyond the per-line cap must not match, got %+v", resp.Matches)
	}
}

func TestPreviewLongLineIsCapped(t *testing.T) {
	root := t.TempDir()
	p := filepath.Join(root, "long.log")
	os.WriteFile(p, []byte(strings.Repeat("y", 2<<20)+"\n"), 0o644)
	svc := NewService(NewPathGuard([]string{root}, nil), Limits{MaxPreviewBytes: 5 << 20, MaxPreviewLines: 10})

	resp, err := svc.Preview(PreviewRequest{Path: p})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := base64.StdEncoding.DecodeString(resp.ContentBase64)
	if len(data) > maxLineBytes {
		t.Fatalf("single line must be capped at %d bytes, got %d", maxLineBytes, len(data))
	}
	if !resp.Truncated {
		t.Fatal("expected truncated=true when the shown line is a cut prefix")
	}
}
