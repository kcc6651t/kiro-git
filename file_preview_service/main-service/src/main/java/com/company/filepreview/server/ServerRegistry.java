package com.company.filepreview.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and holds target server definitions from {@code servers.yml}.
 *
 * <p>The path is resolved first as a filesystem path (for jar-relative deployment
 * under {@code config/servers.yml}) and falls back to the classpath (handy for
 * tests and local development).</p>
 */
@Component
public class ServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServerRegistry.class);

    private final ServerRegistryProperties properties;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    private volatile Map<String, ServerDefinition> byId = new LinkedHashMap<>();

    public ServerRegistry(ServerRegistryProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        String location = properties.getServersConfig();
        try (InputStream in = openConfig(location)) {
            if (in == null) {
                log.warn("servers.yml not found at '{}' (filesystem or classpath); starting with empty registry", location);
                this.byId = new LinkedHashMap<>();
                return;
            }
            ServersFile parsed = yaml.readValue(in, ServersFile.class);
            Map<String, ServerDefinition> map = new LinkedHashMap<>();
            List<ServerDefinition> servers = parsed.getServers() != null ? parsed.getServers() : new ArrayList<>();
            for (ServerDefinition def : servers) {
                if (def.getId() == null || def.getId().trim().isEmpty()) {
                    log.warn("Skipping server definition without id: {}", def.getName());
                    continue;
                }
                map.put(def.getId(), def);
            }
            this.byId = map;
            log.info("Loaded {} server definition(s) from {}", map.size(), location);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load servers config from " + location, e);
        }
    }

    private InputStream openConfig(String location) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        // Try filesystem first
        Resource fsResource = resolver.getResource("file:" + location);
        if (fsResource.exists()) {
            return fsResource.getInputStream();
        }
        Resource cpResource = resolver.getResource("classpath:" + location);
        if (cpResource.exists()) {
            return cpResource.getInputStream();
        }
        return null;
    }

    public List<ServerDefinition> all() {
        return new ArrayList<>(byId.values());
    }

    public List<ServerDefinition> enabled() {
        List<ServerDefinition> result = new ArrayList<>();
        for (ServerDefinition def : byId.values()) {
            if (def.isEnabled()) {
                result.add(def);
            }
        }
        return result;
    }

    public Optional<ServerDefinition> find(String serverId) {
        return Optional.ofNullable(byId.get(serverId));
    }

    public ServerDefinition require(String serverId) {
        ServerDefinition def = byId.get(serverId);
        if (def == null) {
            throw new IllegalArgumentException("Unknown server: " + serverId);
        }
        return def;
    }

    /** Root paths declared for a server, or empty list when none configured. */
    public List<String> allowedRoots(String serverId) {
        return find(serverId).map(ServerDefinition::getAllowedRoots).orElse(Collections.emptyList());
    }

    /** The configured servers.yml location (as given in application.yml). */
    public String configLocation() {
        return properties.getServersConfig();
    }

    /** Raw text of the current servers.yml (filesystem first, then classpath). */
    public String readRawConfig() {
        try (InputStream in = openConfig(properties.getServersConfig())) {
            if (in == null) {
                return "";
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read servers config", e);
        }
    }

    /**
     * Validate and persist servers.yml, then hot-reload. Writes to the filesystem
     * path from configuration (creating parent dirs as needed).
     *
     * @throws IllegalArgumentException when the YAML is invalid.
     */
    public synchronized void saveRawConfig(String content) {
        if (content == null) {
            throw new IllegalArgumentException("配置内容不能为空");
        }
        // Validate by parsing into the expected shape.
        try {
            yaml.readValue(content, ServersFile.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML 格式错误: " + e.getMessage());
        }
        java.nio.file.Path path = java.nio.file.Paths.get(properties.getServersConfig());
        try {
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            java.nio.file.Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("写入配置失败: " + e.getMessage(), e);
        }
        load();
    }

    public static class ServersFile {
        private List<ServerDefinition> servers = new ArrayList<>();

        public List<ServerDefinition> getServers() {
            return servers;
        }

        public void setServers(List<ServerDefinition> servers) {
            this.servers = servers;
        }
    }
}
