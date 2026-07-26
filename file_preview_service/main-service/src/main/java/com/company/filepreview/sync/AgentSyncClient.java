package com.company.filepreview.sync;

import com.company.filepreview.agent.AgentClientProperties;
import com.company.filepreview.agent.MtlsHttpClientFactory;
import com.company.filepreview.common.ApiException;
import com.company.filepreview.config.RequestIdFilter;
import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.sync.model.SyncReadChunk;
import com.company.filepreview.sync.model.SyncScanResult;
import com.company.filepreview.sync.model.SyncWriteCommand;
import com.company.filepreview.sync.model.SyncWriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;

/**
 * 通过 HTTPS + mTLS 调用 Agent 的同步接口。启用条件：{@code filepreview.remote-client=agent}。
 */
@Component
@ConditionalOnProperty(name = "filepreview.remote-client", havingValue = "agent")
public class AgentSyncClient implements SyncClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ServerRegistry registry;
    private final MtlsHttpClientFactory clientFactory;
    private final AgentClientProperties properties;
    private final ObjectMapper mapper;

    public AgentSyncClient(ServerRegistry registry,
                           MtlsHttpClientFactory clientFactory,
                           AgentClientProperties properties,
                           ObjectMapper mapper) {
        this.registry = registry;
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public SyncScanResult scan(String serverId, UserContext user, String dir,
                               List<String> includes, List<String> excludes,
                               List<String> excludeDirs, boolean recursive) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = base(user);
        req.put("dir", dir);
        putStrings(req.putArray("includes"), includes);
        putStrings(req.putArray("excludes"), excludes);
        // 仅在配置时下发：旧版本 Agent 对未知字段拒绝解码（DisallowUnknownFields）。
        if (excludeDirs != null && !excludeDirs.isEmpty()) {
            putStrings(req.putArray("excludeDirs"), excludeDirs);
        }
        req.put("recursive", recursive);
        String body = post(server, "/agent/v1/sync/scan", req);
        return read(body, SyncScanResult.class, "scan");
    }

    @Override
    public SyncReadChunk read(String serverId, UserContext user, String path, long offset, int length) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = base(user);
        req.put("path", path);
        req.put("offset", offset);
        req.put("length", length);
        String body = post(server, "/agent/v1/sync/read", req);
        return read(body, SyncReadChunk.class, "read");
    }

    @Override
    public SyncWriteResult write(String serverId, UserContext user, SyncWriteCommand cmd) {
        ServerDefinition server = registry.require(serverId);
        ObjectNode req = base(user);
        req.put("path", cmd.getPath());
        req.put("offset", cmd.getOffset());
        req.put("last", cmd.isLast());
        req.put("contentBase64", cmd.getContentBase64());
        req.put("mode", cmd.getMode());
        req.put("modTimeMs", cmd.getModTimeMs());
        req.put("uid", cmd.getUid());
        req.put("gid", cmd.getGid());
        req.put("preservePermissions", cmd.isPreservePermissions());
        req.put("preserveOwnership", cmd.isPreserveOwnership());
        String body = post(server, "/agent/v1/sync/write", req);
        return read(body, SyncWriteResult.class, "write");
    }

    private ObjectNode base(UserContext user) {
        ObjectNode req = mapper.createObjectNode();
        req.put("requestId", currentRequestId());
        ObjectNode u = req.putObject("user");
        u.put("id", user != null ? user.getId() : "system");
        u.put("name", user != null ? user.getName() : "system");
        return req;
    }

    private void putStrings(ArrayNode arr, List<String> values) {
        if (values != null) {
            for (String v : values) {
                arr.add(v);
            }
        }
    }

    private <T> T read(String body, Class<T> type, String op) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Invalid sync " + op + " response: " + e.getMessage());
        }
    }

    private String currentRequestId() {
        String rid = MDC.get(RequestIdFilter.ATTRIBUTE);
        return rid != null ? rid : UUID.randomUUID().toString();
    }

    private String post(ServerDefinition server, String apiPath, ObjectNode payload) {
        try {
            RequestBody rb = RequestBody.create(JSON, mapper.writeValueAsBytes(payload));
            Request.Builder builder = new Request.Builder()
                    .url(server.getAgent().getBaseUrl() + apiPath)
                    .header("X-Request-Id", currentRequestId());
            if (properties.getApiToken() != null && !properties.getApiToken().isEmpty()) {
                builder.header("X-Agent-Token", properties.getApiToken());
            }
            Request request = builder.post(rb).build();
            OkHttpClient client = clientFactory.forServer(server);
            try (Response response = client.newCall(request).execute()) {
                ResponseBody rBody = response.body();
                String text = rBody != null ? rBody.string() : "";
                if (response.isSuccessful()) {
                    return text;
                }
                throw mapError(response.code(), text, server.getId());
            }
        } catch (SocketTimeoutException e) {
            throw ApiException.agentTimeout("Sync agent timeout for server " + server.getId());
        } catch (IOException e) {
            throw ApiException.agentUnavailable("Sync agent unreachable for server " + server.getId());
        }
    }

    private ApiException mapError(int status, String body, String serverId) {
        String code = null;
        try {
            code = mapper.readTree(body).path("code").asText(null);
        } catch (Exception ignored) {
            // ignore
        }
        switch (status) {
            case 400:
                return ApiException.badRequest(code != null ? code : "Bad sync request");
            case 403:
                if ("SYNC_WRITE_DISABLED".equals(code)) {
                    return ApiException.forbidden("备机 " + serverId + " 未开启同步写入，请在其 agent.yml 设置 sync.writeEnabled=true 并重启 Agent");
                }
                return ApiException.forbidden(code != null ? code : "Sync forbidden");
            case 404:
                return ApiException.notFound(code != null ? code : "Path not found");
            case 408:
            case 504:
                return ApiException.agentTimeout("Sync agent timeout");
            default:
                return ApiException.agentUnavailable("Sync agent error " + status
                        + (code != null ? " (" + code + ")" : "") + " on " + serverId);
        }
    }
}
