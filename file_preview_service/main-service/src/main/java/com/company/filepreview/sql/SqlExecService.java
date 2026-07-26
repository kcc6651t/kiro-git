package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行用户选定的单条 SQL。对结果集做行数上限保护，并对大结果集启用 MySQL 流式读取
 * （{@code setFetchSize(Integer.MIN_VALUE)}），避免一次性把整个结果加载进内存。
 */
@Service
public class SqlExecService {

    private final SqlDataSourceService dataSources;
    private final SqlProperties properties;

    public SqlExecService(SqlDataSourceService dataSources, SqlProperties properties) {
        this.dataSources = dataSources;
        this.properties = properties;
    }

    public ExecResult execute(Long dataSourceId, String database, String sql, Integer maxRows) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("SQL 不能为空");
        }
        // 去掉结尾分号，只执行单条
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int limit = resolveMaxRows(maxRows);

        long start = System.currentTimeMillis();
        try (Connection c = dataSources.getConnection(dataSourceId)) {
            String originalCatalog = null;
            boolean catalogSwitched = false;
            if (database != null && !database.trim().isEmpty()) {
                // 记录原 catalog：HikariCP 归还连接时不会重置，执行后须还原
                originalCatalog = c.getCatalog();
                try {
                    c.setCatalog(database.trim());
                    catalogSwitched = true;
                } catch (SQLException e) {
                    throw ApiException.badRequest("切换到库 " + database + " 失败: " + e.getMessage());
                }
            }
            try (Statement stmt = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                stmt.setQueryTimeout(properties.getQueryTimeoutSeconds());
                // 多读一行用于判断是否被截断
                stmt.setMaxRows(limit + 1);
                stmt.setFetchSize(Integer.MIN_VALUE); // MySQL 流式游标

                boolean hasResultSet = stmt.execute(trimmed);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ExecResult r = readRows(rs, limit);
                        r.setElapsedMs(System.currentTimeMillis() - start);
                        return r;
                    }
                } else {
                    ExecResult r = new ExecResult();
                    r.setUpdateCount(stmt.getUpdateCount());
                    r.setElapsedMs(System.currentTimeMillis() - start);
                    return r;
                }
            } finally {
                if (catalogSwitched) {
                    // best-effort 还原 catalog，避免残留污染连接池内的其他使用者
                    try {
                        c.setCatalog(originalCatalog);
                    } catch (SQLException ignored) {
                    }
                }
            }
        } catch (SQLException e) {
            throw ApiException.badRequest("执行失败: " + e.getMessage());
        }
    }

    private ExecResult readRows(ResultSet rs, int limit) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        int n = 0;
        while (rs.next()) {
            if (n >= limit) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? null : String.valueOf(v));
            }
            rows.add(row);
            n++;
        }
        ExecResult r = new ExecResult();
        r.setColumns(columns);
        r.setRows(rows);
        r.setRowCount(rows.size());
        r.setTruncated(truncated);
        r.setUpdateCount(-1);
        return r;
    }

    private int resolveMaxRows(Integer requested) {
        int limit = requested != null && requested > 0 ? requested : properties.getDefaultMaxRows();
        return Math.min(limit, properties.getMaxRowsLimit());
    }

    public static class ExecResult {
        private List<String> columns;
        private List<List<String>> rows;
        private int rowCount;
        private boolean truncated;
        private int updateCount = -1;
        private long elapsedMs;

        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }
        public int getRowCount() { return rowCount; }
        public void setRowCount(int rowCount) { this.rowCount = rowCount; }
        public boolean isTruncated() { return truncated; }
        public void setTruncated(boolean truncated) { this.truncated = truncated; }
        public int getUpdateCount() { return updateCount; }
        public void setUpdateCount(int updateCount) { this.updateCount = updateCount; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}
