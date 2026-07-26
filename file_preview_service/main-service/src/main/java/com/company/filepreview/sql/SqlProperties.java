package com.company.filepreview.sql;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL 工作台配置，绑定 application.yml 的 {@code sql.*}。
 */
@Component
@ConfigurationProperties(prefix = "sql")
@Getter
@Setter
public class SqlProperties {

    /** 用于加密存储数据源密码的密钥口令，生产务必修改。 */
    private String secret = "change-this-sql-secret-in-production";

    /** 单次查询默认返回行数。 */
    private int defaultMaxRows = 1000;

    /** 单次查询允许的最大返回行数上限。 */
    private int maxRowsLimit = 100000;

    /** JDBC 查询超时（秒）。 */
    private int queryTimeoutSeconds = 60;

    /** 每个数据源连接池大小。 */
    private int poolSize = 5;
}
