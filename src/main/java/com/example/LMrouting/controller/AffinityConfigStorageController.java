package com.example.LMrouting.controller;

import com.example.LMrouting.service.AffinityConfigStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/affinity-config")
@RequiredArgsConstructor
@Slf4j
public class AffinityConfigStorageController {

    private final AffinityConfigStorageService storageService;

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> config) {
        try {
            storageService.saveConfig(config);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Affinity configuration saved successfully.");
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Failed to save affinity config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to save configuration: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/load")
    public ResponseEntity<Map<String, Object>> loadConfig() {
        try {
            Map<String, Object> config = storageService.loadConfig();
            // Add storage path info so frontend can show where data is persisted
            config.put("_storagePath", storageService.getStoragePath());
            return ResponseEntity.ok(config);
        } catch (IOException e) {
            log.error("Failed to load affinity config", e);
            return ResponseEntity.ok(new HashMap<>());
        }
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearConfig() {
        boolean cleared = storageService.clearConfig();
        Map<String, Object> response = new HashMap<>();
        response.put("success", cleared);
        response.put("message", cleared ? "Configuration cleared." : "Failed to clear configuration.");
        return ResponseEntity.ok(response);
    }
}
