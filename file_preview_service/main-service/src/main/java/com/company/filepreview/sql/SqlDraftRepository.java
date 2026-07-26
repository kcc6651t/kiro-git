package com.company.filepreview.sql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface SqlDraftRepository extends JpaRepository<SqlDraft, Long> {

    Optional<SqlDraft> findByUserId(Long userId);

    /** 删除早于 cutoff 未更新的草稿。 */
    long deleteByUpdatedAtBefore(Instant cutoff);
}
