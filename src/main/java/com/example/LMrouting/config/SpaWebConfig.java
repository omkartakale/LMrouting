package com.example.LMrouting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the Vite/React SPA under {@code /app/} with client-side routing fallback,
 * and redirects the site root to the SPA entry.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String SPA_INDEX = "static/app/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations("classpath:/static/app/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Avoid serving "." / ".." as static files (e.g. GET /app/.) — those are not real assets.
                        if (!StringUtils.hasText(resourcePath) || ".".equals(resourcePath) || "..".equals(resourcePath)) {
                            return new ClassPathResource(SPA_INDEX);
                        }
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        return new ClassPathResource(SPA_INDEX);
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        // /app and /app/ are served by SpaIndexController (HTML shell)
    }
}
