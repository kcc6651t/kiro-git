package com.company.filepreview.support;

import com.company.filepreview.server.ServerRegistry;
import com.company.filepreview.server.ServerRegistryProperties;

/**
 * Test helper that builds a {@link ServerRegistry} loaded from the classpath test
 * fixture {@code servers-test.yml}.
 */
public final class TestServers {

    private TestServers() {
    }

    public static ServerRegistry registry() {
        ServerRegistryProperties props = new ServerRegistryProperties();
        props.setServersConfig("servers-test.yml");
        ServerRegistry registry = new ServerRegistry(props);
        registry.load();
        return registry;
    }
}
