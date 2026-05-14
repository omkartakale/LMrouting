package com.example.LMrouting.config;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

/**
 * Serves the SPA shell for {@code /app} and {@code /app/} so the UI loads even when
 * the generic static-resource chain mis-resolves the path. Deep links ({@code /app/review})
 * continue to be handled by {@link SpaWebConfig}'s {@code /app/**} resource handler + fallback.
 */
@Controller
public class SpaIndexController {

    private static final ClassPathResource SPA_INDEX = new ClassPathResource("static/app/index.html");

    private static final String BUILD_HINT =
            "Frontend bundle missing. From repo root run: .\\mvnw.cmd generate-resources  OR  cd frontend && npm run build";

    @GetMapping({"/app", "/app/"})
    public ResponseEntity<Resource> spaShell() {
        if (!SPA_INDEX.exists()) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(new ByteArrayResource(BUILD_HINT.getBytes(StandardCharsets.UTF_8)));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .contentType(MediaType.TEXT_HTML)
                .body(SPA_INDEX);
    }
}
