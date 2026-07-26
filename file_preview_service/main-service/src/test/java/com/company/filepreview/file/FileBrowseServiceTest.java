package com.company.filepreview.file;

import com.company.filepreview.audit.AuditEvent;
import com.company.filepreview.audit.AuditService;
import com.company.filepreview.auth.AppUserDetails;
import com.company.filepreview.auth.Role;
import com.company.filepreview.auth.UserAccount;
import com.company.filepreview.common.ApiException;
import com.company.filepreview.file.model.ListOptions;
import com.company.filepreview.file.model.ListResult;
import com.company.filepreview.file.model.RemoteFileEntry;
import com.company.filepreview.file.model.SearchMatch;
import com.company.filepreview.file.model.SearchOptions;
import com.company.filepreview.file.model.SearchResult;
import com.company.filepreview.permission.PathAuthorizer;
import com.company.filepreview.permission.PermissionService;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.support.TestServers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileBrowseServiceTest {

    private RemoteFileClient client;
    private AuditService auditService;
    private FileBrowseService service;

    @BeforeEach
    void setUp() {
        ServerRegistry registry = TestServers.registry();
        client = mock(RemoteFileClient.class);
        auditService = mock(AuditService.class);
        PermissionService permissionService = new PermissionService(registry);
        service = new FileBrowseService(client, registry, new PathAuthorizer(), permissionService, auditService);
        authenticateAs("operator", Role.OPERATOR);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, Role role) {
        UserAccount account = new UserAccount();
        account.setId(42L);
        account.setUsername(username);
        account.setDisplayName(username);
        account.setPasswordHash("x");
        account.setRoles(new HashSet<>(Collections.singletonList(role)));
        AppUserDetails details = new AppUserDetails(account);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "x", details.getAuthorities()));
    }

    @Test
    void listSucceedsAndRecordsAudit() {
        ListResult stub = ListResult.builder()
                .path("/data/logs")
                .realPath("/data/logs")
                .entries(Collections.singletonList(RemoteFileEntry.builder().name("app.log").build()))
                .hasMore(false)
                .build();
        when(client.list(eq("srv-a"), any(), eq("/data/logs"), any())).thenReturn(stub);

        ListResult result = service.list("srv-a", "/data/logs", ListOptions.defaults(), "10.0.0.9");

        assertEquals(1, result.getEntries().size());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertTrue(event.isSuccess());
        assertEquals("list", event.getOperation());
        assertEquals("srv-a", event.getServerId());
        assertEquals("operator", event.getUsername());
        assertEquals("10.0.0.9", event.getClientIp());
    }

    @Test
    void deniedPathIsForbiddenAndAudited() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.list("srv-a", "/data/logs/secret.key", ListOptions.defaults(), "10.0.0.9"));
        assertEquals(403, ex.getStatus().value());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        assertFalse(captor.getValue().isSuccess());
        assertEquals("DENIED_PATH", captor.getValue().getErrorCode());
    }

    @Test
    void outsideAllowedRootIsForbidden() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.list("srv-a", "/etc/passwd", ListOptions.defaults(), "10.0.0.9"));
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    void gbkSearchUsesRawByteQueryAndDecodesMatches() throws Exception {
        byte[] gbkLine = "错误 Error 发生".getBytes("GBK");
        SearchResult stub = SearchResult.builder()
                .path("/data/logs/app.log")
                .matches(Collections.singletonList(SearchMatch.builder()
                        .lineNumber(2)
                        .lineBase64(Base64.getEncoder().encodeToString(gbkLine))
                        .build()))
                .truncated(false)
                .build();
        when(client.search(eq("srv-a"), any(), eq("/data/logs/app.log"), any())).thenReturn(stub);

        SearchOptions options = SearchOptions.defaults("错误");
        options.setEncoding("GBK");
        SearchResult result = service.search("srv-a", "/data/logs/app.log", options, "10.0.0.9");

        // 查询串已按 GBK 编码为原始字节下发
        assertEquals(Base64.getEncoder().encodeToString("错误".getBytes("GBK")), options.getQueryBase64());
        // 命中行被解码为可读文本，lineBase64 不外传
        assertEquals("错误 Error 发生", result.getMatches().get(0).getLine());
        assertNull(result.getMatches().get(0).getLineBase64());
    }

    @Test
    void utf8SearchKeepsTextMode() {
        SearchResult stub = SearchResult.builder()
                .path("/data/logs/app.log")
                .matches(Collections.singletonList(SearchMatch.builder().lineNumber(1).line("plain").build()))
                .truncated(false)
                .build();
        when(client.search(eq("srv-a"), any(), eq("/data/logs/app.log"), any())).thenReturn(stub);

        SearchOptions options = SearchOptions.defaults("plain");
        options.setEncoding("UTF-8");
        SearchResult result = service.search("srv-a", "/data/logs/app.log", options, "10.0.0.9");

        assertNull(options.getQueryBase64());
        assertEquals("plain", result.getMatches().get(0).getLine());
    }
}
