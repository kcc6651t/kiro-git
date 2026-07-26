package com.company.filepreview.sync;

import com.company.filepreview.common.ApiException;
import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.permission.PathAccessException;
import com.company.filepreview.permission.PathAuthorizer;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.sync.model.SyncFileInfo;
import com.company.filepreview.sync.model.SyncReadChunk;
import com.company.filepreview.sync.model.SyncScanResult;
import com.company.filepreview.sync.model.SyncWriteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主备同步编排：扫描主机 → 增量比较备机 → 分块读主机/写备机 → 定稿保持权限 → 记录统计。
 *
 * <p>同一服务器的同步任务串行执行（重入将被跳过），避免并发写冲突；单条规则内的
 * 文件传输按固定并行度并发（小文件同步主要耗在网络往返）。单文件失败自动重试
 * 瞬时错误；RUNNING 期间进度节流落库供前端轮询；历史任务定期清理。</p>
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int REQUEST_CHUNK = 4 * 1024 * 1024;
    private static final int MAX_ERROR_SAMPLES = 20;
    /** 单条错误采样写入 message 前的最大长度，防止超长错误挤爆 message 列。 */
    private static final int MAX_ERROR_SAMPLE_LENGTH = 300;
    /** SyncJob.message 列长 4096，拼接后的整体最大长度（留有余量）。 */
    private static final int MAX_MESSAGE_LENGTH = 4000;
    /** 单文件传输的最大尝试次数，仅对 5xx 类瞬时错误重试（4xx 立即失败）。 */
    private static final int MAX_TRANSFER_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 300;
    /** RUNNING 期间进度落库的最小间隔（供前端轮询显示百分比）。 */
    private static final long PROGRESS_SAVE_INTERVAL_MS = 1500;
    /** 明细日志最多保留的行数，超出截断并标注。 */
    private static final int MAX_DETAIL_LINES = 500;
    /** 同步历史任务的保留天数。 */
    static final int JOB_RETENTION_DAYS = 3;
    /** 规则内的并行传输度：海量小文件场景下串行 RTT 是主要瓶颈。 */
    private static final int TRANSFER_PARALLELISM = 4;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SyncClient client;
    private final ServerRegistry registry;
    private final SyncJobRepository jobRepository;
    private final PathAuthorizer pathAuthorizer;

    private final Set<String> running = ConcurrentHashMap.newKeySet();
    // 后台执行手动触发的同步任务；守护线程，关闭时不阻塞退出。
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sync-exec");
        t.setDaemon(true);
        return t;
    });
    // 规则内并行传输文件的 IO 线程池；守护线程。
    private final ExecutorService transferPool = Executors.newFixedThreadPool(TRANSFER_PARALLELISM, r -> {
        Thread t = new Thread(r, "sync-io");
        t.setDaemon(true);
        return t;
    });

    public SyncService(SyncClient client,
                       ServerRegistry registry,
                       SyncJobRepository jobRepository,
                       PathAuthorizer pathAuthorizer) {
        this.client = client;
        this.registry = registry;
        this.jobRepository = jobRepository;
        this.pathAuthorizer = pathAuthorizer;
    }

    /** 配置了备机的服务器列表。 */
    public List<ServerDefinition> backupServers() {
        List<ServerDefinition> result = new ArrayList<>();
        for (ServerDefinition s : registry.all()) {
            if (s.getBackup() != null && s.getBackup().getTargetServerId() != null) {
                result.add(s);
            }
        }
        return result;
    }

    public boolean isRunning(String serverId) {
        return running.contains(serverId);
    }

    public Page<SyncJob> jobs(String serverId, int page, int size) {
        return jobRepository.search(
                (serverId == null || serverId.trim().isEmpty()) ? null : serverId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    public SyncJob lastJob(String serverId) {
        List<SyncJob> list = jobRepository.findTop1ByServerIdOrderByStartedAtDesc(serverId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 同步执行一台服务器的备机同步（阻塞至完成），供定时调度与测试使用。
     *
     * @param trigger SCHEDULED 或 MANUAL
     */
    public SyncJob runBackup(String serverId, String trigger, UserContext user) {
        Prepared p = prepare(serverId);
        acquireOrThrow(serverId);
        SyncJob job = createRunningJob(p, trigger);
        executeAndFinalize(p, job, user);
        return job;
    }

    /**
     * 异步提交一台服务器的备机同步：立即持久化 RUNNING 任务并返回，实际同步在后台线程执行。
     * 供手动触发使用，避免长耗时同步阻塞 HTTP 请求（及反向代理超时）。前端可轮询任务状态。
     */
    public SyncJob submitBackup(String serverId, String trigger, UserContext user) {
        Prepared p = prepare(serverId);
        acquireOrThrow(serverId);
        SyncJob job = createRunningJob(p, trigger);
        try {
            executor.submit(() -> executeAndFinalize(p, job, user));
        } catch (RuntimeException e) {
            // 关闭窗口（shutdownNow 后 submit 被拒）：释放锁并把任务落为 FAILED，避免残留 RUNNING 与重入锁
            running.remove(serverId);
            job.setStatus("FAILED");
            job.setFinishedAt(Instant.now());
            try {
                jobRepository.save(job);
            } catch (Exception saveEx) {
                log.error("保存同步任务失败 server={}", serverId, saveEx);
            }
            throw e;
        }
        return job;
    }

    private Prepared prepare(String serverId) {
        ServerDefinition source = registry.require(serverId);
        ServerDefinition.BackupConfig backup = source.getBackup();
        if (backup == null || backup.getTargetServerId() == null) {
            throw ApiException.badRequest("服务器未配置备机: " + serverId);
        }
        ServerDefinition target = registry.find(backup.getTargetServerId())
                .orElseThrow(() -> ApiException.badRequest("备机不存在: " + backup.getTargetServerId()));
        return new Prepared(source, target, backup);
    }

    private void acquireOrThrow(String serverId) {
        if (!running.add(serverId)) {
            throw ApiException.badRequest("该服务器的同步任务正在进行中");
        }
    }

    private SyncJob createRunningJob(Prepared p, String trigger) {
        SyncJob job = new SyncJob();
        job.setServerId(p.source.getId());
        job.setTargetServerId(p.target.getId());
        job.setTrigger(trigger);
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        try {
            jobRepository.save(job); // 立即持久化，使轮询可见 RUNNING 状态
        } catch (Exception e) {
            log.error("保存 RUNNING 同步任务失败 server={}", p.source.getId(), e);
        }
        return job;
    }

    /** 执行全部规则并落终态统计；释放并发锁。异常安全，绝不抛出。 */
    private void executeAndFinalize(Prepared p, SyncJob job, UserContext user) {
        String serverId = p.source.getId();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        DetailLog detail = new DetailLog();
        Progress progress = new Progress(job);
        try {
            for (ServerDefinition.SyncRule rule : p.backup.getRules()) {
                runRule(p.source, p.target, rule, user, job, errors, detail, progress);
            }
            // 有失败维持原逻辑；无失败但扫描不完整（源目录被截断）只能算部分成功
            if (job.getFilesFailed() > 0) {
                job.setStatus(job.getFilesSynced() > 0 ? "PARTIAL" : "FAILED");
            } else if (progress.isIncomplete()) {
                job.setStatus("PARTIAL");
            } else {
                job.setStatus("SUCCESS");
            }
        } catch (PathAccessException e) {
            job.setStatus("FAILED");
            errors.add("路径校验失败: " + e.getMessage());
            detail.add("ERROR 路径校验失败: " + e.getMessage());
        } catch (ApiException e) {
            job.setStatus("FAILED");
            errors.add(e.getCode() + ": " + e.getMessage());
            detail.add("ERROR " + e.getCode() + ": " + e.getMessage());
        } catch (Exception e) {
            job.setStatus("FAILED");
            errors.add("异常: " + e.getMessage());
            detail.add("ERROR 异常: " + e.getMessage());
            log.error("同步任务异常 server={}", serverId, e);
        } finally {
            running.remove(serverId);
            job.setFinishedAt(Instant.now());
            if (!errors.isEmpty()) {
                // message 列长 4096，整体截断防止终态落库失败导致任务卡 RUNNING
                job.setMessage(truncate(String.join(" | ", errors), MAX_MESSAGE_LENGTH));
            }
            detail.add(String.format("DONE %s total=%d synced=%d skipped=%d failed=%d bytes=%d",
                    job.getStatus(), job.getFilesTotal(), job.getFilesSynced(),
                    job.getFilesSkipped(), job.getFilesFailed(), job.getBytesTransferred()));
            job.setDetail(detail.render());
            try {
                jobRepository.save(job);
            } catch (Exception e) {
                log.error("保存同步统计失败 server={}", serverId, e);
            }
        }
    }

    /** 清理超过保留期（{@link #JOB_RETENTION_DAYS} 天）的历史任务，由 SyncScheduler 定期调用。 */
    @Transactional
    public long purgeOldJobs() {
        Instant cutoff = Instant.now().minus(JOB_RETENTION_DAYS, ChronoUnit.DAYS);
        long removed = jobRepository.deleteByStartedAtBeforeAndStatusNot(cutoff, "RUNNING");
        if (removed > 0) {
            log.info("清理 {} 条超过 {} 天的同步历史", removed, JOB_RETENTION_DAYS);
        }
        return removed;
    }

    /** 单个任务的逐文件明细日志（体积较大，与分页列表分离获取）。 */
    public String jobDetail(long id) {
        SyncJob job = jobRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("同步任务不存在: " + id));
        return job.getDetail() != null ? job.getDetail() : "";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        transferPool.shutdownNow();
    }

    /** 一次同步任务的解析结果。 */
    private static final class Prepared {
        final ServerDefinition source;
        final ServerDefinition target;
        final ServerDefinition.BackupConfig backup;

        Prepared(ServerDefinition source, ServerDefinition target, ServerDefinition.BackupConfig backup) {
            this.source = source;
            this.target = target;
            this.backup = backup;
        }
    }

    private void runRule(ServerDefinition source, ServerDefinition target,
                         ServerDefinition.SyncRule rule, UserContext user,
                         SyncJob job, List<String> errors, DetailLog detail, Progress progress) {
        String sourceDir = rule.getSourceDir();
        String targetDir = (rule.getTargetDir() == null || rule.getTargetDir().trim().isEmpty())
                ? sourceDir : rule.getTargetDir();

        // 中心侧路径授权：源目录须在主机 allowedRoots，目标目录须在备机 allowedRoots。
        pathAuthorizer.authorize(sourceDir, source.getAllowedRoots(), source.getDeniedPaths());
        pathAuthorizer.authorize(targetDir, target.getAllowedRoots(), target.getDeniedPaths());

        SyncScanResult srcScan = client.scan(source.getId(), user, sourceDir,
                rule.getIncludes(), rule.getExcludes(), rule.getExcludeDirs(), rule.isRecursive());

        // 源目录文件数超出 Agent maxFiles 时结果被截断：记录警告并把任务标记为不完整
        if (srcScan.isTruncated()) {
            String warn = "源扫描结果被截断（超过 Agent maxFiles 上限），仅同步部分文件: " + sourceDir;
            errors.add(truncate(warn, MAX_ERROR_SAMPLE_LENGTH));
            detail.add("WARN " + warn);
            progress.markIncomplete();
        }

        // 备机现有文件（增量比较）；备机未开启 sync 读时降级为全量。
        Map<String, SyncFileInfo> dstMap = new HashMap<>();
        try {
            SyncScanResult dstScan = client.scan(target.getId(), user, targetDir,
                    rule.getIncludes(), rule.getExcludes(), rule.getExcludeDirs(), rule.isRecursive());
            if (dstScan.getFiles() != null) {
                for (SyncFileInfo f : dstScan.getFiles()) {
                    dstMap.put(f.getRelPath(), f);
                }
            }
        } catch (Exception e) {
            log.warn("备机扫描失败，改为全量同步 target={} dir={}: {}", target.getId(), targetDir, e.getMessage());
            detail.add("WARN 备机扫描失败，改为全量同步: " + e.getMessage());
        }

        List<SyncFileInfo> files = srcScan.getFiles() != null ? srcScan.getFiles() : new ArrayList<>();
        String ruleName = rule.getName() != null ? rule.getName() : sourceDir;
        // 总数在扫描后即确定并落库，RUNNING 期间前端即可计算进度百分比。
        progress.addTotal(files.size());
        detail.add("RULE " + ruleName + " " + sourceDir + " -> " + targetDir
                + ", scanned " + files.size() + " file(s)");

        // 主线程先做增量比较：未变化的直接跳过，待传输的收集后并行传输。
        List<SyncFileInfo> toTransfer = new ArrayList<>();
        for (SyncFileInfo src : files) {
            SyncFileInfo dst = dstMap.get(src.getRelPath());
            if (dst != null && dst.getSize() == src.getSize() && dst.getModTimeMs() == src.getModTimeMs()) {
                progress.incSkipped();
            } else {
                toTransfer.add(src);
            }
        }

        List<Callable<Void>> tasks = new ArrayList<>();
        for (SyncFileInfo src : toTransfer) {
            tasks.add(() -> {
                String srcPath = joinPath(sourceDir, src.getRelPath());
                String dstPath = joinPath(targetDir, src.getRelPath());
                try {
                    long bytes = transferWithRetry(source.getId(), target.getId(),
                            srcPath, dstPath, src, rule, user, detail);
                    progress.incSynced(bytes);
                    detail.add("SYNC " + src.getRelPath() + " " + bytes + " B");
                } catch (Exception e) {
                    progress.incFailed();
                    detail.add("FAIL " + src.getRelPath() + ": " + e.getMessage());
                    if (errors.size() < MAX_ERROR_SAMPLES) {
                        // 单条采样截断：拼接后写入 message 列（4096），防止超长错误撑爆列
                        errors.add(truncate(src.getRelPath() + ": " + e.getMessage(), MAX_ERROR_SAMPLE_LENGTH));
                    }
                    log.warn("同步文件失败 {} -> {}: {}", srcPath, dstPath, e.getMessage());
                }
                return null;
            });
        }
        try {
            transferPool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail.add("ABORTED 传输被中断");
            // 中断后任务不得按正常完成计状态：抛出后由 executeAndFinalize 统一置 FAILED
            throw new SyncInterruptedException("同步任务被中断");
        }
        detail.add("RULE-DONE " + ruleName + " scanned=" + files.size()
                + " unchanged=" + (files.size() - toTransfer.size())
                + " transfer=" + toTransfer.size());
    }

    /** 传输线程被中断时抛出，executeAndFinalize 捕获后将任务置为 FAILED。 */
    private static final class SyncInterruptedException extends RuntimeException {
        SyncInterruptedException(String message) {
            super(message);
        }
    }

    /** 截断超长字符串，null 安全。 */
    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 传输单个文件，对 5xx 类瞬时错误整体重传。重传从 offset=0 开始，
     * 备机临时文件在 offset=0 时删除重建，因此重试是幂等的。
     */
    private long transferWithRetry(String sourceId, String targetId, String srcPath, String dstPath,
                                   SyncFileInfo src, ServerDefinition.SyncRule rule, UserContext user,
                                   DetailLog detail) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transferFile(sourceId, targetId, srcPath, dstPath, src, rule, user);
            } catch (ApiException e) {
                boolean transientError = e.getStatus() != null && e.getStatus().is5xxServerError();
                if (!transientError || attempt >= MAX_TRANSFER_ATTEMPTS) {
                    throw e;
                }
                detail.add("RETRY " + src.getRelPath() + " attempt " + (attempt + 1)
                        + "/" + MAX_TRANSFER_ATTEMPTS + ": " + e.getMessage());
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 线程安全的明细日志收集器，按行截断（海量小文件时防止 detail 无限膨胀）。 */
    private static final class DetailLog {
        private final List<String> lines = new ArrayList<>();
        private int dropped;

        synchronized void add(String line) {
            if (lines.size() < MAX_DETAIL_LINES) {
                lines.add("[" + LocalTime.now().format(TIME_FMT) + "] " + line);
            } else {
                dropped++;
            }
        }

        synchronized String render() {
            String body = String.join("\n", lines);
            return dropped > 0 ? body + "\n... (truncated, " + dropped + " more lines)" : body;
        }
    }

    /** RUNNING 期间的进度计数与节流落库；供并行传输线程并发调用。 */
    private final class Progress {
        private final SyncJob job;
        private long lastSave;
        /** 源扫描被截断等导致结果不完整时置位，终态不得为 SUCCESS。 */
        private boolean incomplete;

        Progress(SyncJob job) {
            this.job = job;
        }

        synchronized void markIncomplete() {
            incomplete = true;
        }

        synchronized boolean isIncomplete() {
            return incomplete;
        }

        synchronized void addTotal(int n) {
            job.setFilesTotal(job.getFilesTotal() + n);
            saveIfDue();
        }

        synchronized void incSkipped() {
            job.setFilesSkipped(job.getFilesSkipped() + 1);
            saveIfDue();
        }

        synchronized void incSynced(long bytes) {
            job.setFilesSynced(job.getFilesSynced() + 1);
            job.setBytesTransferred(job.getBytesTransferred() + bytes);
            saveIfDue();
        }

        synchronized void incFailed() {
            job.setFilesFailed(job.getFilesFailed() + 1);
            saveIfDue();
        }

        private void saveIfDue() {
            long now = System.currentTimeMillis();
            if (now - lastSave < PROGRESS_SAVE_INTERVAL_MS) {
                return;
            }
            lastSave = now;
            try {
                jobRepository.save(job);
            } catch (Exception e) {
                log.warn("保存同步进度失败: {}", e.getMessage());
            }
        }
    }

    private long transferFile(String sourceId, String targetId, String srcPath, String dstPath,
                              SyncFileInfo src, ServerDefinition.SyncRule rule, UserContext user) {
        long offset = 0;
        long total = 0;
        while (true) {
            SyncReadChunk chunk = client.read(sourceId, user, srcPath, offset, REQUEST_CHUNK);
            String b64 = chunk.getContentBase64() != null ? chunk.getContentBase64() : "";
            int len = b64.isEmpty() ? 0 : Base64.getDecoder().decode(b64).length;

            SyncWriteCommand cmd = SyncWriteCommand.builder()
                    .path(dstPath)
                    .offset(offset)
                    .last(chunk.isEof())
                    .contentBase64(b64)
                    .mode(src.getMode())
                    .modTimeMs(src.getModTimeMs())
                    .uid(src.getUid())
                    .gid(src.getGid())
                    .preservePermissions(rule.isPreservePermissions())
                    .preserveOwnership(rule.isPreserveOwnership())
                    .build();
            client.write(targetId, user, cmd);

            total += len;
            offset += len;
            if (chunk.isEof()) {
                break;
            }
            if (len == 0) {
                // 防御：非 eof 但无数据。文件未定稿不能计为已同步——按瞬时错误抛出，交给重试/失败逻辑
                throw ApiException.agentUnavailable("agent returned no data before eof: " + srcPath);
            }
        }
        return total;
    }

    private String joinPath(String base, String rel) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String r = rel.startsWith("/") ? rel.substring(1) : rel;
        return b + "/" + r;
    }
}
