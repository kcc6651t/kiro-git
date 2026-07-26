package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SQL 工作台草稿：每用户一份，upsert 读写；超期未更新的由 SqlDraftScheduler 定期清理。
 */
@Service
public class SqlDraftService {

    private static final Logger log = LoggerFactory.getLogger(SqlDraftService.class);
    /** 草稿内容上限（防滥用）。 */
    static final int MAX_CONTENT_LENGTH = 256 * 1024;
    /** 草稿保留天数（按最后更新时间）。 */
    static final int DRAFT_RETENTION_DAYS = 15;

    private final SqlDraftRepository repository;

    public SqlDraftService(SqlDraftRepository repository) {
        this.repository = repository;
    }

    /** 读取用户草稿；无草稿返回 null。 */
    public SqlDraft get(long userId) {
        return repository.findByUserId(userId).orElse(null);
    }

    /** 保存（upsert）用户草稿并返回落库后的实体。 */
    public SqlDraft save(long userId, String content) {
        String body = content != null ? content : "";
        if (body.length() > MAX_CONTENT_LENGTH) {
            throw ApiException.badRequest("草稿内容超过 256KB 上限");
        }
        SqlDraft draft = repository.findByUserId(userId).orElseGet(() -> {
            SqlDraft d = new SqlDraft();
            d.setUserId(userId);
            return d;
        });
        draft.setContent(body);
        draft.setUpdatedAt(Instant.now());
        return repository.save(draft);
    }

    /** 清理超过 {@link #DRAFT_RETENTION_DAYS} 天未更新的草稿，由调度器定期调用。 */
    @Transactional
    public long purgeOldDrafts() {
        Instant cutoff = Instant.now().minus(DRAFT_RETENTION_DAYS, ChronoUnit.DAYS);
        long removed = repository.deleteByUpdatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("清理 {} 份超过 {} 天未更新的 SQL 草稿", removed, DRAFT_RETENTION_DAYS);
        }
        return removed;
    }
}
