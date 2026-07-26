package com.company.filepreview.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal PEM parsing for X.509 certificates and unencrypted PKCS#8 private keys.
 * Java 8 can read PKCS#8 keys via {@link PKCS8EncodedKeySpec} without BouncyCastle;
 * keys must therefore be in {@code BEGIN PRIVATE KEY} (PKCS#8) form.
 */
final class PemUtils {

    private static final Pattern CERT_PATTERN = Pattern.compile(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);

    private PemUtils() {
    }

    static List<X509Certificate> readCertificates(String path) throws IOException, CertificateException {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();
        Matcher matcher = CERT_PATTERN.matcher(pem);
        while (matcher.find()) {
            byte[] der = Base64.getMimeDecoder().decode(cleanBase64(matcher.group(1)));
            Certificate cert = factory.generateCertificate(new ByteArrayInputStream(der));
            certs.add((X509Certificate) cert);
        }
        if (certs.isEmpty()) {
            throw new CertificateException("No certificates found in " + path);
        }
        return certs;
    }

    static PrivateKey readPrivateKey(String path) throws IOException, InvalidKeySpecException {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        String base64 = pem
                .replaceAll("-----BEGIN (RSA |EC )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA |EC )?PRIVATE KEY-----", "");
        byte[] der = Base64.getMimeDecoder().decode(cleanBase64(base64));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // Try common algorithms; PKCS#8 embeds the algorithm but KeyFactory needs a name.
        String[] algorithms = {"RSA", "EC"};
        InvalidKeySpecException last = null;
        for (String alg : algorithms) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                last = e;
            } catch (Exception e) {
                last = new InvalidKeySpecException(e);
            }
        }
        throw last != null ? last : new InvalidKeySpecException("Unable to parse private key at " + path);
    }

    private static String cleanBase64(String s) {
        return s.replaceAll("\\s", "");
    }
}
