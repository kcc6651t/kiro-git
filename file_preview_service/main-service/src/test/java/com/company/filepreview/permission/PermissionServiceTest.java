package com.company.filepreview.permission;

import com.company.filepreview.auth.Role;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {

    private PermissionService service;

    @BeforeEach
    void setUp() {
        ServerRegistry registry = TestServers.registry();
        service = new PermissionService(registry);
    }

    @Test
    void operatorCanAccessEnabledServer() {
        assertTrue(service.canAccessServer(EnumSet.of(Role.OPERATOR), "srv-a"));
    }

    @Test
    void cannotAccessDisabledServer() {
        assertFalse(service.canAccessServer(EnumSet.of(Role.OPERATOR), "srv-disabled"));
    }

    @Test
    void cannotAccessUnknownServer() {
        assertFalse(service.canAccessServer(EnumSet.of(Role.ADMIN), "does-not-exist"));
    }

    @Test
    void auditorHasNoFileAccess() {
        assertFalse(service.canAccessServer(EnumSet.of(Role.AUDITOR), "srv-a"));
        assertFalse(service.canPerform(EnumSet.of(Role.AUDITOR), Operation.PREVIEW));
    }

    @Test
    void downloadIsAdminOnly() {
        assertTrue(service.canPerform(EnumSet.of(Role.ADMIN), Operation.DOWNLOAD));
        assertFalse(service.canPerform(EnumSet.of(Role.OPERATOR), Operation.DOWNLOAD));
    }

    @Test
    void visibleServersExcludeDisabled() {
        // servers-test.yml 中启用的有 srv-a / srv-b / srv-bad（srv-disabled 除外）
        assertEquals(3, service.visibleServers(EnumSet.of(Role.OPERATOR)).size());
    }

    @Test
    void noRolesSeeNothing() {
        Set<Role> none = Collections.emptySet();
        assertFalse(service.canAccessServer(none, "srv-a"));
        assertThrows(PathAccessException.class, () -> service.requireOperation(none, Operation.LIST));
    }
}
