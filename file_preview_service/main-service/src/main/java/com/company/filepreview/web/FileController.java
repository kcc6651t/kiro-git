package com.company.filepreview.web;

import com.company.filepreview.file.FileBrowseService;
import com.company.filepreview.file.model.FilePreview;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.PreviewOptions;
import com.company.filepreview.file.model.RemoteFileMeta;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.file.model.TailOptions;
import com.company.filepreview.file.model.TailResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * Read-only file browsing endpoints proxied to the target server Agent.
 *
 * <p>No write endpoints are exposed. Adding upload/delete/edit later means adding
 * methods here plus a client method and relaxing the Agent's read-only guard.</p>
 */
@RestController
@RequestMapping("/api/servers/{serverId}/files")
public class FileController {

    private final FileBrowseService browseService;

    public FileController(FileBrowseService browseService) {
        this.browseService = browseService;
    }

    @GetMapping
    public ListResult list(@PathVariable String serverId,
                           @RequestParam String path,
                           @RequestParam(defaultValue = "false") boolean showHidden,
                           @RequestParam(defaultValue = "name") String sortBy,
                           @RequestParam(defaultValue = "asc") String sortOrder,
                           @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(defaultValue = "200") int limit,
                           HttpServletRequest request) {
        ListOptions options = ListOptions.builder()
                .showHidden(showHidden)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .offset(offset)
                .limit(limit)
                .build();
        return browseService.list(serverId, path, options, HttpUtils.clientIp(request));
    }

    @GetMapping("/meta")
    public RemoteFileMeta meta(@PathVariable String serverId,
                               @RequestParam String path,
                               HttpServletRequest request) {
        return browseService.stat(serverId, path, HttpUtils.clientIp(request));
    }

    @GetMapping("/preview")
    public FilePreview preview(@PathVariable String serverId,
                               @RequestParam String path,
                               @RequestParam(defaultValue = "head") String mode,
                               @RequestParam(defaultValue = "0") long offset,
                               @RequestParam(defaultValue = "5242880") int limitBytes,
                               @RequestParam(defaultValue = "5000") int lines,
                               @RequestParam(defaultValue = "0") int fromLine,
                               @RequestParam(defaultValue = "UTF-8") String encoding,
                               HttpServletRequest request) {
        PreviewOptions options = PreviewOptions.builder()
                .mode(mode)
                .offset(offset)
                .limitBytes(limitBytes)
                .maxLines(lines)
                .fromLine(fromLine)
                .encoding(encoding)
                .build();
        return browseService.preview(serverId, path, options, HttpUtils.clientIp(request));
    }

    @GetMapping("/tail")
    public TailResult tail(@PathVariable String serverId,
                           @RequestParam String path,
                           @RequestParam(defaultValue = "500") int lines,
                           @RequestParam(defaultValue = "UTF-8") String encoding,
                           HttpServletRequest request) {
        TailOptions options = TailOptions.builder()
                .lines(lines)
                .encoding(encoding)
                .build();
        return browseService.tail(serverId, path, options, HttpUtils.clientIp(request));
    }

    @GetMapping("/search")
    public SearchResult search(@PathVariable String serverId,
                               @RequestParam String path,
                               @RequestParam String q,
                               @RequestParam(defaultValue = "false") boolean caseSensitive,
                               @RequestParam(defaultValue = "200") int maxResults,
                               @RequestParam(defaultValue = "UTF-8") String encoding,
                               HttpServletRequest request) {
        SearchOptions options = SearchOptions.builder()
                .query(q)
                .caseSensitive(caseSensitive)
                .maxResults(maxResults)
                .maxScanBytes(64L * 1024 * 1024)
                .encoding(encoding)
                .build();
        return browseService.search(serverId, path, options, HttpUtils.clientIp(request));
    }
}
