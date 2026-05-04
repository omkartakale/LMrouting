package com.example.LMrouting.controller;

import com.example.LMrouting.exception.AllocationFailureException;
import com.example.LMrouting.model.*;
import com.example.LMrouting.service.AffinityAllocationEngineService;
import com.example.LMrouting.service.AffinityComparisonService;
import com.example.LMrouting.service.AuditLogService;
import com.example.LMrouting.service.RegionDensityAnalyzer;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for affinity allocation execution.
 * 
 * Provides endpoints for:
 * - Previewing affinity allocation results
 * - Comparing standard mode vs affinity mode allocations
 * - Executing and finalizing affinity allocation
 * - Retrieving allocation history filtered by mode
 * 
 * Requirements: 1.5, 11.1, 12.1, 18.5
 */
@RestController
@RequestMapping("/api/affinity")
@RequiredArgsConstructor
@Slf4j
public class AffinityAllocationController {

    private static final DateTimeFormatter FMT_DMY = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_DMY2 = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final AffinityAllocationEngineService affinityAllocationEngineService;
    private final AffinityComparisonService affinityComparisonService;
    private final RegionDensityAnalyzer regionDensityAnalyzer;
    private final InMemoryStore store;
    private final AuditLogService auditLogService;

    /**
     * Execute preview allocation without persisting results.
     * 
     * POST /api/affinity/preview
     * 
     * Request body:
     * {
     *   "date": "2026-03-24",
     *   "hubName": "HUB001",
     *   "config": {...},
     *   "regions": [...]
     * }
     * 
     * Response: AffinityAllocationPreview with expected earnings, shipment counts,
     *           variance, cross-region percentage, and warnings
     * 
     * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 20.3, 20.4
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewAllocation(@RequestBody PreviewAllocationRequest request) {
        log.info("AffinityAllocationController: previewing allocation for date='{}', hub='{}'",
                request.date(), request.hubName());

        // Validate request
        if (request.date() == null || request.date().trim().isEmpty()) {
            log.warn("AffinityAllocationController: date is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Date cannot be null or empty"));
        }

        if (request.hubName() == null || request.hubName().trim().isEmpty()) {
            log.warn("AffinityAllocationController: hub name is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Hub name cannot be null or empty"));
        }

        if (request.config() == null) {
            log.warn("AffinityAllocationController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }

        try {
            // Parse date
            String dateStr = request.date();

            // Validate shipment data before proceeding
            List<Shipment> shipments = store.findShipmentsByDate(dateStr);
            String validationError = validateShipmentData(shipments);
            if (validationError != null) {
                log.error("AffinityAllocationController: shipment data validation failed - {}", validationError);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse(validationError + 
                                " Affinity mode requires valid pincode and coordinate data for all shipments. " +
                                "Please correct the data or use standard allocation mode."));
            }

            // Get regions - either from request or analyze from shipments
            List<AffinityRegion> regions = request.regions();
            if (regions == null || regions.isEmpty()) {
                log.info("AffinityAllocationController: no regions provided, analyzing from shipments");
                regions = regionDensityAnalyzer.analyzeRegionDensity(shipments, request.hubName());
                log.info("AffinityAllocationController: created {} regions from shipments", regions.size());
            }

            // Execute preview allocation
            AffinityAllocationPreview preview = affinityAllocationEngineService.previewAffinityAllocation(
                    dateStr,
                    request.hubName(),
                    request.config(),
                    regions
            );

            log.info("AffinityAllocationController: preview complete - {} SRs, {} warnings",
                    preview.getTotalSrs(), preview.getWarnings().size());

            return ResponseEntity.ok(preview);

        } catch (AllocationFailureException e) {
            log.error("AffinityAllocationController: allocation failure - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse(e.getMessage() + 
                            " Consider reverting to standard allocation mode for this dataset."));
        } catch (IllegalArgumentException e) {
            log.error("AffinityAllocationController: validation error - {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("AffinityAllocationController: error during preview - {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error during preview allocation: " + e.getMessage()));
        }
    }

    /**
     * Execute comparison between standard mode and affinity mode allocations.
     * 
     * POST /api/affinity/compare
     * 
     * Request body:
     * {
     *   "date": "2026-03-24",
     *   "hubName": "HUB001",
     *   "config": {...}
     * }
     * 
     * Response: AffinityComparisonResult with side-by-side metrics for both modes,
     *           including earnings distribution, variance, average distance, and
     *           percentage improvement/degradation
     * 
     * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 20.3, 20.4
     */
    @PostMapping("/compare")
    public ResponseEntity<?> compareAllocations(@RequestBody CompareAllocationsRequest request) {
        log.info("AffinityAllocationController: comparing allocations for date='{}', hub='{}'",
                request.date(), request.hubName());

        // Validate request
        if (request.date() == null || request.date().trim().isEmpty()) {
            log.warn("AffinityAllocationController: date is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Date cannot be null or empty"));
        }

        if (request.hubName() == null || request.hubName().trim().isEmpty()) {
            log.warn("AffinityAllocationController: hub name is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Hub name cannot be null or empty"));
        }

        if (request.config() == null) {
            log.warn("AffinityAllocationController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }

        try {
            // Validate shipment data before proceeding
            List<Shipment> shipments = store.findShipmentsByDate(request.date());
            String validationError = validateShipmentData(shipments);
            if (validationError != null) {
                log.error("AffinityAllocationController: shipment data validation failed - {}", validationError);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse(validationError + 
                                " Affinity mode requires valid pincode and coordinate data for all shipments. " +
                                "Please correct the data or use standard allocation mode."));
            }

            // Execute comparison
            AffinityComparisonResult comparison = affinityComparisonService.compareAllocations(
                    request.date(),
                    request.hubName(),
                    request.config()
            );

            log.info("AffinityAllocationController: comparison complete - " +
                            "earnings variance change: {:.2f}%, average distance change: {:.2f}%",
                    comparison.getComparison().getEarningsVarianceChange(),
                    comparison.getComparison().getAverageDistanceChange());

            return ResponseEntity.ok(comparison);

        } catch (AllocationFailureException e) {
            log.error("AffinityAllocationController: allocation failure - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse(e.getMessage() + 
                            " Consider reverting to standard allocation mode for this dataset."));
        } catch (IllegalArgumentException e) {
            log.error("AffinityAllocationController: validation error - {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("AffinityAllocationController: error during comparison - {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error during allocation comparison: " + e.getMessage()));
        }
    }

    /**
     * Execute and finalize affinity allocation, persisting results to the database.
     * 
     * POST /api/affinity/execute
     * 
     * Request body:
     * {
     *   "date": "2026-03-24",
     *   "hubName": "HUB001",
     *   "config": {...},
     *   "regions": [...]
     * }
     * 
     * Response: AffinityAllocationResult with SR assignments and metrics
     * 
     * Requirements: 1.4, 18.1, 18.2, 18.3, 18.4, 20.3, 20.4
     */
    @PostMapping("/execute")
    public ResponseEntity<?> executeAllocation(@RequestBody ExecuteAllocationRequest request) {
        log.info("AffinityAllocationController: executing affinity allocation for date='{}', hub='{}'",
                request.date(), request.hubName());

        // Validate request
        if (request.date() == null || request.date().trim().isEmpty()) {
            log.warn("AffinityAllocationController: date is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Date cannot be null or empty"));
        }

        if (request.hubName() == null || request.hubName().trim().isEmpty()) {
            log.warn("AffinityAllocationController: hub name is null or empty");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Hub name cannot be null or empty"));
        }

        if (request.config() == null) {
            log.warn("AffinityAllocationController: configuration is null");
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Configuration cannot be null"));
        }

        try {
            // Parse date
            String dateStr = request.date();

            // Validate shipment data before proceeding
            List<Shipment> shipments = store.findShipmentsByDate(dateStr);
            String validationError = validateShipmentData(shipments);
            if (validationError != null) {
                log.error("AffinityAllocationController: shipment data validation failed - {}", validationError);
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse(validationError + 
                                " Affinity mode requires valid pincode and coordinate data for all shipments. " +
                                "Please correct the data or use standard allocation mode."));
            }

            // Get regions - either from request or analyze from shipments
            List<AffinityRegion> regions = request.regions();
            if (regions == null || regions.isEmpty()) {
                log.info("AffinityAllocationController: no regions provided, analyzing from shipments");
                regions = regionDensityAnalyzer.analyzeRegionDensity(shipments, request.hubName());
                log.info("AffinityAllocationController: created {} regions from shipments", regions.size());
            }

            // Execute affinity allocation
            AffinityAllocationResult result = affinityAllocationEngineService.executeAffinityAllocation(
                    dateStr,
                    request.hubName(),
                    request.config(),
                    regions
            );

            // Persist allocation run with affinity mode
            AllocationRun allocationRun = AllocationRun.builder()
                    .allocationDate(parseDate(dateStr))
                    .status(AllocationStatus.COMPLETED)
                    .totalShipments(result.getMetrics().getTotalShipments())
                    .totalSrs(result.getMetrics().getTotalSrs())
                    .fairnessVariance(result.getMetrics().getEarningsVariance())
                    .earningsRange(calculateEarningsRange(result))
                    .allocationMode(AllocationMode.AFFINITY)
                    .affinityConfigurationId(request.config().getId())
                    .crossRegionPercentage(result.getMetrics().getCrossRegionOverlapPercentage())
                    .createdAt(java.time.LocalDateTime.now())
                    .build();

            // Store allocation run
            store.saveAllocationRun(allocationRun);
            
            // Log allocation execution start (Requirement 24.2)
            Map<String, String> startMetadata = new HashMap<>();
            startMetadata.put("date", dateStr);
            startMetadata.put("hubName", request.hubName());
            startMetadata.put("allocationMode", "AFFINITY");
            
            auditLogService.logAllocationExecution(
                    AuditLog.EventType.ALLOCATION_STARTED,
                    allocationRun.getId(),
                    String.format("Started affinity allocation for date %s, hub %s", dateStr, request.hubName()),
                    startMetadata
            );
            
            // Log allocation completion (Requirement 24.2)
            Map<String, String> completionMetadata = new HashMap<>();
            completionMetadata.put("date", dateStr);
            completionMetadata.put("hubName", request.hubName());
            completionMetadata.put("allocationMode", "AFFINITY");
            completionMetadata.put("totalShipments", String.valueOf(result.getMetrics().getTotalShipments()));
            completionMetadata.put("totalSrs", String.valueOf(result.getMetrics().getTotalSrs()));
            
            auditLogService.logAllocationExecution(
                    AuditLog.EventType.ALLOCATION_COMPLETED,
                    allocationRun.getId(),
                    String.format("Completed affinity allocation for date %s, hub %s", dateStr, request.hubName()),
                    completionMetadata
            );
            
            // Log allocation metrics (Requirement 24.3)
            auditLogService.logAllocationMetrics(
                    allocationRun.getId(),
                    result.getMetrics().getEarningsVariance(),
                    result.getMetrics().getCrossRegionOverlapPercentage(),
                    (double) result.getMetrics().getTotalShipments() / result.getMetrics().getTotalSrs()
            );
            
            // Log warnings for regions with zero SRs (Requirement 24.4)
            for (AffinityRegion region : regions) {
                if (region.getShipmentCount() > 40) {
                    long assignedSRCount = request.config().getSrRegionAssignments().stream()
                            .filter(a -> a.getAffinityRegionId().equals(region.getId()))
                            .count();
                    
                    if (assignedSRCount == 0) {
                        Map<String, String> warningMetadata = new HashMap<>();
                        warningMetadata.put("regionId", String.valueOf(region.getId()));
                        warningMetadata.put("pincode", region.getPincode());
                        warningMetadata.put("shipmentCount", String.valueOf(region.getShipmentCount()));
                        
                        auditLogService.logWarning(
                                AuditLog.EventType.WARNING_ZERO_SRS,
                                allocationRun.getId(),
                                String.format("Region %s (pincode: %s) has %d shipments but zero assigned SRs",
                                        region.getId(), region.getPincode(), region.getShipmentCount()),
                                warningMetadata
                        );
                    }
                }
            }
            
            // Log warning if variance threshold exceeded (Requirement 24.4)
            // Assuming a threshold of 0.20 (20% variance)
            if (result.getMetrics().getEarningsVariance() > 0.20) {
                Map<String, String> varianceMetadata = new HashMap<>();
                varianceMetadata.put("earningsVariance", String.format("%.4f", result.getMetrics().getEarningsVariance()));
                varianceMetadata.put("threshold", "0.20");
                
                auditLogService.logWarning(
                        AuditLog.EventType.WARNING_VARIANCE_EXCEEDED,
                        allocationRun.getId(),
                        String.format("Earnings variance (%.4f) exceeds threshold (0.20)",
                                result.getMetrics().getEarningsVariance()),
                        varianceMetadata
                );
            }

            log.info("AffinityAllocationController: affinity allocation executed and persisted - " +
                            "{} SRs, {} shipments, cross-region: {:.1f}%",
                    result.getMetrics().getTotalSrs(),
                    result.getMetrics().getTotalShipments(),
                    result.getMetrics().getCrossRegionOverlapPercentage());

            return ResponseEntity.status(HttpStatus.CREATED).body(result);

        } catch (AllocationFailureException e) {
            log.error("AffinityAllocationController: allocation failure - {}", e.getMessage());
            
            // Log allocation failure error (Requirement 24.4)
            Map<String, String> errorMetadata = new HashMap<>();
            errorMetadata.put("date", request.date());
            errorMetadata.put("hubName", request.hubName());
            errorMetadata.put("errorMessage", e.getMessage());
            
            auditLogService.logError(
                    AuditLog.EventType.ERROR_ALLOCATION,
                    String.format("Allocation failed for date %s, hub %s: %s",
                            request.date(), request.hubName(), e.getMessage()),
                    errorMetadata
            );
            
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse(e.getMessage() + 
                            " Consider reverting to standard allocation mode for this dataset."));
        } catch (IllegalArgumentException e) {
            log.error("AffinityAllocationController: validation error - {}", e.getMessage());
            
            // Log validation error (Requirement 24.4)
            Map<String, String> errorMetadata = new HashMap<>();
            errorMetadata.put("date", request.date());
            errorMetadata.put("hubName", request.hubName());
            errorMetadata.put("errorMessage", e.getMessage());
            
            auditLogService.logError(
                    AuditLog.EventType.ERROR_VALIDATION,
                    String.format("Validation error for date %s, hub %s: %s",
                            request.date(), request.hubName(), e.getMessage()),
                    errorMetadata
            );
            
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("AffinityAllocationController: error during execution - {}", e.getMessage(), e);
            
            // Log general error (Requirement 24.4)
            Map<String, String> errorMetadata = new HashMap<>();
            errorMetadata.put("date", request.date());
            errorMetadata.put("hubName", request.hubName());
            errorMetadata.put("errorMessage", e.getMessage());
            errorMetadata.put("errorType", e.getClass().getSimpleName());
            
            auditLogService.logError(
                    AuditLog.EventType.ERROR_ALLOCATION,
                    String.format("Unexpected error during allocation for date %s, hub %s: %s",
                            request.date(), request.hubName(), e.getMessage()),
                    errorMetadata
            );
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error during affinity allocation execution: " + e.getMessage()));
        }
    }

    /**
     * Get allocation history filtered by allocation mode.
     * 
     * GET /api/affinity/history?mode=AFFINITY
     * GET /api/affinity/history?mode=STANDARD
     * GET /api/affinity/history (returns all)
     * 
     * Response: List of AllocationRun objects filtered by mode
     * 
     * Requirements: 1.5, 18.5
     */
    @GetMapping("/history")
    public ResponseEntity<?> getAllocationHistory(
            @RequestParam(required = false) AllocationMode mode) {
        log.info("AffinityAllocationController: retrieving allocation history, mode filter={}",
                mode != null ? mode : "ALL");

        try {
            // Get all allocation runs
            List<AllocationRun> allRuns = store.getAllAllocationRuns();

            // Filter by mode if specified
            List<AllocationRun> filteredRuns;
            if (mode != null) {
                filteredRuns = allRuns.stream()
                        .filter(run -> mode.equals(run.getAllocationMode()))
                        .collect(Collectors.toList());
                log.info("AffinityAllocationController: found {} allocation runs with mode {}",
                        filteredRuns.size(), mode);
            } else {
                filteredRuns = allRuns;
                log.info("AffinityAllocationController: found {} total allocation runs",
                        filteredRuns.size());
            }

            // Sort by date descending (most recent first)
            filteredRuns.sort((a, b) -> b.getAllocationDate().compareTo(a.getAllocationDate()));

            return ResponseEntity.ok(filteredRuns);

        } catch (Exception e) {
            log.error("AffinityAllocationController: error retrieving history - {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error retrieving allocation history: " + e.getMessage()));
        }
    }

    /**
     * Get complete audit trail for a specific allocation run.
     * 
     * GET /api/affinity/audit/{allocationRunId}
     * 
     * Returns chronological list of all audit events for the specified allocation run,
     * including configuration changes, execution events, warnings, and errors.
     * 
     * Response: List of AuditLog objects in chronological order
     * 
     * Requirements: 24.5
     */
    @GetMapping("/audit/{allocationRunId}")
    public ResponseEntity<?> getAuditTrail(@PathVariable Long allocationRunId) {
        log.info("AffinityAllocationController: retrieving audit trail for allocationRunId={}",
                allocationRunId);

        try {
            // Validate allocation run exists
            if (allocationRunId == null) {
                log.warn("AffinityAllocationController: allocation run ID is null");
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Allocation run ID cannot be null"));
            }

            // Get audit trail
            List<AuditLog> auditTrail = auditLogService.getAuditTrail(allocationRunId);

            log.info("AffinityAllocationController: found {} audit log entries for allocationRunId={}",
                    auditTrail.size(), allocationRunId);

            return ResponseEntity.ok(auditTrail);

        } catch (Exception e) {
            log.error("AffinityAllocationController: error retrieving audit trail - {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error retrieving audit trail: " + e.getMessage()));
        }
    }

    /**
     * Calculate earnings range (max - min) from allocation result.
     */
    private double calculateEarningsRange(AffinityAllocationResult result) {
        if (result.getMetrics().getPerSrMetrics().isEmpty()) {
            return 0.0;
        }

        double minEarnings = result.getMetrics().getPerSrMetrics().values().stream()
                .mapToDouble(AffinityAllocationMetrics.SrMetrics::getEarnings)
                .min()
                .orElse(0.0);

        double maxEarnings = result.getMetrics().getPerSrMetrics().values().stream()
                .mapToDouble(AffinityAllocationMetrics.SrMetrics::getEarnings)
                .max()
                .orElse(0.0);

        return maxEarnings - minEarnings;
    }

    /**
     * Parse date strings in multiple formats:
     * - ISO: 2026-03-24
     * - dd-MMM-yy: 24-Mar-26
     * - dd/MM/yyyy: 24/03/2026
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Date cannot be blank.");
        }
        String s = dateStr.trim();

        // Try ISO first
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignored) {}

        // Try dd-MMM-yy (e.g. 24-Mar-26)
        try { return LocalDate.parse(s, FMT_DMY); } catch (DateTimeParseException ignored) {}

        // Try d-MMM-yy (e.g. 4-Mar-26)
        try { return LocalDate.parse(s, FMT_DMY2); } catch (DateTimeParseException ignored) {}

        // Try dd/MM/yyyy
        try { return LocalDate.parse(s, FMT_SLASH); } catch (DateTimeParseException ignored) {}

        throw new IllegalArgumentException(
                "Cannot parse date '" + s + "'. Supported formats: yyyy-MM-dd, dd-MMM-yy, dd/MM/yyyy");
    }

    // ===== Request DTOs =====

    /**
     * Request DTO for preview allocation endpoint.
     */
    public record PreviewAllocationRequest(
            String date,
            String hubName,
            AffinityConfiguration config,
            List<AffinityRegion> regions
    ) {}

    /**
     * Request DTO for compare allocations endpoint.
     */
    public record CompareAllocationsRequest(
            String date,
            String hubName,
            AffinityConfiguration config
    ) {}

    /**
     * Request DTO for execute allocation endpoint.
     */
    public record ExecuteAllocationRequest(
            String date,
            String hubName,
            AffinityConfiguration config,
            List<AffinityRegion> regions
    ) {}

    /**
     * Error response DTO.
     */
    public record ErrorResponse(String message) {}

    /**
     * Validates shipment data for affinity mode allocation.
     * 
     * Checks that all shipments have:
     * - Valid pincode (not null or empty)
     * - Valid coordinates (not 0.0, 0.0)
     * 
     * Requirements: 20.4
     * 
     * @param shipments List of shipments to validate
     * @return Error message if validation fails, null if validation passes
     */
    private String validateShipmentData(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) {
            return "No shipments found for the specified date.";
        }

        long shipmentsWithMissingPincode = shipments.stream()
                .filter(s -> s.getDropPincode() == null || s.getDropPincode().trim().isEmpty())
                .count();

        long shipmentsWithMissingCoordinates = shipments.stream()
                .filter(s -> s.getDropLatitude() == 0.0 && s.getDropLongitude() == 0.0)
                .count();

        if (shipmentsWithMissingPincode > 0) {
            return String.format("Found %d shipment(s) with missing pincode data.", shipmentsWithMissingPincode);
        }

        if (shipmentsWithMissingCoordinates > 0) {
            return String.format("Found %d shipment(s) with missing coordinate data.", shipmentsWithMissingCoordinates);
        }

        return null; // Validation passed
    }
}
