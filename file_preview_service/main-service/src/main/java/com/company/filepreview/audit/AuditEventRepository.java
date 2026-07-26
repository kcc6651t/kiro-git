package com.company.filepreview.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    @Query("select a from AuditEvent a where " +
            "(:userId is null or a.userId = :userId) and " +
            "(:serverId is null or a.serverId = :serverId) and " +
            "(:operation is null or a.operation = :operation) " +
            "order by a.createdAt desc")
    Page<AuditEvent> search(@Param("userId") String userId,
                            @Param("serverId") String serverId,
                            @Param("operation") String operation,
                            Pageable pageable);
}
