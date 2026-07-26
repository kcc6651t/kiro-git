package com.company.filepreview.sql;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

/** 一个用户的 SQL 工作台草稿（内容 JSON 原样存取，后端不解析）。每用户一份。 */
@Entity
@Table(name = "sql_draft", indexes = @Index(name = "idx_sql_draft_updated", columnList = "updated_at"))
@Getter
@Setter
public class SqlDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 草稿属主（app_user.id），唯一。 */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 工作台状态 JSON（数据源/标签组/SQL 内容/激活标签）。 */
    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
