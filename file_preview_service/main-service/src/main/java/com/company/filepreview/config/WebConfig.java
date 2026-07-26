package com.company.filepreview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration. CORS is only enabled for the Vite dev server origin so
 * the frontend can be developed against a running backend; production serves the
 * SPA from the same origin and needs no CORS.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${filepreview.dev.cors-origin:http://localhost:5173}")
    private String devOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(devOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}
