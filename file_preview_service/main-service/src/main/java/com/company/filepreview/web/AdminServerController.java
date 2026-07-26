package com.company.filepreview.web;

import com.company.filepreview.agent.MtlsHttpClientFactory;
import com.company.filepreview.file.RemoteFileClient;
import com.company.filepreview.file.model.AgentCapabilities;
import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Server configuration management for administrators.
 *
 * <p>Servers are declared in {@code servers.yml} (MVP: file-driven, not a database).
 * This controller therefore exposes a read-only view of the full configuration plus
 * a reload action to re-read the file after edits. It is restricted to ADMIN by the
 * security config ({@code /api/admin/**}). This is the extension point for a future
 * editable, DB-backed server registry.</p>
 */
@RestController
@RequestMapping("/api/admin/servers")
public class AdminServerController {

    private final ServerRegistry registry;
    private final RemoteFileClient client;
    private final com.company.filepreview.sync.SyncScheduler syncScheduler;
    private final MtlsHttpClientFactory mtlsClientFactory;

    public AdminServerController(ServerRegistry registry, RemoteFileClient client,
                                 com.company.filepreview.sync.SyncScheduler syncScheduler,
                                 MtlsHttpClientFactory mtlsClientFactory) {
        this.registry = registry;
        this.client = client;
        this.syncScheduler = syncScheduler;
        this.mtlsClientFactory = mtlsClientFactory;
    }

    /** Full configuration of every declared server (enabled or not). */
    @GetMapping
    public List<ServerDefinition> all() {
        return registry.all();
    }

    @GetMapping("/{serverId}")
    public ServerDefinition detail(@PathVariable String serverId) {
        return registry.require(serverId);
    }

    /** Live Agent health + capabilities for the admin status view. */
    @GetMapping("/{serverId}/status")
    public StatusResponse status(@PathVariable String serverId) {
        registry.require(serverId);
        StatusResponse resp = new StatusResponse();
        AgentHealth health = client.health(serverId);
        resp.setHealth(health);
        if ("UP".equalsIgnoreCase(health.getStatus())) {
            try {
                resp.setCapabilities(client.capabilities(serverId));
            } catch (Exception ignored) {
                // capabilities are best-effort for the admin view
            }
        }
        return resp;
    }

    /** Re-read servers.yml from disk after an out-of-band edit. */
    @PostMapping("/reload")
    public ReloadResponse reload() {
        registry.load();
        // 重新加载后重建定时同步调度，使 backup 配置变更生效。
        syncScheduler.reschedule();
        // 证书/serverName/超时可能已变更，丢弃缓存的 mTLS client。
        mtlsClientFactory.invalidateAll();
        ReloadResponse resp = new ReloadResponse();
        resp.setServerCount(registry.all().size());
        return resp;
    }

    /** 读取 servers.yml 原文，供在应用内编辑。 */
    @GetMapping("/config")
    public ConfigResponse getConfig() {
        ConfigResponse resp = new ConfigResponse();
        resp.setPath(registry.configLocation());
        resp.setContent(registry.readRawConfig());
        return resp;
    }

    /** 校验并保存 servers.yml，随后热加载并重建同步调度。 */
    @PutMapping("/config")
    public ReloadResponse saveConfig(@org.springframework.web.bind.annotation.RequestBody ConfigRequest body) {
        try {
            registry.saveRawConfig(body.getContent());
        } catch (IllegalArgumentException e) {
            throw com.company.filepreview.common.ApiException.badRequest(e.getMessage());
        }
        syncScheduler.reschedule();
        // 与 /reload 同理，丢弃缓存的 mTLS client。
        mtlsClientFactory.invalidateAll();
        ReloadResponse resp = new ReloadResponse();
        resp.setServerCount(registry.all().size());
        return resp;
    }

    @Data
    public static class ConfigResponse {
        private String path;
        private String content;
    }

    @Data
    public static class ConfigRequest {
        private String content;
    }

    @Data
    public static class StatusResponse {
        private AgentHealth health;
        private AgentCapabilities capabilities;
    }

    @Data
    public static class ReloadResponse {
        private int serverCount;
    }
}
