package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 JDBC {@link DatabaseMetaData} 读取数据库/表/字段元数据，供前端树形懒加载。
 * MySQL 下 catalog 即数据库名。
 */
@Service
public class SqlMetadataService {

    private final SqlDataSourceService dataSources;

    public SqlMetadataService(SqlDataSourceService dataSources) {
        this.dataSources = dataSources;
    }

    /** 列出所有数据库（MySQL catalog）。 */
    public List<String> databases(Long dataSourceId) {
        List<String> result = new ArrayList<>();
        try (Connection c = dataSources.getConnection(dataSourceId);
             ResultSet rs = c.getMetaData().getCatalogs()) {
            while (rs.next()) {
                result.add(rs.getString("TABLE_CAT"));
            }
        } catch (SQLException e) {
            throw ApiException.badRequest("读取数据库列表失败: " + e.getMessage());
        }
        return result;
    }

    /** 列出指定数据库下的表与视图。 */
    public List<TableInfo> tables(Long dataSourceId, String database) {
        List<TableInfo> result = new ArrayList<>();
        try (Connection c = dataSources.getConnection(dataSourceId)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(database, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    result.add(new TableInfo(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
                }
            }
        } catch (SQLException e) {
            throw ApiException.badRequest("读取表列表失败: " + e.getMessage());
        }
        return result;
    }

    /** 列出指定表的字段名与类型（含精度/长度）。 */
    public List<ColumnInfo> columns(Long dataSourceId, String database, String table) {
        List<ColumnInfo> result = new ArrayList<>();
        try (Connection c = dataSources.getConnection(dataSourceId)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(database, null, table, "%")) {
                while (rs.next()) {
                    String typeName = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int digits = rs.getInt("DECIMAL_DIGITS");
                    boolean hasDigits = rs.getObject("DECIMAL_DIGITS") != null;
                    String fullType = formatType(typeName, size, digits, hasDigits);
                    boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    result.add(new ColumnInfo(rs.getString("COLUMN_NAME"), fullType, nullable));
                }
            }
        } catch (SQLException e) {
            throw ApiException.badRequest("读取字段列表失败: " + e.getMessage());
        }
        return result;
    }

    private static String formatType(String typeName, int size, int digits, boolean hasDigits) {
        if (typeName == null) {
            return "";
        }
        String upper = typeName.toUpperCase();
        // 数值型带精度和小数位
        if (hasDigits && digits > 0 && (upper.contains("DECIMAL") || upper.contains("NUMERIC")
                || upper.contains("DOUBLE") || upper.contains("FLOAT"))) {
            return typeName + "(" + size + "," + digits + ")";
        }
        // 字符/二进制型带长度
        if (size > 0 && (upper.contains("CHAR") || upper.contains("BINARY") || upper.contains("BIT"))) {
            return typeName + "(" + size + ")";
        }
        // 整数型带显示宽度
        if (size > 0 && (upper.equals("INT") || upper.equals("BIGINT") || upper.equals("TINYINT")
                || upper.equals("SMALLINT") || upper.equals("MEDIUMINT"))) {
            return typeName + "(" + size + ")";
        }
        return typeName;
    }

    public static class TableInfo {
        private final String name;
        private final String type;

        public TableInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() { return name; }
        public String getType() { return type; }
    }

    public static class ColumnInfo {
        private final String name;
        private final String type;
        private final boolean nullable;

        public ColumnInfo(String name, String type, boolean nullable) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public boolean isNullable() { return nullable; }
    }
}
