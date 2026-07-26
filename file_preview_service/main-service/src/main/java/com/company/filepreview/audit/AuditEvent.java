package com.company.filepreview.audit;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.Instant;

/**
 * Central, user-facing audit record. Correlates with an Agent's local audit via
 * {@code requestId}.
 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at"),
        @Index(name = "idx_audit_user", columnList = "user_id"),
        @Index(name = "idx_audit_server", columnList = "server_id")
})
@Getter
@Setter
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "user_id")
    private String userId;

    private String username;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "server_id")
    private String serverId;

    /** list | stat | preview | tail | search | download */
    private String operation;

    @Column(length = 4096)
    private String path;

    private boolean success;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "agent_duration_ms")
    private Long agentDurationMs;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "bytes_returned")
    private Long bytesReturned;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
