package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecServiceTest {

    private SqlDataSourceService dataSources;
    private SqlExecService service;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws Exception {
        dataSources = mock(SqlDataSourceService.class);
        SqlProperties props = new SqlProperties();
        props.setDefaultMaxRows(1000);
        props.setMaxRowsLimit(100000);
        props.setQueryTimeoutSeconds(60);
        service = new SqlExecService(dataSources, props);

        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSources.getConnection(1L)).thenReturn(connection);
        when(connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY))
                .thenReturn(statement);
    }

    @Test
    void usesDefaultMaxRowsWhenNotRequested() throws Exception {
        when(statement.execute(anyString())).thenReturn(false);

        service.execute(1L, null, "update t set a=1", null);

        // 默认 1000 行，多读一行用于判断截断
        verify(statement).setMaxRows(1001);
    }

    @Test
    void honoursRequestedMaxRowsBelowLimit() throws Exception {
        when(statement.execute(anyString())).thenReturn(false);

        service.execute(1L, null, "update t set a=1", 50);

        verify(statement).setMaxRows(51);
    }

    @Test
    void clampsRequestedMaxRowsToLimit() throws Exception {
        when(statement.execute(anyString())).thenReturn(false);

        service.execute(1L, null, "update t set a=1", 200000);

        // default-max-rows 之外还有 max-rows-limit 兜底
        verify(statement).setMaxRows(100001);
    }

    @Test
    void updateStatementReturnsUpdateCount() throws Exception {
        when(statement.execute(anyString())).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(3);

        SqlExecService.ExecResult r = service.execute(1L, null, "update t set a=1", 10);

        assertEquals(3, r.getUpdateCount());
        assertFalse(r.isTruncated());
    }

    @Test
    void truncatedWhenMoreRowsThanLimit() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("c1");
        // limit=1：第二行存在即应截断
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn("a");

        SqlExecService.ExecResult r = service.execute(1L, null, "select * from t", 1);

        verify(statement).setMaxRows(2);
        assertTrue(r.isTruncated());
        assertEquals(1, r.getRowCount());
        assertEquals("c1", r.getColumns().get(0));
        assertEquals("a", r.getRows().get(0).get(0));
    }

    @Test
    void notTruncatedWhenRowsWithinLimit() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("c1");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn("a", "b");

        SqlExecService.ExecResult r = service.execute(1L, null, "select * from t", 10);

        verify(statement).setMaxRows(11);
        assertFalse(r.isTruncated());
        assertEquals(2, r.getRowCount());
    }

    @Test
    void rejectsBlankSql() {
        assertThrows(ApiException.class, () -> service.execute(1L, null, "   ", null));
        assertThrows(ApiException.class, () -> service.execute(1L, null, null, null));
        verify(dataSources, never()).getConnection(anyLong());
    }

    @Test
    void switchesCatalogAndRestoresOriginal() throws Exception {
        when(connection.getCatalog()).thenReturn("olddb");
        when(statement.execute(anyString())).thenReturn(false);

        service.execute(1L, "newdb", "update t set a=1", null);

        // 先切到目标库，执行后还原原 catalog，避免污染连接池内的其他使用者
        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).getCatalog();
        inOrder.verify(connection).setCatalog("newdb");
        inOrder.verify(connection).setCatalog("olddb");
    }

    @Test
    void setCatalogFailureIsReportedAsBadRequest() throws Exception {
        doThrow(new SQLException("Unknown database 'bad-db'")).when(connection).setCatalog("bad-db");

        ApiException e = assertThrows(ApiException.class,
                () -> service.execute(1L, "bad-db", "select 1", null));
        assertEquals("BAD_REQUEST", e.getCode());
        assertTrue(e.getMessage().contains("切换到库 bad-db 失败"));
        // 切库失败不应继续执行 SQL
        verify(statement, never()).execute(anyString());
    }
}
