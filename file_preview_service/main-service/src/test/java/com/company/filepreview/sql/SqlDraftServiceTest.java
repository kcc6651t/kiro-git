package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlDraftServiceTest {

    private SqlDraftRepository repo;
    private SqlDraftService service;

    @BeforeEach
    void setUp() {
        repo = mock(SqlDraftRepository.class);
        when(repo.save(any(SqlDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new SqlDraftService(repo);
    }

    @Test
    void saveCreatesNewDraftWhenAbsent() {
        when(repo.findByUserId(7L)).thenReturn(Optional.empty());

        SqlDraft d = service.save(7L, "{\"v\":1}");

        assertEquals(7L, d.getUserId());
        assertEquals("{\"v\":1}", d.getContent());
        assertNotNull(d.getUpdatedAt());
        verify(repo).save(d);
    }

    @Test
    void saveUpdatesExistingDraftInPlace() {
        SqlDraft existing = new SqlDraft();
        existing.setUserId(7L);
        existing.setContent("old");
        when(repo.findByUserId(7L)).thenReturn(Optional.of(existing));

        SqlDraft d = service.save(7L, "new");

        assertSame(existing, d);
        assertEquals("new", d.getContent());
    }

    @Test
    void oversizedContentRejected() {
        String big = new String(new char[SqlDraftService.MAX_CONTENT_LENGTH + 1]).replace('\0', 'x');

        ApiException ex = assertThrows(ApiException.class, () -> service.save(7L, big));

        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void purgeUsesFifteenDayCutoff() {
        service.purgeOldDrafts();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByUpdatedAtBefore(cutoff.capture());
        long skewSeconds = Math.abs(Duration.between(cutoff.getValue(),
                Instant.now().minus(SqlDraftService.DRAFT_RETENTION_DAYS, ChronoUnit.DAYS)).getSeconds());
        assertTrue(skewSeconds < 60, "清理阈值应为当前时间往前 15 天，实际偏移 " + skewSeconds + "s");
    }

    @Test
    void getReturnsNullWhenAbsent() {
        when(repo.findByUserId(1L)).thenReturn(Optional.empty());
        assertNull(service.get(1L));
    }
}
