package com.company.filepreview.file;

import com.company.filepreview.audit.AuditEvent;
import com.company.filepreview.audit.AuditService;
import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
import com.company.filepreview.common.ApiException;
import com.company.filepreview.config.RequestIdFilter;
import com.company.filepreview.file.model.FilePreview;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.PreviewOptions;
import com.company.filepreview.file.model.RemoteFileMeta;
import com.company.filepreview.file.model.SearchMatch;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.file.model.TailOptions;
import com.company.filepreview.file.model.TailResult;
import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.permission.Operation;
import com.company.filepreview.permission.PathAccessException;
import com.company.filepreview.permission.PathAuthorizer;
import com.company.filepreview.permission.PermissionService;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Set;

/**
 * Orchestrates a browse/preview operation:
 * <ol>
 *   <li>permission check (server + operation),</li>
 *   <li>central-side path authorization (allowedRoots / deniedPaths),</li>
 *   <li>the Agent call (timed),</li>
 *   <li>encoding decode for preview/tail content,</li>
 *   <li>a central audit record for both success and failure.</li>
 * </ol>
 * The Agent performs the authoritative second path check on the target server.
 */
@Service
public class FileBrowseService {

    private final RemoteFileClient client;
    private final ServerRegistry registry;
    private final PathAuthorizer pathAuthorizer;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public FileBrowseService(RemoteFileClient client,
                             ServerRegistry registry,
                             PathAuthorizer pathAuthorizer,
                             PermissionService permissionService,
                             AuditService auditService) {
        this.client = client;
        this.registry = registry;
        this.pathAuthorizer = pathAuthorizer;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    public ListResult list(String serverId, String path, ListOptions options, String clientIp) {
        return run(serverId, path, Operation.LIST, clientIp, (server, normalized, user) -> {
            ListResult r = client.list(serverId, user, normalized, options);
            long bytes = r.getEntries() != null ? r.getEntries().size() : 0;
            return new Outcome<>(r, bytes);
        });
    }

    public RemoteFileMeta stat(String serverId, String path, String clientIp) {
        return run(serverId, path, Operation.STAT, clientIp, (server, normalized, user) -> {
            RemoteFileMeta r = client.stat(serverId, user, normalized);
            return new Outcome<>(r, 0L);
        });
    }

    public FilePreview preview(String serverId, String path, PreviewOptions options, String clientIp) {
        return run(serverId, path, Operation.PREVIEW, clientIp, (server, normalized, user) -> {
            FilePreview r = client.preview(serverId, user, normalized, options);
            decodeContent(r, options != null ? options.getEncoding() : "UTF-8");
            long bytes = r.getBytesReturned() > 0 ? r.getBytesReturned() : r.getSize();
            return new Outcome<>(r, bytes);
        });
    }

    public TailResult tail(String serverId, String path, TailOptions options, String clientIp) {
        return run(serverId, path, Operation.TAIL, clientIp, (server, normalized, user) -> {
            TailResult r = client.tail(serverId, user, normalized, options);
            decodeTail(r, options != null ? options.getEncoding() : "UTF-8");
            return new Outcome<>(r, r.getSize());
        });
    }

    public SearchResult search(String serverId, String path, SearchOptions options, String clientIp) {
        prepareRawQuery(options);
        return run(serverId, path, Operation.SEARCH, clientIp, (server, normalized, user) -> {
            SearchResult r = client.search(serverId, user, normalized, options);
            decodeMatches(r, options != null ? options.getEncoding() : null);
            long bytes = r.getMatches() != null ? r.getMatches().size() : 0;
            return new Outcome<>(r, bytes);
        });
    }

    /**
     * 非 UTF-8 编码文件：把查询串按文件编码转成原始字节（base64）下发，Agent 按字节
     * 匹配并以 lineBase64 返回命中行——否则 UTF-8 查询字节对 GBK 文件永远不匹配。
     */
    private void prepareRawQuery(SearchOptions options) {
        if (options == null || options.getQuery() == null || options.getQuery().isEmpty()) {
            return;
        }
        String enc = options.getEncoding();
        if (enc == null || enc.trim().isEmpty() || "UTF-8".equalsIgnoreCase(enc.trim())) {
            return;
        }
        try {
            options.setQueryBase64(Base64.getEncoder().encodeToString(options.getQuery().getBytes(enc.trim())));
        } catch (Exception e) {
            // 不支持的编码：退回文本模式（与修复前行为一致）
        }
    }

    /** 原始字节模式下把 lineBase64 按文件编码解码回 line，原始字节不外传给前端。 */
    private void decodeMatches(SearchResult r, String encoding) {
        if (r == null || r.getMatches() == null) {
            return;
        }
        for (SearchMatch m : r.getMatches()) {
            if (m.getLineBase64() != null) {
                m.setLine(decode(Base64.getDecoder().decode(m.getLineBase64()), encoding));
                m.setLineBase64(null);
            }
        }
    }

    private <T> T run(String serverId, String path, Operation op, String clientIp, AgentCall<T> call) {
        Set<Role> roles = CurrentUser.roles();
        UserContext user = CurrentUser.context();
        long start = System.currentTimeMillis();
        String normalized = path;
        try {
            permissionService.requireServerAccess(roles, serverId);
            permissionService.requireOperation(roles, op);

            ServerDefinition server = registry.require(serverId);
            normalized = pathAuthorizer.authorize(path, server.getAllowedRoots(), server.getDeniedPaths());

            long agentStart = System.currentTimeMillis();
            Outcome<T> outcome = call.execute(server, normalized, user);
            long agentDuration = System.currentTimeMillis() - agentStart;

            audit(serverId, user, clientIp, op, normalized, true, null,
                    agentDuration, System.currentTimeMillis() - start, outcome.bytes);
            return outcome.value;
        } catch (PathAccessException e) {
            audit(serverId, user, clientIp, op, normalized, false, e.getCode(),
                    null, System.currentTimeMillis() - start, 0L);
            throw ApiException.forbidden(e.getMessage());
        } catch (ApiException e) {
            audit(serverId, user, clientIp, op, normalized, false, e.getCode(),
                    null, System.currentTimeMillis() - start, 0L);
            throw e;
        } catch (IllegalArgumentException e) {
            audit(serverId, user, clientIp, op, normalized, false, "BAD_REQUEST",
                    null, System.currentTimeMillis() - start, 0L);
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void decodeContent(FilePreview preview, String encoding) {
        if (preview == null) {
            return;
        }
        if (preview.isBinary()) {
            preview.setContent(null);
            return;
        }
        if (preview.getContent() != null) {
            return; // already decoded (mock client)
        }
        if (preview.getContentBase64() != null) {
            byte[] raw = Base64.getDecoder().decode(preview.getContentBase64());
            preview.setContent(decode(raw, encoding));
        }
    }

    private void decodeTail(TailResult tail, String encoding) {
        if (tail == null) {
            return;
        }
        if (tail.getContent() != null) {
            return;
        }
        if (tail.getContentBase64() != null) {
            byte[] raw = Base64.getDecoder().decode(tail.getContentBase64());
            tail.setContent(decode(raw, encoding));
        }
    }

    private String decode(byte[] raw, String encoding) {
        String enc = (encoding == null || encoding.trim().isEmpty()) ? "UTF-8" : encoding;
        try {
            return new String(raw, enc);
        } catch (UnsupportedEncodingException e) {
            return new String(raw, Charset.forName("UTF-8"));
        }
    }

    private void audit(String serverId, UserContext user, String clientIp, Operation op, String path,
                       boolean success, String errorCode, Long agentMs, long totalMs, long bytes) {
        AuditEvent event = new AuditEvent();
        event.setRequestId(MDC.get(RequestIdFilter.ATTRIBUTE));
        event.setUserId(user != null ? user.getId() : null);
        event.setUsername(user != null ? user.getName() : null);
        event.setClientIp(clientIp);
        event.setServerId(serverId);
        event.setOperation(op.name().toLowerCase());
        event.setPath(path);
        event.setSuccess(success);
        event.setErrorCode(errorCode);
        event.setAgentDurationMs(agentMs);
        event.setTotalDurationMs(totalMs);
        event.setBytesReturned(bytes);
        auditService.record(event);
    }

    @FunctionalInterface
    private interface AgentCall<T> {
        Outcome<T> execute(ServerDefinition server, String normalizedPath, UserContext user);
    }

    private static final class Outcome<T> {
        final T value;
        final long bytes;

        Outcome(T value, long bytes) {
            this.value = value;
            this.bytes = bytes;
        }
    }
}
