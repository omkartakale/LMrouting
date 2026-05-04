package com.example.LMrouting.controller;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.model.AffinityConfiguration;
import com.example.LMrouting.model.AffinityRegion;
import com.example.LMrouting.model.AuditLog;
import com.example.LMrouting.model.SrRegionAssignment;
import com.example.LMrouting.service.AffinityConfigService;
import com.example.LMrouting.service.AuditLogService;
import com.example.LMrouting.service.RegionDensityAnalyzer;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for affinity configuration management.
 * 
 * Provides endpoints for:
 * - Analyzing regions and generating density reports
 * - Auto-distributing SRs to regions
 * - Manually updating SR assignments
 * - Saving, loading, and deleting configurations
 * - Validating configurations against current data
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 19.1, 19.2, 19.3, 19.5, 20.5
 */
@RestController
@RequestMapping("/api/affinity")
@RequiredArgsConstructor
@Slf4j
public class AffinityConfigController {

    private final AffinityConfigService affinityConfigService;
    private final RegionDensityAnalyzer regionDensityAnalyzer;
    private final AuditLogService auditLogService;
    private final InMemoryStore store;

    /**
     * List all saved configuration names.
     *
     * GET /api/affinity/configs
     *
     * Response: List of saved configuration name strings
     *
     * Requirements: 19.1
     */
    @GetMapping("/configs")
    public ResponseEntity<?> listConfigurations() {
        log.info("AffinityConfigController: listing all saved configurations");
        List<String> names = affinityConfigService.listConfigurationNames();
        log.info("AffinityConfigController: found {} saved configurations", names.size());
        return ResponseEntity.ok(names);
    }

    /**
     * Analyze regions and return density report.
     * 
     * POST /api/affinity/analyze
     * 
     * Request body:
     * {
     *   "date": "2026-03-24",
     *   "hubName": "HUB001",
     *   "shipments": [...]
     * }
     * 
     * Response:
     * {
     *   "hubName": "HUB001",
     *   "date": "2026-03-24",
     *   "totalShipments": 500,
     *   "totalRegions": 25,
     *   "regions": [...]
     * }
     * 
     * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 20.4
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeRegions(@RequestBody AnalyzeRegionsRequest request) {
        log.info("AffinityConfigController: analyzing regions for hub '{}' on date '{}'", 
                 request.hubName(), request.date());
        
        if (request.shipments() == null || request.shipments().isEmpty()) {
            log.info("AffinityConfigController: no shipments in request, loading from store for date '{}'", request.date());
            List<com.example.LMrouting.model.Shipment> stored = store.findShipmentsByDate(request.date());
            if (stored == null || stored.isEmpty()) {
                log.warn("AffinityConfigController: no shipments found in store for date '{}'", request.date());
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("No shipments found for date '" + request.date() + "'. Please upload shipment data first."));
            }
            List<AffinityRegion> regions = regionDensityAnalyzer.analyzeRegionDensity(stored, request.hubName());
            RegionDensityReportDto report = new RegionDensityReportDto(
                    request.hubName(), request.date(), stored.size(), regions.size(), regions);
            log.info("AffinityConfigController: analysis complete - {} regions created from {} stored shipments",
                     regions.size(), stored.size());
            return ResponseEntity.ok(report);
        }
        
        // Analyze regions
        List<AffinityRegion> regions = regionDensityAnalyzer.analyzeRegionDensity(
                request.shipments(), 
                request.hubName()
        );
        
        // Calculate totals
        int totalShipments = request.shipments().size();
        int totalRegions = regions.size();
        
        // Build response
        RegionDensityReportDto report = new RegionDensityReportDto(
                request.hubName(),
                request.date(),
                totalShipments,
                totalRegions,
                regions
        );
        
        log.info("AffinityConfigController: analysis complete - {} regions created from {} shipments",
                 totalRegions, totalShipments);
        
        return ResponseEntity.ok(report);
    }

    /**
     * Generate auto-suggested SR assignments based on region density.
     * 
     * POST /api/affinity/auto-distribute
     * 
     * Request body:
     * {
     *   "regions": [...],
     *   "presentSRs": ["SR001", "SR002", ...]
     * }
     * 
     * Response: AffinityConfiguration with suggested assignments
     * 
     * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 20.1
     */
    @PostMapping("/auto-distribute")
    public ResponseEntity<?> autoDistribute(@RequestBody AutoDistributeRequest request) {
        log.info("AffinityConfigController: auto-distributing {} SRs across {} regions",
                 request.presentSRs() != null ? request.presentSRs().size() : 0,
                 request.regions() != null ? request.regions().size() : 0);
        
        if (request.regions() == null || request.regions().isEmpty()) {
            log.warn("AffinityConfigController: no regions provided for auto-distribution");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Regions list cannot be null or empty"));
        }
        
        if (request.presentSRs() == null || request.presentSRs().isEmpty()) {
            log.warn("AffinityConfigController: no SRs provided for auto-distribution");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Present SRs list cannot be null or empty"));
        }
        
        // Auto-distribute SRs
        AffinityConfiguration config = affinityConfigService.autoDistributeSRs(
                request.regions(),
                request.presentSRs()
        );
        
        // Validate total assigned SRs does not exceed present SR count (Requirement 20.1)
        Set<String> uniqueAssignedSRs = config.getSrRegionAssignments().stream()
                .map(SrRegionAssignment::getSrName)
                .collect(java.util.stream.Collectors.toSet());
        
        if (uniqueAssignedSRs.size() > request.presentSRs().size()) {
            String errorMsg = String.format(
                    "Auto-distribution resulted in %d assigned SRs, which exceeds present SR count (%d). This is an internal error.",
                    uniqueAssignedSRs.size(), request.presentSRs().size());
            log.error("AffinityConfigController: {}", errorMsg);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(errorMsg));
        }
        
        log.info("AffinityConfigController: auto-distribution complete - {} assignments created",
                 config.getSrRegionAssignments().size());
        
        return ResponseEntity.ok(config);
    }

    /**
     * Update SR assignments for a specific region.
     * 
     * PUT /api/affinity/assign
     * 
     * Request body:
     * {
     *   "config": {...},
     *   "regionId": 123,
     *   "srNames": ["SR001", "SR002"],
     *   "presentSRs": ["SR001", "SR002", ...],
     *   "allRegions": [...]
     * }
     * 
     * Response: Updated AffinityConfiguration
     * 
     * Requirements: 5.1, 5.2, 5.3, 5.4, 20.1, 20.2, 20.5
     */
    @PutMapping("/assign")
    public ResponseEntity<?> updateAssignment(@RequestBody UpdateAssignmentRequest request) {
        log.info("AffinityConfigController: updating SR assignment for region {}",
                 request.regionId());
        
        if (request.config() == null) {
            log.warn("AffinityConfigController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }
        
        if (request.regionId() == null) {
            log.warn("AffinityConfigController: region ID is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Region ID cannot be null"));
        }
        
        if (request.presentSRs() == null || request.presentSRs().isEmpty()) {
            log.warn("AffinityConfigController: present SRs list is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Present SRs list cannot be null or empty"));
        }
        
        try {
            // Update SR assignment
            AffinityConfiguration updatedConfig = affinityConfigService.updateSRAssignment(
                    request.config(),
                    request.regionId(),
                    request.srNames(),
                    request.presentSRs(),
                    request.allRegions()
            );
            
            // Log configuration change (Requirement 24.1)
            Map<String, String> metadata = new HashMap<>();
            metadata.put("regionId", String.valueOf(request.regionId()));
            metadata.put("srNames", request.srNames() != null ? String.join(", ", request.srNames()) : "");
            metadata.put("assignmentCount", String.valueOf(request.srNames() != null ? request.srNames().size() : 0));
            
            auditLogService.logConfigurationChange(
                    AuditLog.EventType.ASSIGNMENT_UPDATED,
                    "supervisor", // TODO: Get from security context
                    String.format("Updated SR assignments for region %d", request.regionId()),
                    metadata
            );
            
            // Validate total assigned SRs does not exceed present SR count (Requirement 20.1)
            Set<String> uniqueAssignedSRs = updatedConfig.getSrRegionAssignments().stream()
                    .map(SrRegionAssignment::getSrName)
                    .collect(java.util.stream.Collectors.toSet());
            
            if (uniqueAssignedSRs.size() > request.presentSRs().size()) {
                String errorMsg = String.format(
                        "Total assigned SRs (%d) exceeds present SR count (%d). Please reduce SR assignments.",
                        uniqueAssignedSRs.size(), request.presentSRs().size());
                log.error("AffinityConfigController: {}", errorMsg);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse(errorMsg));
            }
            
            // Check for regions with >100 shipments and zero assigned SRs (Requirement 20.2)
            if (request.allRegions() != null) {
                List<String> warnings = new ArrayList<>();
                
                for (AffinityRegion region : request.allRegions()) {
                    if (region.getShipmentCount() > 100) {
                        long assignedSRCount = updatedConfig.getSrRegionAssignments().stream()
                                .filter(a -> a.getAffinityRegionId().equals(region.getId()))
                                .count();
                        
                        if (assignedSRCount == 0) {
                            String warning = String.format(
                                    "Warning: Region %s (pincode: %s) has %d shipments but zero assigned SRs",
                                    region.getId(), region.getPincode(), region.getShipmentCount());
                            warnings.add(warning);
                            log.warn("AffinityConfigController: {}", warning);
                        }
                    }
                }
                
                // If there are warnings, include them in the response
                if (!warnings.isEmpty()) {
                    log.info("AffinityConfigController: SR assignment updated with {} warnings", warnings.size());
                    return ResponseEntity.ok()
                            .header("X-Warnings", String.join("; ", warnings))
                            .body(updatedConfig);
                }
            }
            
            log.info("AffinityConfigController: SR assignment updated successfully for region {}",
                     request.regionId());
            
            return ResponseEntity.ok(updatedConfig);
            
        } catch (IllegalArgumentException e) {
            log.error("AffinityConfigController: validation error - {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Save configuration with a name for future reuse.
     * 
     * POST /api/affinity/save
     * 
     * Request body:
     * {
     *   "config": {...},
     *   "configName": "MyConfig"
     * }
     * 
     * Response: Saved AffinityConfiguration
     * 
     * Requirements: 19.1, 19.2
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveConfiguration(@RequestBody SaveConfigRequest request) {
        log.info("AffinityConfigController: saving configuration with name '{}'",
                 request.configName());
        
        if (request.config() == null) {
            log.warn("AffinityConfigController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }
        
        if (request.configName() == null || request.configName().trim().isEmpty()) {
            log.warn("AffinityConfigController: configuration name is empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration name cannot be null or empty"));
        }
        
        try {
            // Save configuration
            AffinityConfiguration savedConfig = affinityConfigService.saveConfiguration(
                    request.config(),
                    request.configName()
            );
            
            // Log configuration save (Requirement 24.1)
            Map<String, String> metadata = new HashMap<>();
            metadata.put("configName", request.configName());
            metadata.put("assignmentCount", String.valueOf(savedConfig.getSrRegionAssignments().size()));
            metadata.put("hubName", savedConfig.getHubName() != null ? savedConfig.getHubName() : "");
            
            auditLogService.logConfigurationChange(
                    AuditLog.EventType.CONFIG_SAVED,
                    "supervisor", // TODO: Get from security context
                    String.format("Saved configuration '%s'", request.configName()),
                    metadata
            );
            
            log.info("AffinityConfigController: configuration '{}' saved successfully",
                     request.configName());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(savedConfig);
            
        } catch (IllegalArgumentException e) {
            log.error("AffinityConfigController: validation error - {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Load a previously saved configuration by name.
     * 
     * GET /api/affinity/load/{configName}
     * 
     * Response: Loaded AffinityConfiguration
     * 
     * Requirements: 19.1, 19.2
     */
    @GetMapping("/load/{configName}")
    public ResponseEntity<?> loadConfiguration(@PathVariable String configName) {
        log.info("AffinityConfigController: loading configuration '{}'", configName);
        
        if (configName == null || configName.trim().isEmpty()) {
            log.warn("AffinityConfigController: configuration name is empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration name cannot be null or empty"));
        }
        
        try {
            // Load configuration
            AffinityConfiguration config = affinityConfigService.loadConfiguration(configName);
            
            // Log configuration load (Requirement 24.1)
            Map<String, String> metadata = new HashMap<>();
            metadata.put("configName", configName);
            metadata.put("assignmentCount", String.valueOf(config.getSrRegionAssignments().size()));
            
            auditLogService.logConfigurationChange(
                    AuditLog.EventType.CONFIG_LOADED,
                    "supervisor", // TODO: Get from security context
                    String.format("Loaded configuration '%s'", configName),
                    metadata
            );
            
            log.info("AffinityConfigController: configuration '{}' loaded successfully", configName);
            
            return ResponseEntity.ok(config);
            
        } catch (IllegalArgumentException e) {
            log.error("AffinityConfigController: configuration '{}' not found - {}",
                     configName, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Delete a saved configuration by name.
     * 
     * DELETE /api/affinity/delete/{configName}
     * 
     * Response: 204 No Content on success
     * 
     * Requirements: 19.5
     */
    @DeleteMapping("/delete/{configName}")
    public ResponseEntity<?> deleteConfiguration(@PathVariable String configName) {
        log.info("AffinityConfigController: deleting configuration '{}'", configName);
        
        if (configName == null || configName.trim().isEmpty()) {
            log.warn("AffinityConfigController: configuration name is empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration name cannot be null or empty"));
        }
        
        try {
            // Delete configuration
            affinityConfigService.deleteConfiguration(configName);
            
            // Log configuration deletion (Requirement 24.1)
            Map<String, String> metadata = new HashMap<>();
            metadata.put("configName", configName);
            
            auditLogService.logConfigurationChange(
                    AuditLog.EventType.CONFIG_DELETED,
                    "supervisor", // TODO: Get from security context
                    String.format("Deleted configuration '%s'", configName),
                    metadata
            );
            
            log.info("AffinityConfigController: configuration '{}' deleted successfully", configName);
            
            return ResponseEntity.noContent().build();
            
        } catch (IllegalArgumentException e) {
            log.error("AffinityConfigController: error deleting configuration '{}' - {}",
                     configName, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Validate configuration against current shipment data.
     * 
     * POST /api/affinity/validate
     * 
     * Request body:
     * {
     *   "config": {...},
     *   "currentShipments": [...]
     * }
     * 
     * Response:
     * {
     *   "isValid": true,
     *   "warnings": ["warning1", "warning2"]
     * }
     * 
     * Requirements: 19.3, 19.4
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateConfiguration(@RequestBody ValidateConfigRequest request) {
        log.info("AffinityConfigController: validating configuration '{}'",
                 request.config() != null ? request.config().getConfigName() : "unnamed");
        
        if (request.config() == null) {
            log.warn("AffinityConfigController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }
        
        try {
            // Validate configuration
            AffinityConfigService.ValidationResult result = affinityConfigService.validateConfiguration(
                    request.config(),
                    request.currentShipments()
            );
            
            // Convert to DTO
            ValidationResultDto dto = new ValidationResultDto(
                    result.isValid(),
                    result.getWarnings()
            );
            
            log.info("AffinityConfigController: validation complete - isValid={}, warnings={}",
                     result.isValid(), result.getWarnings().size());
            
            return ResponseEntity.ok(dto);
            
        } catch (IllegalArgumentException e) {
            log.error("AffinityConfigController: validation error - {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }
}
