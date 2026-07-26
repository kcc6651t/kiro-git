package com.company.filepreview.sync;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    @Query("select j from SyncJob j where (:serverId is null or j.serverId = :serverId) order by j.startedAt desc")
    Page<SyncJob> search(@Param("serverId") String serverId, Pageable pageable);

    List<SyncJob> findTop1ByServerIdOrderByStartedAtDesc(String serverId);

    /** 删除早于 cutoff 的已结束任务（RUNNING 不删，防止误清执行中的记录）。 */
    long deleteByStartedAtBeforeAndStatusNot(java.time.Instant cutoff, String status);
}
