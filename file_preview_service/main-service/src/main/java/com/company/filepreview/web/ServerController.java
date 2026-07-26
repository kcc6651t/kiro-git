package com.company.filepreview.web;

import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
import com.company.filepreview.common.ApiException;
import com.company.filepreview.file.RemoteFileClient;
import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.permission.PermissionService;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server registry and Agent status endpoints.
 */
@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerRegistry registry;
    private final PermissionService permissionService;
    private final RemoteFileClient client;

    public ServerController(ServerRegistry registry,
                            PermissionService permissionService,
                            RemoteFileClient client) {
        this.registry = registry;
        this.permissionService = permissionService;
        this.client = client;
    }

    @GetMapping
    public List<ServerSummary> list() {
        Set<Role> roles = CurrentUser.roles();
        return permissionService.visibleServers(roles).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/{serverId}")
    public ServerDetail detail(@PathVariable String serverId) {
        requireAccess(serverId);
        ServerDefinition def = registry.require(serverId);
        ServerDetail detail = new ServerDetail();
        detail.setId(def.getId());
        detail.setName(def.getName());
        detail.setEnv(def.getEnv());
        detail.setTags(def.getTags());
        detail.setDefaultRoot(def.getDefaultRoot());
        detail.setAllowedRoots(def.getAllowedRoots());
        List<Bookmark> bms = new ArrayList<>();
        if (def.getBookmarks() != null) {
            for (ServerDefinition.BookmarkDefinition b : def.getBookmarks()) {
                Bookmark bm = new Bookmark();
                bm.setName(b.getName());
                bm.setPath(b.getPath());
                bms.add(bm);
            }
        }
        detail.setBookmarks(bms);
        return detail;
    }

    @GetMapping("/{serverId}/roots")
    public RootsResponse roots(@PathVariable String serverId) {
        requireAccess(serverId);
        ServerDefinition def = registry.require(serverId);
        RootsResponse resp = new RootsResponse();
        resp.setDefaultRoot(def.getDefaultRoot());
        resp.setAllowedRoots(def.getAllowedRoots());
        return resp;
    }

    @GetMapping("/{serverId}/health")
    public AgentHealth health(@PathVariable String serverId) {
        requireAccess(serverId);
        return client.health(serverId);
    }

    private void requireAccess(String serverId) {
        if (!permissionService.canAccessServer(CurrentUser.roles(), serverId)) {
            throw ApiException.forbidden("Not authorized for server " + serverId);
        }
    }

    private ServerSummary toSummary(ServerDefinition def) {
        ServerSummary s = new ServerSummary();
        s.setId(def.getId());
        s.setName(def.getName());
        s.setEnv(def.getEnv());
        s.setTags(def.getTags());
        s.setDefaultRoot(def.getDefaultRoot());
        return s;
    }

    @Data
    public static class ServerSummary {
        private String id;
        private String name;
        private String env;
        private List<String> tags;
        private String defaultRoot;
    }

    @Data
    public static class ServerDetail {
        private String id;
        private String name;
        private String env;
        private List<String> tags;
        private String defaultRoot;
        private List<String> allowedRoots;
        private List<Bookmark> bookmarks;
    }

    @Data
    public static class Bookmark {
        private String name;
        private String path;
    }

    @Data
    public static class RootsResponse {
        private String defaultRoot;
        private List<String> allowedRoots;
    }
}
