package com.company.filepreview.sync;

import com.company.filepreview.file.model.UserContext;
import com.company.filepreview.server.ServerDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 按每台服务器备机配置的 cron 定时触发同步。调用 {@link #reschedule()} 可在
 * servers.yml 重新加载后重建调度。
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private static final UserContext SYSTEM = UserContext.builder().id("system").name("system").build();

    private final SyncService syncService;
    private ThreadPoolTaskScheduler scheduler;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostConstruct
    public synchronized void start() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        schedule();
    }

    /** 重新读取备机配置并重建调度（用于 servers.yml 热加载后）。 */
    public synchronized void reschedule() {
        for (ScheduledFuture<?> f : futures) {
            f.cancel(false);
        }
        futures.clear();
        schedule();
    }

    private void schedule() {
        int count = 0;
        for (ServerDefinition s : syncService.backupServers()) {
            ServerDefinition.BackupConfig b = s.getBackup();
            if (b == null || !b.isEnabled()) {
                continue;
            }
            String cron = b.getSchedule();
            if (cron == null || cron.trim().isEmpty()) {
                continue;
            }
            final String serverId = s.getId();
            try {
                ScheduledFuture<?> f = scheduler.schedule(() -> safeRun(serverId), new CronTrigger(cron.trim()));
                if (f != null) {
                    futures.add(f);
                    count++;
                }
            } catch (Exception e) {
                log.error("注册同步调度失败 server={} cron={}: {}", serverId, cron, e.getMessage());
            }
        }
        log.info("已注册 {} 个定时同步任务", count);
        // 同步历史定期清理（保留 3 天）：启动 1 分钟后首跑，之后每小时一次。
        ScheduledFuture<?> purge = scheduler.scheduleWithFixedDelay(
                this::safePurge, new Date(System.currentTimeMillis() + 60_000), 3_600_000);
        if (purge != null) {
            futures.add(purge);
        }
    }

    private void safePurge() {
        try {
            syncService.purgeOldJobs();
        } catch (Exception e) {
            log.warn("清理同步历史失败: {}", e.getMessage());
        }
    }

    private void safeRun(String serverId) {
        try {
            syncService.runBackup(serverId, "SCHEDULED", SYSTEM);
        } catch (Exception e) {
            log.warn("定时同步执行失败 server={}: {}", serverId, e.getMessage());
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
