package com.company.filepreview.agent;

import com.company.filepreview.file.model.AgentHealth;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.server.ServerRegistryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * mTLS integration test for {@link AgentRemoteFileClient} using a MockWebServer
 * that requires client authentication. Verifies a valid client cert succeeds and
 * a cert from an untrusted CA is rejected (chain validation is never skipped).
 */
class MtlsAgentClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void validClientCertSucceeds() throws Exception {
        HeldCertificate rootCa = new HeldCertificate.Builder()
                .certificateAuthority(1)
                .commonName("root")
                .build();
        HeldCertificate serverCert = new HeldCertificate.Builder()
                .commonName("agent")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .signedBy(rootCa)
                .build();
        HeldCertificate clientCert = new HeldCertificate.Builder()
                .commonName("file-preview-main")
                .signedBy(rootCa)
                .build();

        HandshakeCertificates serverHandshake = new HandshakeCertificates.Builder()
                .heldCertificate(serverCert)
                .addTrustedCertificate(rootCa.certificate())
                .build();

        server = new MockWebServer();
        server.useHttps(serverHandshake.sslSocketFactory(), false);
        server.requireClientAuth();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"serverId\":\"srv-a\",\"status\":\"UP\",\"version\":\"1.0.0\"}"));
        server.start();

        AgentRemoteFileClient client = buildClient(rootCa, clientCert, "localhost");

        AgentHealth health = client.health("srv-a");
        assertEquals("UP", health.getStatus());
        assertEquals("1.0.0", health.getVersion());
    }

    @Test
    void untrustedClientCertIsRejected() throws Exception {
        HeldCertificate rootCa = new HeldCertificate.Builder().certificateAuthority(1).commonName("root").build();
        HeldCertificate serverCert = new HeldCertificate.Builder()
                .commonName("agent")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .signedBy(rootCa)
                .build();
        // Client cert signed by a DIFFERENT CA the server does not trust.
        HeldCertificate otherCa = new HeldCertificate.Builder().certificateAuthority(1).commonName("other").build();
        HeldCertificate rogueClient = new HeldCertificate.Builder()
                .commonName("file-preview-main")
                .signedBy(otherCa)
                .build();

        HandshakeCertificates serverHandshake = new HandshakeCertificates.Builder()
                .heldCertificate(serverCert)
                .addTrustedCertificate(rootCa.certificate())
                .build();

        server = new MockWebServer();
        server.useHttps(serverHandshake.sslSocketFactory(), false);
        server.requireClientAuth();
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}"));
        server.start();

        // Client trusts the server's rootCa but presents a rogue client cert.
        AgentRemoteFileClient client = buildClient(rootCa, rogueClient, "localhost");

        AgentHealth health = client.health("srv-a");
        // health() degrades gracefully to DOWN when the mTLS handshake fails.
        assertNotEquals("UP", health.getStatus());
    }

    private AgentRemoteFileClient buildClient(HeldCertificate caForClientTrust,
                                              HeldCertificate clientCert,
                                              String serverName) throws Exception {
        Path tmp = Files.createTempDirectory("mtls-test");
        Path caFile = tmp.resolve("ca.crt");
        Path clientCrt = tmp.resolve("client.crt");
        Path clientKey = tmp.resolve("client.key");
        Files.write(caFile, certPem(caForClientTrust.certificate()).getBytes(StandardCharsets.UTF_8));
        Files.write(clientCrt, certPem(clientCert.certificate()).getBytes(StandardCharsets.UTF_8));
        Files.write(clientKey, keyPem(clientCert).getBytes(StandardCharsets.UTF_8));

        AgentClientProperties props = new AgentClientProperties();
        AgentClientProperties.ClientCert cc = new AgentClientProperties.ClientCert();
        cc.setCertFile(clientCrt.toString());
        cc.setKeyFile(clientKey.toString());
        props.getClientCerts().put("main-client", cc);
        AgentClientProperties.CaCert ca = new AgentClientProperties.CaCert();
        ca.setCertFile(caFile.toString());
        props.getCaCerts().put("agent-ca", ca);

        ServerRegistryProperties srp = new ServerRegistryProperties();
        srp.setServersConfig("servers-test.yml");
        ServerRegistry registry = new ServerRegistry(srp);
        registry.load();
        ServerDefinition def = registry.require("srv-a");
        def.getAgent().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        def.getAgent().setServerName(serverName);

        MtlsHttpClientFactory factory = new MtlsHttpClientFactory(props);
        return new AgentRemoteFileClient(registry, factory, props, new ObjectMapper());
    }

    private String certPem(X509Certificate cert) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    private String keyPem(HeldCertificate held) {
        // PrivateKey.getEncoded() is PKCS#8 DER, which PemUtils reads via PKCS8EncodedKeySpec.
        byte[] der = held.keyPair().getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }
}
