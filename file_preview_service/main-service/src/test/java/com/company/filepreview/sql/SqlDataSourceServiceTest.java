package com.company.filepreview.sql;

import com.company.filepreview.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlDataSourceServiceTest {

    private SqlDataSourceRepository repository;
    private CryptoService crypto;
    private SqlDataSourceService service;

    @BeforeEach
    void setUp() {
        repository = mock(SqlDataSourceRepository.class);
        SqlProperties props = new SqlProperties();
        props.setSecret("test-secret");
        crypto = new CryptoService(props);
        service = new SqlDataSourceService(repository, crypto, props);
        when(repository.save(any(SqlDataSource.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createEncryptsPasswordBeforeSave() {
        service.create("myds", "db-host", 3306, "appdb", "root", "plain-pw", null);

        ArgumentCaptor<SqlDataSource> captor = ArgumentCaptor.forClass(SqlDataSource.class);
        verify(repository).save(captor.capture());
        SqlDataSource saved = captor.getValue();
        assertFalse("plain-pw".equals(saved.getPasswordEnc()), "入库的必须是密文");
        assertTrue(saved.getPasswordEnc().startsWith("enc:v1:"));
        assertEquals("plain-pw", crypto.decrypt(saved.getPasswordEnc()));
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByName("dup")).thenReturn(true);

        ApiException e = assertThrows(ApiException.class,
                () -> service.create("dup", "db-host", 3306, null, "root", "pw", null));
        assertEquals("BAD_REQUEST", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.create("   ", "db-host", 3306, null, "root", "pw", null));
        assertEquals("BAD_REQUEST", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void updateEncryptsNewPassword() {
        SqlDataSource existing = existing(7L, "old-name", crypto.encrypt("old-pw"));
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        service.update(7L, null, "db-host", 3307, null, "root", "new-pw", null);

        ArgumentCaptor<SqlDataSource> captor = ArgumentCaptor.forClass(SqlDataSource.class);
        verify(repository).save(captor.capture());
        SqlDataSource saved = captor.getValue();
        assertTrue(saved.getPasswordEnc().startsWith("enc:v1:"));
        assertEquals("new-pw", crypto.decrypt(saved.getPasswordEnc()));
    }

    @Test
    void updateKeepsPasswordWhenBlank() {
        String oldEnc = crypto.encrypt("old-pw");
        SqlDataSource existing = existing(7L, "old-name", oldEnc);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        // 密码为空表示不修改
        service.update(7L, null, "db-host", 3306, null, "root", "", null);

        ArgumentCaptor<SqlDataSource> captor = ArgumentCaptor.forClass(SqlDataSource.class);
        verify(repository).save(captor.capture());
        assertEquals(oldEnc, captor.getValue().getPasswordEnc());
    }

    @Test
    void updateRejectsDuplicateName() {
        SqlDataSource existing = existing(7L, "old-name", crypto.encrypt("old-pw"));
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.existsByName("taken")).thenReturn(true);

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(7L, "taken", "db-host", 3306, null, "root", null, null));
        assertEquals("BAD_REQUEST", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void deleteRemovesEntity() {
        SqlDataSource existing = existing(7L, "old-name", crypto.encrypt("old-pw"));
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        service.delete(7L);

        verify(repository).delete(existing);
    }

    @Test
    void getThrowsNotFoundForMissingId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.get(99L));
        assertEquals("NOT_FOUND", e.getCode());
    }

    @Test
    void importBatchRejectsNullItems() {
        ApiException e = assertThrows(ApiException.class, () -> service.importBatch(null));
        assertEquals("BAD_REQUEST", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepsFieldsOmittedInRequest() {
        SqlDataSource existing = existing(7L, "old-name", crypto.encrypt("old-pw"));
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        // 请求中省略（null）的字段不得覆盖原值
        service.update(7L, null, null, 3306, null, null, null, null);

        ArgumentCaptor<SqlDataSource> captor = ArgumentCaptor.forClass(SqlDataSource.class);
        verify(repository).save(captor.capture());
        SqlDataSource saved = captor.getValue();
        assertEquals("db-host", saved.getHost());
        assertEquals("root", saved.getUsername());
        assertEquals(3306, saved.getPort());
    }

    @Test
    void dataSourceViewDoesNotLeakCiphertext() throws Exception {
        String cipher = crypto.encrypt("plain-pw");
        SqlDataSource ds = existing(7L, "myds", cipher);

        // DataSourceView.of 是 web 包内包私有方法，反射调用后按前端实际收到的 JSON 断言
        Class<?> viewClass = Class.forName("com.company.filepreview.web.SqlController$DataSourceView");
        Method of = viewClass.getDeclaredMethod("of", SqlDataSource.class);
        of.setAccessible(true);
        Object view = of.invoke(null, ds);
        String json = new ObjectMapper().writeValueAsString(view);

        assertFalse(json.contains(cipher), "视图 JSON 不得包含密文");
        assertFalse(json.contains("plain-pw"), "视图 JSON 不得包含明文密码");
        assertFalse(json.contains("passwordEnc"), "视图 JSON 不得暴露密文字段");
    }

    private static SqlDataSource existing(Long id, String name, String passwordEnc) {
        SqlDataSource ds = new SqlDataSource();
        ds.setId(id);
        ds.setName(name);
        ds.setHost("db-host");
        ds.setPort(3306);
        ds.setUsername("root");
        ds.setPasswordEnc(passwordEnc);
        return ds;
    }
}
