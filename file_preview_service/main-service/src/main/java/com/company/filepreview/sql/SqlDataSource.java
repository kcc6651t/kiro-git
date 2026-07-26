package com.company.filepreview.sql;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/** 一个 MySQL 数据源配置。密码以密文存储。 */
@Entity
@Table(name = "sql_data_source")
@Getter
@Setter
public class SqlDataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名称，唯一。 */
    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port = 3306;

    /** 数据库类型，目前仅 mysql。 */
    @Column(name = "db_type", length = 32)
    private String dbType = "mysql";

    /** 默认连接的数据库（可空，MySQL 下可留空以浏览所有库）。 */
    @Column(name = "default_database")
    private String defaultDatabase;

    @Column(nullable = false)
    private String username;

    /** 加密后的密码。 */
    @Column(name = "password_enc", length = 1024)
    private String passwordEnc;

    /** 额外 JDBC 参数，如 useSSL=false&characterEncoding=utf8。 */
    @Column(length = 1024)
    private String params;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
