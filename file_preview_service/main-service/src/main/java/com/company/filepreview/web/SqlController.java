package com.company.filepreview.web;

import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.auth.Role;
import com.company.filepreview.common.ApiException;
import com.company.filepreview.sql.SqlDataSource;
import com.company.filepreview.sql.SqlDataSourceService;
import com.company.filepreview.sql.SqlDraft;
import com.company.filepreview.sql.SqlDraftService;
import com.company.filepreview.sql.SqlExecService;
import com.company.filepreview.sql.SqlMetadataService;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 工作台接口。数据源的增删改由 ADMIN 管理；ADMIN 与 OPERATOR 可浏览元数据、执行 SQL；
 * AUDITOR 无权访问（仅审计文件，不触达数据）。
 */
@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private final SqlDataSourceService dataSourceService;
    private final SqlMetadataService metadataService;
    private final SqlExecService execService;
    private final SqlDraftService draftService;

    public SqlController(SqlDataSourceService dataSourceService, SqlMetadataService metadataService,
                         SqlExecService execService, SqlDraftService draftService) {
        this.dataSourceService = dataSourceService;
        this.metadataService = metadataService;
        this.execService = execService;
        this.draftService = draftService;
    }

    // ---- 数据源管理 ----

    @GetMapping("/datasources")
    public List<DataSourceView> listDataSources() {
        requireSqlUser();
        return dataSourceService.list().stream().map(DataSourceView::of).collect(Collectors.toList());
    }

    @PostMapping("/datasources")
    public DataSourceView create(@RequestBody DataSourceRequest body) {
        requireAdmin();
        SqlDataSource ds = dataSourceService.create(body.getName(), body.getHost(), body.getPort(),
                body.getDefaultDatabase(), body.getUsername(), body.getPassword(), body.getParams());
        return DataSourceView.of(ds);
    }

    @PutMapping("/datasources/{id}")
    public DataSourceView update(@PathVariable Long id, @RequestBody DataSourceRequest body) {
        requireAdmin();
        SqlDataSource ds = dataSourceService.update(id, body.getName(), body.getHost(), body.getPort(),
                body.getDefaultDatabase(), body.getUsername(), body.getPassword(), body.getParams());
        return DataSourceView.of(ds);
    }

    @DeleteMapping("/datasources/{id}")
    public void delete(@PathVariable Long id) {
        requireAdmin();
        dataSourceService.delete(id);
    }

    @PostMapping("/datasources/{id}/test")
    public void test(@PathVariable Long id) {
        requireAdmin();
        dataSourceService.testConnection(id);
    }

    @PostMapping("/datasources/import")
    public ImportResult importBatch(@RequestBody List<SqlDataSourceService.ImportItem> items) {
        requireAdmin();
        int n = dataSourceService.importBatch(items);
        return new ImportResult(n, items != null ? items.size() : 0);
    }

    // ---- 元数据树（懒加载） ----

    @GetMapping("/datasources/{id}/databases")
    public List<String> databases(@PathVariable Long id) {
        requireSqlUser();
        return metadataService.databases(id);
    }

    @GetMapping("/datasources/{id}/tables")
    public List<SqlMetadataService.TableInfo> tables(@PathVariable Long id, @RequestParam String database) {
        requireSqlUser();
        return metadataService.tables(id, database);
    }

    @GetMapping("/datasources/{id}/columns")
    public List<SqlMetadataService.ColumnInfo> columns(@PathVariable Long id,
                                                       @RequestParam String database,
                                                       @RequestParam String table) {
        requireSqlUser();
        return metadataService.columns(id, database, table);
    }

    // ---- 执行 ----

    @PostMapping("/datasources/{id}/execute")
    public SqlExecService.ExecResult execute(@PathVariable Long id, @RequestBody ExecRequest body) {
        requireSqlUser();
        return execService.execute(id, body.getDatabase(), body.getSql(), body.getMaxRows());
    }

    // ---- 草稿（每用户一份，属主即当前登录用户，无需传 userId） ----

    @GetMapping("/draft")
    public DraftView draft() {
        requireSqlUser();
        SqlDraft d = draftService.get(CurrentUser.require().getId());
        return new DraftView(d != null ? d.getContent() : null, d != null ? d.getUpdatedAt() : null);
    }

    @PutMapping("/draft")
    public DraftView saveDraft(@RequestBody DraftRequest body) {
        requireSqlUser();
        SqlDraft d = draftService.save(CurrentUser.require().getId(), body.getContent());
        return new DraftView(d.getContent(), d.getUpdatedAt());
    }

    // ---- 权限护栏 ----

    private void requireSqlUser() {
        Set<Role> roles = CurrentUser.roles();
        if (!roles.contains(Role.ADMIN) && !roles.contains(Role.OPERATOR)) {
            throw ApiException.forbidden("无权访问 SQL 工作台");
        }
    }

    private void requireAdmin() {
        if (!CurrentUser.roles().contains(Role.ADMIN)) {
            throw ApiException.forbidden("仅管理员可管理数据源");
        }
    }

    // ---- DTO ----

    @Data
    public static class DataSourceRequest {
        private String name;
        private String host;
        private int port = 3306;
        private String defaultDatabase;
        private String username;
        private String password;
        private String params;
    }

    @Data
    public static class ExecRequest {
        private String database;
        private String sql;
        private Integer maxRows;
    }

    @Data
    public static class DraftRequest {
        private String content;
    }

    @Data
    public static class DraftView {
        private final String content;
        private final java.time.Instant updatedAt;
    }

    @Data
    public static class DataSourceView {
        private Long id;
        private String name;
        private String host;
        private int port;
        private String dbType;
        private String defaultDatabase;
        private String username;
        private String params;

        static DataSourceView of(SqlDataSource ds) {
            DataSourceView v = new DataSourceView();
            v.setId(ds.getId());
            v.setName(ds.getName());
            v.setHost(ds.getHost());
            v.setPort(ds.getPort());
            v.setDbType(ds.getDbType());
            v.setDefaultDatabase(ds.getDefaultDatabase());
            v.setUsername(ds.getUsername());
            v.setParams(ds.getParams());
            return v;
        }
    }

    @Data
    public static class ImportResult {
        private final int imported;
        private final int total;
    }
}
