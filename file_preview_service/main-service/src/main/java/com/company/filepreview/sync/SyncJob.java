package com.company.filepreview.sync;

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

/** 一次备机同步任务的执行统计。 */
@Entity
@Table(name = "sync_job", indexes = {
        @Index(name = "idx_sync_started", columnList = "started_at"),
        @Index(name = "idx_sync_server", columnList = "server_id")
})
@Getter
@Setter
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id")
    private String serverId;

    @Column(name = "target_server_id")
    private String targetServerId;

    /** SCHEDULED | MANUAL */
    private String trigger;

    /** RUNNING | SUCCESS | PARTIAL | FAILED */
    private String status;

    @Column(name = "started_at")
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "files_total")
    private int filesTotal;

    @Column(name = "files_synced")
    private int filesSynced;

    @Column(name = "files_skipped")
    private int filesSkipped;

    @Column(name = "files_failed")
    private int filesFailed;

    @Column(name = "bytes_transferred")
    private long bytesTransferred;

    @Column(length = 4096)
    private String message;

    /**
     * 逐文件明细日志（规则扫描/传输/失败/汇总，截断保留前若干行）。
     * 体积较大，列表接口不返回，经 /jobs/{id}/detail 单独获取。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @javax.persistence.Lob
    private String detail;
}
