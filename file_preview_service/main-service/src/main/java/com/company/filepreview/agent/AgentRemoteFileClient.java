package com.company.filepreview.agent;

import com.company.filepreview.common.ApiException;
import com.company.filepreview.config.RequestIdFilter;
import com.company.filepreview.file.RemoteFileClient;
import com.company.filepreview.file.model.AgentCapabilities;
import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.file.model.FilePreview;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.PreviewOptions;
import com.company.filepreview.file.model.RemoteFileMeta;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.file.model.TailOptions;
import com.company.filepreview.file.model.TailResult;
import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * Calls a target server Agent over HTTPS + mTLS. Activated with
 * {@code filepreview.remote-client=agent}.
 */
@Component
@ConditionalOnProperty(name = "filepreview.remote-client", havingValue = "agent")
public class AgentRemoteFileClient implements RemoteFileClient {

    private static final Logger log = LoggerFactory.getLogger(AgentRemoteFileClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ServerRegistry registry;
    private final MtlsHttpClientFactory clientFactory;
    private final AgentClientProperties properties;
    private final ObjectMapper mapper;

    public AgentRemoteFileClient(ServerRegistry registry,
                                 MtlsHttpClientFactory clientFactory,
                                 AgentClientProperties properties,
                                 ObjectMapper mapper) {
        this.registry = registry;
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public AgentHealth health(String serverId) {
        ServerDefinition server = registry.require(serverId);
        try {
            String body = get(server, "/agent/v1/health");
            AgentHealth health = mapper.readValue(body, AgentHealth.class);
            if (health.getServerId() == null) {
                health.setServerId(serverId);
            }
            return health;
        } catch (Exception e) {
            return AgentHealth.builder().serverId(serverId).status("DOWN").error(e.getMessage()).build();
        }
    }

    @Override
    public AgentCapabilities capabilities(String serverId) {
        ServerDefinition server = registry.require(serverId);
        try {
            String body = get(server, "/agent/v1/capabilities");
            return mapper.readValue(body, AgentCapabilities.class);
        } catch (Exception e) {
            throw ApiException.agentUnavailable("capabilities failed: " + e.getMessage());
        }
    }

    @Override
    public ListResult list(String serverId, UserContext user, String path, ListOptions options) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = baseRequest(user, path);
        ObjectNode opts = req.putObject("options");
        opts.put("showHidden", options.isShowHidden());
        opts.put("sortBy", options.getSortBy());
        opts.put("sortOrder", options.getSortOrder());
        opts.put("offset", options.getOffset());
        opts.put("limit", options.getLimit());
        String body = post(server, "/agent/v1/files/list", req);
        try {
            return mapper.readValue(body, ListResult.class);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid list response: " + e.getMessage());
        }
    }

    @Override
    public RemoteFileMeta stat(String serverId, UserContext user, String path) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = baseRequest(user, path);
        String body = post(server, "/agent/v1/files/meta", req);
        try {
            return mapper.readValue(body, RemoteFileMeta.class);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid meta response: " + e.getMessage());
        }
    }

    @Override
    public FilePreview preview(String serverId, UserContext user, String path, PreviewOptions options) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = baseRequest(user, path);
        ObjectNode opts = req.putObject("options");
        opts.put("mode", options.getMode());
        opts.put("offset", options.getOffset());
        opts.put("limitBytes", options.getLimitBytes());
        opts.put("maxLines", options.getMaxLines());
        opts.put("fromLine", options.getFromLine());
        opts.put("encoding", options.getEncoding());
        String body = post(server, "/agent/v1/files/preview", req);
        try {
            return mapper.readValue(body, FilePreview.class);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid preview response: " + e.getMessage());
        }
    }

    @Override
    public TailResult tail(String serverId, UserContext user, String path, TailOptions options) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = baseRequest(user, path);
        ObjectNode opts = req.putObject("options");
        opts.put("lines", options.getLines());
        opts.put("encoding", options.getEncoding());
        String body = post(server, "/agent/v1/files/tail", req);
        try {
            return mapper.readValue(body, TailResult.class);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid tail response: " + e.getMessage());
        }
    }

    @Override
    public SearchResult search(String serverId, UserContext user, String path, SearchOptions options) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = baseRequest(user, path);
        ObjectNode opts = req.putObject("options");
        opts.put("query", options.getQuery());
        opts.put("caseSensitive", options.isCaseSensitive());
        opts.put("maxResults", options.getMaxResults());
        opts.put("maxScanBytes", options.getMaxScanBytes());
        opts.put("encoding", options.getEncoding());
        // 仅非 UTF-8 搜索时下发（旧版本 Agent 对未知字段拒绝解码）
        if (options.getQueryBase64() != null) {
            opts.put("queryBase64", options.getQueryBase64());
        }
        String body = post(server, "/agent/v1/files/search", req);
        try {
            return mapper.readValue(body, SearchResult.class);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid search response: " + e.getMessage());
        }
    }

    private ObjectNode baseRequest(UserContext user, String path) {
        ObjectNode req = mapper.createObjectNode();
        req.put("requestId", currentRequestId());
        ObjectNode u = req.putObject("user");
        u.put("id", user != null ? user.getId() : "unknown");
        u.put("name", user != null ? user.getName() : "unknown");
        req.put("path", path);
        return req;
    }

    private String currentRequestId() {
        String rid = MDC.get(RequestIdFilter.ATTRIBUTE);
        return rid != null ? rid : UUID.randomUUID().toString();
    }

    private String get(ServerDefinition server, String apiPath) {
        Request request = addToken(new Request.Builder()
                .url(server.getAgent().getBaseUrl() + apiPath)
                .header("X-Request-Id", currentRequestId()))
                .get()
                .build();
        return execute(server, request);
    }

    private String post(ServerDefinition server, String apiPath, ObjectNode payload) {
        try {
            RequestBody rb = RequestBody.create(JSON, mapper.writeValueAsBytes(payload));
            Request request = addToken(new Request.Builder()
                    .url(server.getAgent().getBaseUrl() + apiPath)
                    .header("X-Request-Id", currentRequestId()))
                    .post(rb)
                    .build();
            return execute(server, request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("Failed to serialize agent request");
        }
    }

    private Request.Builder addToken(Request.Builder builder) {
        if (properties.getApiToken() != null && !properties.getApiToken().isEmpty()) {
            builder.header("X-Agent-Token", properties.getApiToken());
        }
        return builder;
    }

    private String execute(ServerDefinition server, Request request) {
        OkHttpClient client = clientFactory.forServer(server);
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            if (response.isSuccessful()) {
                return text;
            }
            throw mapError(response.code(), text);
        } catch (SocketTimeoutException e) {
            throw ApiException.agentTimeout("Agent request timed out for server " + server.getId());
        } catch (IOException e) {
            log.warn("Agent call failed for server {}: {}", server.getId(), e.getMessage());
            throw ApiException.agentUnavailable("Agent unreachable for server " + server.getId());
        }
    }

    private ApiException mapError(int httpStatus, String body) {
        String agentCode = extractCode(body);
        // Agent 错误体里的 message 是面向用户的可读原因，优先透传
        String agentMessage = extractMessage(body);
        switch (httpStatus) {
            case 400:
                return new ApiException(HttpStatus.BAD_REQUEST,
                        agentCode != null ? agentCode : "BAD_REQUEST",
                        hasText(agentMessage) ? agentMessage : "Bad request to agent");
            case 403:
                return new ApiException(HttpStatus.FORBIDDEN,
                        agentCode != null ? agentCode : "FORBIDDEN",
                        hasText(agentMessage) ? agentMessage : "Agent denied access");
            case 404:
                return new ApiException(HttpStatus.NOT_FOUND,
                        agentCode != null ? agentCode : "NOT_FOUND",
                        hasText(agentMessage) ? agentMessage : "Path not found");
            case 408:
            case 504:
                return ApiException.agentTimeout("Agent timeout");
            default:
                return ApiException.agentUnavailable("Agent error " + httpStatus
                        + (agentCode != null ? " (" + agentCode + ")" : ""));
        }
    }

    private String extractCode(String body) {
        try {
            return mapper.readTree(body).path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractMessage(String body) {
        try {
            return mapper.readTree(body).path("message").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
