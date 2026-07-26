package com.company.filepreview.bookmark;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * A user or global bookmark to a server path.
 */
@Entity
@Table(name = "bookmark")
@Getter
@Setter
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private String serverId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 4096)
    private String path;

    @Column(name = "scope_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private ScopeType scopeType = ScopeType.personal;

    /** For team scope, the group identifier; for personal, unused. */
    @Column(name = "scope_ref")
    private String scopeRef;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public enum ScopeType {
        personal, team, global
    }
}
