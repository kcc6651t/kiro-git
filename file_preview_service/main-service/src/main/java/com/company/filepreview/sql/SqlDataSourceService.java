package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源的 CRUD、批量导入，以及每个数据源一个 HikariDataSource 连接池的缓存管理。
 * 密码使用 {@link CryptoService} 加密存储，仅在建立连接时解密。
 */
@Service
public class SqlDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(SqlDataSourceService.class);
    private static final String DRIVER = "com.mysql.jdbc.Driver";

    private final SqlDataSourceRepository repository;
    private final CryptoService crypto;
    private final SqlProperties properties;

    /** dataSourceId -> 连接池。懒创建，配置变更时失效。 */
    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public SqlDataSourceService(SqlDataSourceRepository repository, CryptoService crypto, SqlProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.properties = properties;
    }

    public List<SqlDataSource> list() {
        return repository.findAll();
    }

    public SqlDataSource get(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("数据源不存在"));
    }

    public SqlDataSource create(String name, String host, int port, String defaultDatabase,
                                String username, String password, String params) {
        if (name == null || name.trim().isEmpty()) {
            throw ApiException.badRequest("数据源名称不能为空");
        }
        if (repository.existsByName(name.trim())) {
            throw ApiException.badRequest("数据源名称已存在: " + name);
        }
        SqlDataSource ds = new SqlDataSource();
        ds.setName(name.trim());
        ds.setHost(host);
        ds.setPort(port <= 0 ? 3306 : port);
        ds.setDbType("mysql");
        ds.setDefaultDatabase(emptyToNull(defaultDatabase));
        ds.setUsername(username);
        ds.setPasswordEnc(crypto.encrypt(password));
        ds.setParams(emptyToNull(params));
        ds.setCreatedAt(Instant.now());
        ds.setUpdatedAt(Instant.now());
        return repository.save(ds);
    }

    public SqlDataSource update(Long id, String name, String host, int port, String defaultDatabase,
                                String username, String password, String params) {
        SqlDataSource ds = get(id);
        if (name != null && !name.trim().isEmpty() && !name.trim().equals(ds.getName())) {
            if (repository.existsByName(name.trim())) {
                throw ApiException.badRequest("数据源名称已存在: " + name);
            }
            ds.setName(name.trim());
        }
        // 仅覆盖请求中提供的字段，省略的字段保持原值（port 为原始类型，DTO 默认 3306）
        if (host != null) {
            ds.setHost(host);
        }
        ds.setPort(port <= 0 ? 3306 : port);
        if (defaultDatabase != null) {
            ds.setDefaultDatabase(emptyToNull(defaultDatabase));
        }
        if (username != null) {
            ds.setUsername(username);
        }
        // 密码为空表示不修改
        if (password != null && !password.isEmpty()) {
            ds.setPasswordEnc(crypto.encrypt(password));
        }
        if (params != null) {
            ds.setParams(emptyToNull(params));
        }
        ds.setUpdatedAt(Instant.now());
        SqlDataSource saved = repository.save(ds);
        evict(id);
        return saved;
    }

    public void delete(Long id) {
        SqlDataSource ds = get(id);
        repository.delete(ds);
        evict(id);
    }

    /** 批量导入。返回成功导入的数量；重名的跳过。 */
    public int importBatch(List<ImportItem> items) {
        if (items == null) {
            throw ApiException.badRequest("导入内容不能为空");
        }
        int imported = 0;
        for (ImportItem it : items) {
            if (it == null || it.getName() == null || it.getName().trim().isEmpty() || repository.existsByName(it.getName().trim())) {
                continue;
            }
            create(it.getName(), it.getHost(), it.getPort(), it.getDefaultDatabase(),
                    it.getUsername(), it.getPassword(), it.getParams());
            imported++;
        }
        return imported;
    }

    /** 使用一次性连接测试可用性，不进入连接池缓存。 */
    public void testConnection(Long id) {
        SqlDataSource ds = get(id);
        HikariConfig cfg = baseConfig(ds, 1);
        try (HikariDataSource temp = new HikariDataSource(cfg); Connection c = temp.getConnection()) {
            c.isValid(5);
        } catch (SQLException | PoolInitializationException e) {
            // 池初始化失败（地址不通/认证失败等）抛 RuntimeException，同样映射为 400
            throw ApiException.badRequest("连接失败: " + e.getMessage());
        }
    }

    /** 获取（或懒创建）该数据源的连接。调用方负责关闭。 */
    public Connection getConnection(Long id) {
        HikariDataSource pool;
        try {
            pool = pools.computeIfAbsent(id, k -> new HikariDataSource(baseConfig(get(k), properties.getPoolSize())));
        } catch (PoolInitializationException e) {
            // 池初始化失败（地址不通/认证失败等）抛 RuntimeException，映射为 400 而非 500
            throw ApiException.badRequest("连接失败: " + e.getMessage());
        }
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw ApiException.badRequest("获取连接失败: " + e.getMessage());
        }
    }

    private HikariConfig baseConfig(SqlDataSource ds, int poolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildUrl(ds));
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(crypto.decrypt(ds.getPasswordEnc()));
        cfg.setDriverClassName(DRIVER);
        cfg.setMaximumPoolSize(Math.max(1, poolSize));
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(600_000);
        cfg.setPoolName("sqlwb-" + ds.getId());
        return cfg;
    }

    private String buildUrl(SqlDataSource ds) {
        StringBuilder sb = new StringBuilder("jdbc:mysql://");
        sb.append(ds.getHost()).append(':').append(ds.getPort()).append('/');
        if (ds.getDefaultDatabase() != null) {
            sb.append(ds.getDefaultDatabase());
        }
        String params = ds.getParams();
        // 5.1.47 驱动默认值合理化；未指定时补充常用参数
        if (params == null || params.isEmpty()) {
            params = "useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&zeroDateTimeBehavior=convertToNull";
        }
        sb.append('?').append(params);
        return sb.toString();
    }

    private void evict(Long id) {
        HikariDataSource pool = pools.remove(id);
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                log.warn("关闭连接池失败 id={}", id, e);
            }
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(p -> {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        });
        pools.clear();
    }

    /** 批量导入的单项。 */
    public static class ImportItem {
        private String name;
        private String host;
        private int port = 3306;
        private String defaultDatabase;
        private String username;
        private String password;
        private String params;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDefaultDatabase() { return defaultDatabase; }
        public void setDefaultDatabase(String defaultDatabase) { this.defaultDatabase = defaultDatabase; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getParams() { return params; }
        public void setParams(String params) { this.params = params; }
    }
}
