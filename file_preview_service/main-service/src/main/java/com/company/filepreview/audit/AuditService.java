package com.company.filepreview.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Persists central audit events. Records both successful and failed operations so
 * denied access attempts are traceable.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(AuditEvent event) {
        try {
            repository.save(event);
        } catch (Exception e) {
            // Never let audit persistence break the request; log and continue.
            log.error("Failed to persist audit event for requestId={}", event.getRequestId(), e);
        }
    }

    public Page<AuditEvent> search(String userId, String serverId, String operation, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 500));
        return repository.search(emptyToNull(userId), emptyToNull(serverId), emptyToNull(operation), pageable);
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }
}
