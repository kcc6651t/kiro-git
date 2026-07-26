package com.company.filepreview.permission;

import com.company.filepreview.auth.Role;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which servers and operations a user may access.
 *
 * <p>MVP model, driven by role:</p>
 * <ul>
 *   <li>{@code ADMIN} and {@code OPERATOR} may access all enabled servers and all
 *       read operations except DOWNLOAD (download is off by default).</li>
 *   <li>{@code AUDITOR} may not access file content at all.</li>
 * </ul>
 *
 * <p>The method signatures accept a user's roles and a server id, which is the
 * natural extension point for a future per-user / per-server grant table without
 * touching call sites.</p>
 */
@Service
public class PermissionService {

    private final ServerRegistry serverRegistry;

    public PermissionService(ServerRegistry serverRegistry) {
        this.serverRegistry = serverRegistry;
    }

    public boolean canAccessServer(Set<Role> roles, String serverId) {
        if (!hasFileAccess(roles)) {
            return false;
        }
        return serverRegistry.find(serverId).map(ServerDefinition::isEnabled).orElse(false);
    }

    public boolean canPerform(Set<Role> roles, Operation operation) {
        if (operation == Operation.DOWNLOAD) {
            // Download is off by default; only ADMIN may (and only when explicitly enabled elsewhere).
            return roles.contains(Role.ADMIN);
        }
        return hasFileAccess(roles);
    }

    /** Servers visible to the given roles. */
    public List<ServerDefinition> visibleServers(Set<Role> roles) {
        List<ServerDefinition> result = new ArrayList<>();
        if (!hasFileAccess(roles)) {
            return result;
        }
        for (ServerDefinition def : serverRegistry.enabled()) {
            result.add(def);
        }
        return result;
    }

    public void requireServerAccess(Set<Role> roles, String serverId) {
        if (!canAccessServer(roles, serverId)) {
            throw new PathAccessException("SERVER_FORBIDDEN", "Not authorized for server " + serverId);
        }
    }

    public void requireOperation(Set<Role> roles, Operation operation) {
        if (!canPerform(roles, operation)) {
            throw new PathAccessException("OPERATION_FORBIDDEN", "Operation not permitted: " + operation);
        }
    }

    private boolean hasFileAccess(Set<Role> roles) {
        return roles.contains(Role.ADMIN) || roles.contains(Role.OPERATOR);
    }
}
