package com.company.filepreview.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    private static CryptoService crypto(String secret) {
        SqlProperties props = new SqlProperties();
        props.setSecret(secret);
        return new CryptoService(props);
    }

    @Test
    void encryptDecryptRoundTrip() {
        CryptoService crypto = crypto("test-secret");
        String plain = "s3cret! 密码";
        assertEquals(plain, crypto.decrypt(crypto.encrypt(plain)));
    }

    @Test
    void ciphertextCarriesVersionPrefix() {
        CryptoService crypto = crypto("test-secret");
        assertTrue(crypto.encrypt("pw").startsWith("enc:v1:"));
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() {
        CryptoService crypto = crypto("test-secret");
        // 随机 IV：两次加密同一明文，密文必须不同
        assertNotEquals(crypto.encrypt("pw"), crypto.encrypt("pw"));
    }

    @Test
    void decryptWithWrongKeyFails() {
        String enc = crypto("secret-a").encrypt("pw");
        assertThrows(IllegalStateException.class, () -> crypto("secret-b").decrypt(enc));
    }

    @Test
    void tamperedCiphertextFails() {
        CryptoService crypto = crypto("test-secret");
        String enc = crypto.encrypt("pw");
        // 改动密文最后一个字符（保持合法 Base64），GCM 校验必须失败
        char last = enc.charAt(enc.length() - 1);
        String tampered = enc.substring(0, enc.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void valueWithoutPrefixIsReturnedAsIs() {
        // 兼容旧数据：未加前缀的内容视为明文原样返回
        CryptoService crypto = crypto("test-secret");
        assertEquals("legacy-plain", crypto.decrypt("legacy-plain"));
    }

    @Test
    void nullAndEmptyPassThrough() {
        CryptoService crypto = crypto("test-secret");
        assertNull(crypto.encrypt(null));
        assertNull(crypto.decrypt(null));
        assertEquals("", crypto.encrypt(""));
        assertEquals("", crypto.decrypt(""));
    }
}
