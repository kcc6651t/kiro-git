package com.company.filepreview.agent;

import com.company.filepreview.common.ApiException;
import com.company.filepreview.server.ServerDefinition;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Builds and caches an mTLS-enabled {@link OkHttpClient} per server.
 *
 * <p>Security properties:</p>
 * <ul>
 *   <li>Presents the central service's client certificate (mutual TLS).</li>
 *   <li>Trusts only the configured Agent CA - the default JVM trust store is not used.</li>
 *   <li>Certificate chain validation is never disabled.</li>
 *   <li>Hostname is verified against the Agent's configured {@code serverName}
 *       (CN or SAN), since Agents are reached by IP.</li>
 * </ul>
 */
@Component
public class MtlsHttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(MtlsHttpClientFactory.class);

    private final AgentClientProperties properties;
    private final ConcurrentHashMap<String, OkHttpClient> cache = new ConcurrentHashMap<>();

    public MtlsHttpClientFactory(AgentClientProperties properties) {
        this.properties = properties;
    }

    public OkHttpClient forServer(ServerDefinition server) {
        return cache.computeIfAbsent(server.getId(), id -> build(server));
    }

    /** Clears the cached client for a server, e.g. after certificate rotation. */
    public void invalidate(String serverId) {
        cache.remove(serverId);
    }

    /** Clears every cached client, e.g. after servers.yml is reloaded. */
    public void invalidateAll() {
        cache.clear();
    }

    private OkHttpClient build(ServerDefinition server) {
        ServerDefinition.AgentEndpoint ep = server.getAgent();
        if (ep == null || ep.getBaseUrl() == null) {
            throw new IllegalStateException("Server " + server.getId() + " has no agent.baseUrl");
        }
        String clientRef = ep.getClientCertRef();
        String caRef = ep.getCaCertRef();
        AgentClientProperties.ClientCert clientCert = properties.getClientCerts().get(clientRef);
        AgentClientProperties.CaCert caCert = properties.getCaCerts().get(caRef);
        if (clientCert == null) {
            throw new IllegalStateException("No clientCert configured for ref '" + clientRef + "'");
        }
        if (caCert == null) {
            throw new IllegalStateException("No caCert configured for ref '" + caRef + "'");
        }

        try {
            List<X509Certificate> chain = PemUtils.readCertificates(clientCert.getCertFile());
            PrivateKey key = PemUtils.readPrivateKey(clientCert.getKeyFile());

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry("client", key, new char[0],
                    chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);

            List<X509Certificate> caChain = PemUtils.readCertificates(caCert.getCertFile());
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int i = 0;
            for (X509Certificate ca : caChain) {
                trustStore.setCertificateEntry("ca-" + (i++), ca);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            TrustManager[] trustManagers = tmf.getTrustManagers();
            X509TrustManager x509TrustManager = firstX509(trustManagers);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), trustManagers, new java.security.SecureRandom());

            String expectedName = ep.getServerName();
            HostnameVerifier verifier = new ExpectedNameVerifier(expectedName);

            int connectTimeout = ep.getConnectTimeoutMs() > 0
                    ? ep.getConnectTimeoutMs() : properties.getDefaultConnectTimeoutMs();
            int readTimeout = ep.getReadTimeoutMs() > 0
                    ? ep.getReadTimeoutMs() : properties.getDefaultReadTimeoutMs();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), x509TrustManager)
                    .hostnameVerifier(verifier)
                    .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(readTimeout, TimeUnit.MILLISECONDS)
                    .build();
        } catch (Exception e) {
            log.error("Failed to build mTLS client for server {}", server.getId(), e);
            throw ApiException.agentUnavailable("Failed to initialize mTLS for server " + server.getId());
        }
    }

    private X509TrustManager firstX509(TrustManager[] managers) {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    /**
     * Verifies the peer certificate CN/SAN matches the configured serverName. The
     * certificate chain itself is validated by the TrustManager before this runs;
     * this only pins the identity so IP-addressed Agents are accepted safely.
     */
    static final class ExpectedNameVerifier implements HostnameVerifier {
        private final String expectedName;

        ExpectedNameVerifier(String expectedName) {
            this.expectedName = expectedName;
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            if (expectedName == null || expectedName.isEmpty()) {
                return false; // fail closed: never accept when no expected identity configured
            }
            try {
                Certificate[] peer = session.getPeerCertificates();
                if (peer.length == 0 || !(peer[0] instanceof X509Certificate)) {
                    return false;
                }
                X509Certificate cert = (X509Certificate) peer[0];
                if (cnMatches(cert, expectedName) || sanMatches(cert, expectedName)) {
                    return true;
                }
                log.warn("Agent certificate identity mismatch: expected '{}'", expectedName);
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean cnMatches(X509Certificate cert, String expected) {
            String dn = cert.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.startsWith("CN=") && p.substring(3).equalsIgnoreCase(expected)) {
                    return true;
                }
            }
            return false;
        }

        private boolean sanMatches(X509Certificate cert, String expected) throws Exception {
            java.util.Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return false;
            }
            for (List<?> san : sans) {
                if (san.size() >= 2 && expected.equalsIgnoreCase(String.valueOf(san.get(1)))) {
                    return true;
                }
            }
            return false;
        }
    }
}
