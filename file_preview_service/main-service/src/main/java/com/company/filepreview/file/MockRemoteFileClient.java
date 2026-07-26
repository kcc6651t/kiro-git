package com.company.filepreview.file;

import com.company.filepreview.file.model.AgentCapabilities;
import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.file.model.FilePreview;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.PreviewOptions;
import com.company.filepreview.file.model.RemoteFileEntry;
import com.company.filepreview.file.model.RemoteFileMeta;
import com.company.filepreview.file.model.SearchMatch;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.file.model.TailOptions;
import com.company.filepreview.file.model.TailResult;
import com.company.filepreview.file.model.UserContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory fixture implementing {@link RemoteFileClient}. Activated with
 * {@code filepreview.remote-client=mock} (the default) so the frontend and central
 * APIs can be developed before any real Agent exists.
 */
@Component
@ConditionalOnProperty(name = "filepreview.remote-client", havingValue = "mock", matchIfMissing = true)
public class MockRemoteFileClient implements RemoteFileClient {

    @Override
    public AgentHealth health(String serverId) {
        return AgentHealth.builder().serverId(serverId).status("UP").version("mock-1.0.0").build();
    }

    @Override
    public AgentCapabilities capabilities(String serverId) {
        return AgentCapabilities.builder()
                .serverId(serverId)
                .version("mock-1.0.0")
                .maxPreviewBytes(5 * 1024 * 1024)
                .maxPreviewLines(5000)
                .maxTailLines(5000)
                .maxDirectoryEntries(1000)
                .searchSupported(true)
                .imagePreviewSupported(false)
                .build();
    }

    @Override
    public ListResult list(String serverId, UserContext user, String path, ListOptions options) {
        List<RemoteFileEntry> entries = new ArrayList<>();
        // Two sample subdirectories and a few files under any path.
        entries.add(dir(path, "config"));
        entries.add(dir(path, "archive"));
        entries.add(file(path, "app.log", 1_234_567));
        entries.add(file(path, "error.log", 89_012));
        entries.add(file(path, "application.yml", 2_048));
        entries.add(file(path, "notes.txt", 512));
        if (options != null && options.isShowHidden()) {
            entries.add(file(path, ".hidden", 16));
        }

        sort(entries, options);

        int offset = options != null ? Math.max(0, options.getOffset()) : 0;
        int limit = options != null && options.getLimit() > 0 ? options.getLimit() : 200;
        boolean hasMore = offset + limit < entries.size();
        int end = Math.min(entries.size(), offset + limit);
        List<RemoteFileEntry> page = offset < entries.size()
                ? new ArrayList<>(entries.subList(offset, end))
                : new ArrayList<>();

        return ListResult.builder()
                .path(path)
                .realPath(path)
                .entries(page)
                .hasMore(hasMore)
                .build();
    }

    @Override
    public RemoteFileMeta stat(String serverId, UserContext user, String path) {
        boolean isDir = !path.contains(".");
        return RemoteFileMeta.builder()
                .path(path)
                .realPath(path)
                .type(isDir ? "dir" : "file")
                .size(isDir ? 4096 : 12_345)
                .mode(isDir ? "drwxr-xr-x" : "-rw-r-----")
                .owner("app")
                .group("app")
                .modifiedAt(OffsetDateTime.now().toString())
                .mimeType(isDir ? null : "text/plain")
                .readable(true)
                .previewable(!isDir)
                .build();
    }

    @Override
    public FilePreview preview(String serverId, UserContext user, String path, PreviewOptions options) {
        // Simulate a large file with 12000 log lines (+2 header lines from buildLogText).
        int totalLines = 12000;
        int maxLines = options != null && options.getMaxLines() > 0 ? options.getMaxLines() : 5000;
        String encoding = options != null && options.getEncoding() != null ? options.getEncoding() : "UTF-8";
        String mode = options != null && options.getMode() != null ? options.getMode() : "head";
        String full = buildLogText(serverId, path, user, totalLines);
        byte[] fullBytes = full.getBytes(Charset.forName("UTF-8"));

        if ("range".equals(mode)) {
            // 字节窗口：与真实 Agent 的动态加载行为一致
            long offset = options != null ? Math.max(options.getOffset(), 0) : 0;
            int limit = options != null && options.getLimitBytes() > 0 ? options.getLimitBytes() : 262144;
            int start = (int) Math.min(offset, fullBytes.length);
            int end = Math.min(start + limit, fullBytes.length);
            byte[] window = Arrays.copyOfRange(fullBytes, start, end);
            return FilePreview.builder()
                    .path(path)
                    .realPath(path)
                    .mimeType("text/plain")
                    .encoding(encoding)
                    .size(fullBytes.length)
                    .truncated(end < fullBytes.length)
                    .returnedLines(0)
                    .bytesReturned(window.length)
                    .startOffset(start)
                    .binary(false)
                    .contentBase64(Base64.getEncoder().encodeToString(window))
                    .content(new String(window, Charset.forName("UTF-8")))
                    .build();
        }

        // head / lines：行窗口。lines 模式从 fromLine 开始（文件内搜索跳转上下文）。
        // mock 文本为 ASCII，字符数即字节数。
        int fromLine = "lines".equals(mode) && options != null ? Math.max(options.getFromLine(), 1) : 1;
        String[] allLines = full.split("\n");
        int start = Math.min(fromLine, allLines.length + 1); // 1-based
        int produce = Math.max(0, Math.min(maxLines, allLines.length - start + 1));
        long startOffset = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < start - 1 + produce; i++) {
            if (i < start - 1) {
                startOffset += allLines[i].length() + 1;
            } else {
                sb.append(allLines[i]).append('\n');
            }
        }
        String text = sb.toString();
        byte[] bytes = text.getBytes(Charset.forName("UTF-8"));
        boolean truncated = start - 1 + produce < allLines.length;
        return FilePreview.builder()
                .path(path)
                .realPath(path)
                .mimeType("text/plain")
                .encoding(encoding)
                .size(fullBytes.length)
                .truncated(truncated)
                .returnedLines(produce)
                .bytesReturned(bytes.length)
                .startLine(produce > 0 ? start : fromLine)
                .startOffset(startOffset)
                .binary(false)
                .contentBase64(Base64.getEncoder().encodeToString(bytes))
                .content(text)
                .build();
    }

    @Override
    public SearchResult search(String serverId, UserContext user, String path, SearchOptions options) {
        String query = options != null ? options.getQuery() : null;
        boolean caseSensitive = options != null && options.isCaseSensitive();
        int maxResults = options != null && options.getMaxResults() > 0 ? options.getMaxResults() : 200;
        String haystack = buildLogText(serverId, path, user, 500);
        List<SearchMatch> matches = new ArrayList<>();
        String[] lines = haystack.split("\n");
        String needle = query == null ? "" : (caseSensitive ? query : query.toLowerCase());
        boolean truncated = false;
        for (int i = 0; i < lines.length; i++) {
            String hay = caseSensitive ? lines[i] : lines[i].toLowerCase();
            if (!needle.isEmpty() && hay.contains(needle)) {
                matches.add(SearchMatch.builder().lineNumber(i + 1).line(lines[i]).build());
                if (matches.size() >= maxResults) {
                    truncated = true;
                    break;
                }
            }
        }
        return SearchResult.builder()
                .path(path)
                .realPath(path)
                .matches(matches)
                .truncated(truncated)
                .scannedBytes(haystack.getBytes(Charset.forName("UTF-8")).length)
                .build();
    }

    private String buildLogText(String serverId, String path, UserContext user, int lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mock preview of ").append(path).append("\n");
        sb.append("# server=").append(serverId)
                .append(" user=").append(user != null ? user.getName() : "?").append("\n");
        for (int i = 1; i <= lines; i++) {
            String level = (i % 17 == 0) ? "ERROR" : (i % 5 == 0 ? "WARN" : "INFO");
            sb.append(String.format("2026-07-18T10:%02d:%02d %-5s sample log line %d for %s%n",
                    i % 60, (i * 7) % 60, level, i, path));
        }
        return sb.toString();
    }

    @Override
    public TailResult tail(String serverId, UserContext user, String path, TailOptions options) {
        int lines = options != null && options.getLines() > 0 ? options.getLines() : 500;
        int produce = Math.min(lines, 50);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < produce; i++) {
            sb.append(String.format("2026-07-18T11:%02d:%02d WARN  tail line %d of %s%n", i % 60, (i * 7) % 60, i, path));
        }
        String text = sb.toString();
        String encoding = options != null && options.getEncoding() != null ? options.getEncoding() : "UTF-8";
        return TailResult.builder()
                .path(path)
                .realPath(path)
                .encoding(encoding)
                .size(text.getBytes(Charset.forName("UTF-8")).length)
                .returnedLines(produce)
                .truncated(false)
                .content(text)
                .build();
    }

    private RemoteFileEntry dir(String base, String name) {
        return RemoteFileEntry.builder()
                .name(name)
                .path(join(base, name))
                .type("dir")
                .size(4096)
                .mode("drwxr-xr-x")
                .owner("app")
                .group("app")
                .modifiedAt(OffsetDateTime.now().toString())
                .readable(true)
                .previewable(false)
                .build();
    }

    private RemoteFileEntry file(String base, String name, long size) {
        return RemoteFileEntry.builder()
                .name(name)
                .path(join(base, name))
                .type("file")
                .size(size)
                .mode("-rw-r-----")
                .owner("app")
                .group("app")
                .modifiedAt(OffsetDateTime.now().toString())
                .readable(true)
                .previewable(true)
                .build();
    }

    private String join(String base, String name) {
        if (base.endsWith("/")) {
            return base + name;
        }
        return base + "/" + name;
    }

    private void sort(List<RemoteFileEntry> entries, ListOptions options) {
        String sortBy = options != null && options.getSortBy() != null ? options.getSortBy() : "name";
        boolean asc = options == null || options.getSortOrder() == null || !"desc".equalsIgnoreCase(options.getSortOrder());
        Comparator<RemoteFileEntry> cmp;
        switch (sortBy) {
            case "size":
                cmp = Comparator.comparingLong(RemoteFileEntry::getSize);
                break;
            case "type":
                cmp = Comparator.comparing(RemoteFileEntry::getType);
                break;
            case "modified":
                cmp = Comparator.comparing(RemoteFileEntry::getModifiedAt);
                break;
            default:
                cmp = Comparator.comparing(RemoteFileEntry::getName);
        }
        // Directories first, then the chosen comparator.
        Comparator<RemoteFileEntry> dirFirst = Comparator.comparing((RemoteFileEntry e) -> !"dir".equals(e.getType()));
        entries.sort(dirFirst.thenComparing(asc ? cmp : cmp.reversed()));
    }
}
