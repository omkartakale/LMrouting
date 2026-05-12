package com.example.LMrouting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists affinity region configuration to a JSON file on disk.
 *
 * Storage path priority:
 *   1. affinity.config.storage.path property (if set)
 *   2. <user.home>/lm-routing-data/affinity-config.json (default)
 *
 * Using user.home ensures the file survives application restarts,
 * redeployments, and Docker container restarts (when home dir is mounted).
 */
@Service
@Slf4j
public class AffinityConfigStorageService {

    @Value("${affinity.config.storage.path:}")
    private String configuredPath;

    private String configFilePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        if (configuredPath != null && !configuredPath.isBlank()) {
            configFilePath = configuredPath;
        } else {
            // Default: user home directory — survives restarts and redeployments
            String home = System.getProperty("user.home");
            configFilePath = home + File.separator + "lm-routing-data"
                    + File.separator + "affinity-config.json";
        }
        log.info("AffinityConfigStorage: using path '{}'", configFilePath);

        // Ensure parent directory exists at startup
        try {
            Path dir = Paths.get(configFilePath).getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("AffinityConfigStorage: created directory '{}'", dir);
            }
        } catch (IOException e) {
            log.warn("AffinityConfigStorage: could not create storage directory: {}", e.getMessage());
        }
    }

    public void saveConfig(Map<String, Object> config) throws IOException {
        Path dir = Paths.get(configFilePath).getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File(configFilePath), config);
        log.info("Affinity config saved to '{}'", configFilePath);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadConfig() throws IOException {
        File file = new File(configFilePath);
        if (!file.exists()) {
            log.info("No affinity config file found at '{}'", configFilePath);
            return new HashMap<>();
        }
        Map<String, Object> config = objectMapper.readValue(file, Map.class);
        log.info("Affinity config loaded from '{}' ({} bytes)", configFilePath, file.length());
        return config;
    }

    public boolean clearConfig() {
        File file = new File(configFilePath);
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                log.info("Affinity config deleted: '{}'", configFilePath);
            } else {
                log.warn("Failed to delete affinity config: '{}'", configFilePath);
            }
            return deleted;
        }
        return true;
    }

    /** Returns the resolved storage path — useful for logging/debugging. */
    public String getStoragePath() {
        return configFilePath;
    }

    /**
     * Load the per-SR shift durations map from the affinity config.
     *
     * <p>The {@code srShiftDurations} field is stored in the same JSON file as
     * {@code srZoneMap} and {@code regions}. When absent or null, returns an empty map
     * (all SRs fall back to the global default shift duration).
     *
     * @return a non-null map of SR name → shift duration in minutes (empty if not configured)
     * @throws IOException if the config file cannot be read
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> loadSrShiftDurations() throws IOException {
        Map<String, Object> config = loadConfig();
        Object raw = config.get("srShiftDurations");
        if (raw == null) {
            return new HashMap<>();
        }
        if (raw instanceof Map) {
            Map<String, Object> rawMap = (Map<String, Object>) raw;
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    result.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
            return result;
        }
        return new HashMap<>();
    }

    /**
     * Save per-SR shift durations into the affinity config.
     *
     * <p>Merges the {@code srShiftDurations} map into the existing config without
     * overwriting other fields (regions, srZoneMap, etc.).
     *
     * @param srShiftDurations map of SR name → shift duration in minutes
     * @throws IOException if the config file cannot be written
     */
    public void saveSrShiftDurations(Map<String, Integer> srShiftDurations) throws IOException {
        Map<String, Object> config = loadConfig();
        if (srShiftDurations == null || srShiftDurations.isEmpty()) {
            config.remove("srShiftDurations");
        } else {
            config.put("srShiftDurations", srShiftDurations);
        }
        saveConfig(config);
        log.info("AffinityConfigStorage: saved srShiftDurations ({} entries)", 
                srShiftDurations != null ? srShiftDurations.size() : 0);
    }
}
