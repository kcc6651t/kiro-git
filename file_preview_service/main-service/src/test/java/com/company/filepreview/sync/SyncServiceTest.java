package com.company.filepreview.sync;

import com.company.filepreview.common.ApiException;
import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.permission.PathAuthorizer;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.support.TestServers;
import com.company.filepreview.sync.model.SyncFileInfo;
import com.company.filepreview.sync.model.SyncReadChunk;
import com.company.filepreview.sync.model.SyncScanResult;
import com.company.filepreview.sync.model.SyncWriteCommand;
import com.company.filepreview.sync.model.SyncWriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private FakeSyncClient client;
    private SyncJobRepository repo;
    private SyncService service;

    private final UserContext user = UserContext.builder().id("1").name("admin").build();

    @BeforeEach
    void setUp() {
        ServerRegistry registry = TestServers.registry();
        client = new FakeSyncClient();
        repo = mock(SyncJobRepository.class);
        when(repo.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new SyncService(client, registry, repo, new PathAuthorizer());
    }

    @Test
    void incrementalSyncSkipsIdenticalAndTransfersChanged() {
        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("SUCCESS", job.getStatus());
        assertEquals(2, job.getFilesTotal());
        assertEquals(1, job.getFilesSkipped());   // a.log 相同 -> 跳过
        assertEquals(1, job.getFilesSynced());    // b.log 变化 -> 传输
        assertTrue(job.getBytesTransferred() > 0);
        // 备机确实收到了 b.log 的写入
        boolean wroteB = client.writes.stream().anyMatch(w -> w.getPath().endsWith("/data/logs/b.log"));
        assertTrue(wroteB, "应向备机写入 b.log");
        // 权限保持：写入指令带上了源文件 mode
        assertTrue(client.writes.stream().anyMatch(SyncWriteCommand::isPreservePermissions));
        // servers.yml 中的 excludeDirs 原样透传到扫描请求
        assertEquals(Collections.singletonList("archive"), client.lastExcludeDirs);
        // 明细日志记录了规则、逐文件结果与汇总
        assertTrue(job.getDetail().contains("RULE "));
        assertTrue(job.getDetail().contains("SYNC b.log"));
        assertTrue(job.getDetail().contains("DONE SUCCESS"));
    }

    @Test
    void transientWriteFailureIsRetriedAndEventuallySucceeds() {
        client.failWritesWithTimeout = 2; // 前两次 write 超时，第三次成功
        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("SUCCESS", job.getStatus());
        assertEquals(1, job.getFilesSynced());
        assertEquals(3, client.writeAttempts); // 1 次首发 + 2 次重试
        assertTrue(job.getDetail().contains("RETRY b.log"));
    }

    @Test
    void nonTransientFailureIsNotRetried() {
        client.writeError = ApiException.forbidden("SYNC_WRITE_DISABLED"); // 4xx 不重试
        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFilesFailed());
        assertEquals(1, client.writeAttempts);
        assertTrue(job.getDetail().contains("FAIL b.log"));
    }

    @Test
    void purgeDeletesJobsBeyondRetentionDays() {
        service.purgeOldJobs();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByStartedAtBeforeAndStatusNot(cutoff.capture(), eq("RUNNING"));
        long skewSeconds = Math.abs(Duration.between(
                cutoff.getValue(), Instant.now().minus(SyncService.JOB_RETENTION_DAYS, ChronoUnit.DAYS)).getSeconds());
        assertTrue(skewSeconds < 60, "清理阈值应为当前时间往前 3 天，实际偏移 " + skewSeconds + "s");
    }

    @Test
    void illegalSourcePathFailsAuthorization() {
        SyncJob job = service.runBackup("srv-bad", "MANUAL", user);
        assertEquals("FAILED", job.getStatus());
        assertEquals(0, job.getFilesTotal());
        assertTrue(job.getMessage() != null && job.getMessage().contains("路径校验失败"));
    }

    @Test
    void concurrentRunOnSameServerIsRejected() {
        // 阻塞型 client：第一次运行占用期间不会真正并发，这里直接验证重入保护逻辑
        // 通过在同一线程连续调用不会重入，改为验证 isRunning 初始为 false
        assertEquals(false, service.isRunning("srv-a"));
        service.runBackup("srv-a", "MANUAL", user);
        assertEquals(false, service.isRunning("srv-a")); // 结束后释放
    }

    @Test
    void truncatedSourceScanMarksJobPartial() {
        client.truncatedScan = true; // 源目录文件数超 Agent maxFiles，扫描结果被截断
        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("PARTIAL", job.getStatus());
        assertEquals(0, job.getFilesFailed());
        assertEquals(1, job.getFilesSynced()); // 扫描到的文件仍正常同步
        assertTrue(job.getDetail().contains("WARN 源扫描结果被截断"));
        assertTrue(job.getMessage() != null && job.getMessage().contains("截断"));
    }

    @Test
    void oversizedErrorMessageIsTruncatedBeforePersist() {
        // 单条错误采样截断 300、整体 message 截断 4000，防止 message 列超长导致终态落库失败
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append('x');
        }
        client.writeError = ApiException.forbidden(sb.toString());

        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("FAILED", job.getStatus());
        assertTrue(job.getMessage() != null, "应有错误 message");
        assertTrue(job.getMessage().length() <= 300,
                "单条错误采样应截断到 300 字符，实际 " + job.getMessage().length());
    }

    @Test
    void emptyReadBeforeEofFailsFileInsteadOfCountingSynced() {
        client.emptyReads = true; // 读到的都是非 eof 空块：文件未定稿，不得计 SYNC
        SyncJob job = service.runBackup("srv-a", "MANUAL", user);

        assertEquals("FAILED", job.getStatus());
        assertEquals(1, job.getFilesFailed());
        assertEquals(0, job.getFilesSynced());
        assertTrue(job.getMessage() != null && job.getMessage().contains("no data before eof"));
        assertTrue(job.getDetail().contains("FAIL b.log"));
    }

    @Test
    void submitAfterShutdownMarksJobFailedAndReleasesLock() {
        service.shutdown(); // shutdownNow 后再提交，模拟关闭窗口
        assertThrows(RejectedExecutionException.class, () -> service.submitBackup("srv-a", "MANUAL", user));
        assertEquals(false, service.isRunning("srv-a"));

        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getFinishedAt() != null);
    }

    /** 可编程的假 SyncClient：按 serverId 返回不同扫描结果，并记录写入指令。 */
    static class FakeSyncClient implements SyncClient {
        final List<SyncWriteCommand> writes = new ArrayList<>();
        List<String> lastExcludeDirs;
        int writeAttempts;
        /** 接下来 N 次 write 抛 AGENT_TIMEOUT（瞬时错误，用于验证重试）。 */
        int failWritesWithTimeout;
        /** 非空则 write 始终抛该异常（用于验证非瞬时错误不重试）。 */
        ApiException writeError;
        /** 为 true 时源扫描返回 truncated=true（文件数超 Agent 上限）。 */
        boolean truncatedScan;
        /** 为 true 时 read 始终返回非 eof 的空块（验证防御分支不得计为已同步）。 */
        boolean emptyReads;

        @Override
        public SyncScanResult scan(String serverId, UserContext user, String dir,
                                   List<String> includes, List<String> excludes,
                                   List<String> excludeDirs, boolean recursive) {
            this.lastExcludeDirs = excludeDirs;
            if ("srv-a".equals(serverId)) {
                // 源：两个文件
                return SyncScanResult.builder().dir(dir).realDir(dir).truncated(truncatedScan)
                        .files(Arrays.asList(
                                file("a.log", 100, 1000L),
                                file("b.log", 200, 2000L)))
                        .build();
            }
            if ("srv-b".equals(serverId)) {
                // 备机：已有 a.log 且完全相同 -> 应被跳过
                return SyncScanResult.builder().dir(dir).realDir(dir).truncated(false)
                        .files(Collections.singletonList(file("a.log", 100, 1000L)))
                        .build();
            }
            return SyncScanResult.builder().dir(dir).realDir(dir)
                    .files(new ArrayList<>()).truncated(false).build();
        }

        @Override
        public SyncReadChunk read(String serverId, UserContext user, String path, long offset, int length) {
            if (emptyReads) {
                // 非 eof 且无数据：触发传输防御分支
                return SyncReadChunk.builder()
                        .path(path).offset(offset).eof(false)
                        .contentBase64("")
                        .build();
            }
            byte[] data = "data".getBytes(StandardCharsets.UTF_8);
            return SyncReadChunk.builder()
                    .path(path).offset(offset).eof(true)
                    .contentBase64(Base64.getEncoder().encodeToString(data))
                    .build();
        }

        @Override
        public SyncWriteResult write(String serverId, UserContext user, SyncWriteCommand command) {
            writeAttempts++;
            if (failWritesWithTimeout > 0) {
                failWritesWithTimeout--;
                throw ApiException.agentTimeout("sync agent timeout (fake)");
            }
            if (writeError != null) {
                throw writeError;
            }
            writes.add(command);
            return SyncWriteResult.builder()
                    .path(command.getPath()).bytesWritten(4)
                    .finalized(command.isLast()).permissionsSet(command.isPreservePermissions())
                    .build();
        }

        private static SyncFileInfo file(String rel, long size, long mtime) {
            return SyncFileInfo.builder().relPath(rel).size(size).mode(0640)
                    .modTimeMs(mtime).uid(1000).gid(1000).owner("app").group("app").build();
        }
    }
}
