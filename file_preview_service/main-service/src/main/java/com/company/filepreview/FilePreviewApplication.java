package com.company.filepreview;

import com.company.filepreview.server.ServerRegistryProperties;
import com.company.filepreview.agent.AgentClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the central file preview service.
 *
 * <p>Packaged as a single executable jar. The React frontend is built and copied
 * into {@code src/main/resources/static} at build time so the whole system ships
 * as one artifact.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({ServerRegistryProperties.class, AgentClientProperties.class})
public class FilePreviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilePreviewApplication.class, args);
    }
}
