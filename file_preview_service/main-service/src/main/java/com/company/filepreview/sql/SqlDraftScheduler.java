package com.company.filepreview.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Date;

/**
 * SQL 草稿定期清理：启动 5 分钟后首跑，之后每天一次（保留 15 天，见 SqlDraftService）。
 */
@Component
public class SqlDraftScheduler {

    private static final Logger log = LoggerFactory.getLogger(SqlDraftScheduler.class);
    private static final long FIRST_DELAY_MS = 5 * 60 * 1000;
    private static final long PERIOD_MS = 24 * 60 * 60 * 1000;

    private final SqlDraftService draftService;
    private ThreadPoolTaskScheduler scheduler;

    public SqlDraftScheduler(SqlDraftService draftService) {
        this.draftService = draftService;
    }

    @PostConstruct
    public void start() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sql-draft-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::safePurge,
                new Date(System.currentTimeMillis() + FIRST_DELAY_MS), PERIOD_MS);
    }

    private void safePurge() {
        try {
            draftService.purgeOldDrafts();
        } catch (Exception e) {
            log.warn("清理 SQL 草稿失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
