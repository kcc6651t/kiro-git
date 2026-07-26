package com.company.filepreview.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mTLS material and defaults for calling Agents. Certificate references used in
 * {@code servers.yml} ({@code caCertRef}, {@code clientCertRef}) are resolved to
 * actual PEM files here.
 *
 * <pre>
 * filepreview:
 *   agent:
 *     defaultConnectTimeoutMs: 2000
 *     defaultReadTimeoutMs: 10000
 *     apiToken: "optional-second-factor-token"
 *     clientCerts:
 *       main-client:
 *         certFile: config/certs/main-client.crt
 *         keyFile:  config/certs/main-client.key   # PKCS#8 PEM
 *     caCerts:
 *       agent-ca:
 *         certFile: config/certs/agent-ca.crt
 * </pre>
 */
@ConfigurationProperties(prefix = "filepreview.agent")
public class AgentClientProperties {

    private int defaultConnectTimeoutMs = 2000;
    private int defaultReadTimeoutMs = 10000;

    /** Optional static API token added as a second factor beyond mTLS. */
    private String apiToken;

    private Map<String, ClientCert> clientCerts = new LinkedHashMap<>();
    private Map<String, CaCert> caCerts = new LinkedHashMap<>();

    public int getDefaultConnectTimeoutMs() {
        return defaultConnectTimeoutMs;
    }

    public void setDefaultConnectTimeoutMs(int defaultConnectTimeoutMs) {
        this.defaultConnectTimeoutMs = defaultConnectTimeoutMs;
    }

    public int getDefaultReadTimeoutMs() {
        return defaultReadTimeoutMs;
    }

    public void setDefaultReadTimeoutMs(int defaultReadTimeoutMs) {
        this.defaultReadTimeoutMs = defaultReadTimeoutMs;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public Map<String, ClientCert> getClientCerts() {
        return clientCerts;
    }

    public void setClientCerts(Map<String, ClientCert> clientCerts) {
        this.clientCerts = clientCerts;
    }

    public Map<String, CaCert> getCaCerts() {
        return caCerts;
    }

    public void setCaCerts(Map<String, CaCert> caCerts) {
        this.caCerts = caCerts;
    }

    public static class ClientCert {
        private String certFile;
        private String keyFile;

        public String getCertFile() {
            return certFile;
        }

        public void setCertFile(String certFile) {
            this.certFile = certFile;
        }

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(String keyFile) {
            this.keyFile = keyFile;
        }
    }

    public static class CaCert {
        private String certFile;

        public String getCertFile() {
            return certFile;
        }

        public void setCertFile(String certFile) {
            this.certFile = certFile;
        }
    }
}
