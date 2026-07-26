package com.company.filepreview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds general file-preview settings from {@code application.yml}.
 */
@ConfigurationProperties(prefix = "filepreview")
public class ServerRegistryProperties {

    /** Path to servers.yml, e.g. {@code config/servers.yml}. */
    private String serversConfig = "config/servers.yml";

    public String getServersConfig() {
        return serversConfig;
    }

    public void setServersConfig(String serversConfig) {
        this.serversConfig = serversConfig;
    }
}
