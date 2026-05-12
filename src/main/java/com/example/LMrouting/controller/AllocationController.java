package com.example.LMrouting.controller;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AffinityConfigStorageService;
import com.example.LMrouting.service.AffinityShiftAllocationService;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OverrideManagerService;
import com.example.LMrouting.service.SrTimelineService;
import com.example.LMrouting.service.TravelTimeCacheService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/allocate")
@RequiredArgsConstructor
@Slf4j
public class AllocationController {

    private static final DateTimeFormatter FMT_DMY  = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_DMY2 = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final AllocationEngineService allocationEngineService;
    private final AffinityShiftAllocationService affinityShiftAllocationService;
    private final AffinityConfigStorageService affinityConfigStorageService;
    private final OverrideManagerService overrideManagerService;
    private final GoogleMapsService googleMapsService;
    private final TravelTimeCacheService travelTimeCache;
    private final SrTimelineService srTimelineService;
    private final InMemoryStore store;

    @PostMapping
    public ResponseEntity<AllocationSummary> allocate(@RequestBody AllocateRequest request) {
        LocalDate date = parseDate(request.date());
        AllocationSummary summary = allocationEngineService.allocate(date, request);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{date}/summary")
    public ResponseEntity<AllocationSummary> getSummary(@PathVariable("date") String dateStr) {
        AllocationSummary summary = allocationEngineService.getSummary(parseDate(dateStr));
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{date}/sr/{srName}")
    public ResponseEntity<SrRouteDto> getSrRoute(@PathVariable("date") String dateStr,
                                                  @PathVariable("srName") String srName) {
        SrRouteDto route = allocationEngineService.getSrRoute(parseDate(dateStr), srName);
        return ResponseEntity.ok(route);
    }

    @PostMapping("/{date}/override")
    public ResponseEntity<OverrideResult> override(@PathVariable("date") String dateStr,
                                                    @RequestBody OverrideRequest request) {
        OverrideResult result = overrideManagerService.reassign(
                request.shippingId(), request.targetSrName(), parseDate(dateStr));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{date}/override")
    public ResponseEntity<Void> undoOverride(@PathVariable("date") String dateStr) {
        overrideManagerService.undoLastOverride(parseDate(dateStr));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{date}/finalize")
    public ResponseEntity<Void> finalize(@PathVariable("date") String dateStr) {
        overrideManagerService.finalizeAllocation(parseDate(dateStr));
        return ResponseEntity.noContent().build();
    }

    /**
     * Get the road polyline for an SR's route.
     * Returns list of [lat, lng] pairs — follows real roads if ORS/Google Maps is configured.
     * GET /api/allocate/{date}/sr/{srName}/polyline
     */
    @GetMapping("/{date}/sr/{srName}/polyline")
    public ResponseEntity<List<double[]>> getSrPolyline(@PathVariable("date") String dateStr,
                                                         @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getRoutePolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the ORS (OpenStreetMap) road polyline for an SR's route.
     * GET /api/allocate/{date}/sr/{srName}/polyline/ors
     * GET /api/allocate/{date}/sr/{srName}/polyline/osm  (alias)
     */
    @GetMapping({"/{date}/sr/{srName}/polyline/ors", "/{date}/sr/{srName}/polyline/osm"})
    public ResponseEntity<List<double[]>> getSrOrsPolyline(@PathVariable("date") String dateStr,
                                                            @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getOrsPolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the Google Maps road polyline for an SR's route.
     * GET /api/allocate/{date}/sr/{srName}/polyline/google
     */
    @GetMapping("/{date}/sr/{srName}/polyline/google")
    public ResponseEntity<List<double[]>> getSrGooglePolyline(@PathVariable("date") String dateStr,
                                                               @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getGoogleMapsPolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the delivery timeline for an SR's route using SrTimelineService.
     *
     * Returns SrTimelineDto with stop-by-stop ETA progression aligned with packing-time calculations.
     * Uses the same Haversine-based travel-time logic as ShiftWorkloadCalculatorService,
     * ensuring the ETA shown to the supervisor matches what was used during allocation decisions.
     *
     * GET /api/allocate/{date}/sr/{srName}/timeline
     * Optional query param: startTime=09:00 (default 09:00)
     *
     * Returns 404 if the SR is not found or has no allocation for that date.
     */
    @GetMapping("/{date}/sr/{srName}/timeline")
    public ResponseEntity<SrTimelineDto> getSrTimeline(
            @PathVariable("date") String dateStr,
            @PathVariable("srName") String srName,
            @RequestParam(value = "startTime", defaultValue = "09:00") String startTime) {

        LocalDate date = parseDate(dateStr);
        String storeDateStr = resolveStoreDateStr(date);

        List<Shipment> shipments = store.findShipmentsByDateAndSr(storeDateStr, srName);
        if (shipments.isEmpty()) {
            throw new AllocationNotFoundException(
                    "No shipments found for SR '" + srName + "' on " + dateStr);
        }

        // Parse start time
        java.time.LocalTime start;
        try {
            start = java.time.LocalTime.parse(startTime);
        } catch (Exception e) {
            start = java.time.LocalTime.of(9, 0);
        }

        SrTimelineDto timeline = srTimelineService.buildTimeline(srName, shipments, start);
        return ResponseEntity.ok(timeline);
    }

    // =========================================================================
    // Draft Reassignment Endpoints
    // =========================================================================

    /**
     * Get draft reassignment recommendations (computed but NOT persisted).
     *
     * <p>Computes SR rebalancing suggestions based on the current allocation state:
     * identifies idle/underloaded SRs in one region that could be moved to
     * overloaded regions with overflow shipments.
     *
     * <p>Draft targets:
     * <ul>
     *   <li>80-90% utilization for reassigned SRs (moved to a new region)</li>
     *   <li>&gt;90% utilization for native-region SRs (staying in their original region)</li>
     * </ul>
     *
     * GET /api/allocate/{date}/rebalance/draft
     */
    @GetMapping("/{date}/rebalance/draft")
    public ResponseEntity<DraftReassignmentDto> getDraftReassignment(@PathVariable("date") String dateStr) {
        LocalDate date = parseDate(dateStr);

        // Get the latest allocation summary to extract region summaries and suggestions
        AllocationSummary summary = allocationEngineService.getSummary(date);

        List<SrRebalanceSuggestion> recommendations = new ArrayList<>();
        int estimatedAdditionalShipments = 0;

        if (summary.regionSummaries() != null) {
            for (RegionSummaryDto region : summary.regionSummaries()) {
                if (region.suggestions() != null) {
                    recommendations.addAll(region.suggestions());
                }
            }
            // Estimate additional shipments that could be allocated
            estimatedAdditionalShipments = recommendations.stream()
                    .mapToInt(SrRebalanceSuggestion::toRegionOverflow)
                    .sum();
        }

        int highCount = (int) recommendations.stream()
                .filter(r -> "HIGH".equals(r.priority()))
                .count();
        int mediumCount = (int) recommendations.stream()
                .filter(r -> "MEDIUM".equals(r.priority()))
                .count();

        String message = recommendations.isEmpty()
                ? "No reassignment recommendations — all regions are balanced."
                : String.format("%d recommendation(s) found. Review and POST to /rebalance/save to apply.",
                        recommendations.size());

        DraftReassignmentDto draft = new DraftReassignmentDto(
                dateStr,
                "draft",
                recommendations,
                recommendations.size(),
                highCount,
                mediumCount,
                80.0,
                90.0,
                90.0,
                estimatedAdditionalShipments,
                message
        );

        return ResponseEntity.ok(draft);
    }

    /**
     * Persist draft reassignment — updates SR-to-region assignments in affinity
     * config and re-runs allocation.
     *
     * <p>Accepts the list of SR reassignment suggestions to apply. For each
     * suggestion, the SR is moved from its current region to the target region
     * in the affinity configuration (srZoneMap). Then allocation is re-run
     * with the updated configuration.
     *
     * POST /api/allocate/{date}/rebalance/save
     *
     * Request body (optional): list of SR names to reassign. If empty/null,
     * applies ALL draft recommendations.
     */
    @PostMapping("/{date}/rebalance/save")
    @SuppressWarnings("unchecked")
    public ResponseEntity<DraftReassignmentDto> saveDraftReassignment(
            @PathVariable("date") String dateStr,
            @RequestBody(required = false) Map<String, Object> requestBody) {

        LocalDate date = parseDate(dateStr);

        // Step 1: Get current draft recommendations
        AllocationSummary summary = allocationEngineService.getSummary(date);

        List<SrRebalanceSuggestion> allRecommendations = new ArrayList<>();
        if (summary.regionSummaries() != null) {
            for (RegionSummaryDto region : summary.regionSummaries()) {
                if (region.suggestions() != null) {
                    allRecommendations.addAll(region.suggestions());
                }
            }
        }

        if (allRecommendations.isEmpty()) {
            DraftReassignmentDto result = new DraftReassignmentDto(
                    dateStr, "saved", List.of(), 0, 0, 0,
                    80.0, 90.0, 90.0, 0,
                    "No recommendations to apply — all regions are already balanced."
            );
            return ResponseEntity.ok(result);
        }

        // Step 2: Determine which SRs to reassign
        List<String> srNamesToReassign = null;
        if (requestBody != null && requestBody.containsKey("srNames")) {
            Object srNamesObj = requestBody.get("srNames");
            if (srNamesObj instanceof List) {
                srNamesToReassign = (List<String>) srNamesObj;
            }
        }

        // Filter recommendations to only those requested (or all if none specified)
        List<SrRebalanceSuggestion> toApply;
        if (srNamesToReassign != null && !srNamesToReassign.isEmpty()) {
            final List<String> finalSrNames = srNamesToReassign;
            toApply = allRecommendations.stream()
                    .filter(r -> finalSrNames.contains(r.srName()))
                    .toList();
        } else {
            toApply = allRecommendations;
        }

        if (toApply.isEmpty()) {
            DraftReassignmentDto result = new DraftReassignmentDto(
                    dateStr, "saved", List.of(), 0, 0, 0,
                    80.0, 90.0, 90.0, 0,
                    "No matching recommendations found for the specified SR names."
            );
            return ResponseEntity.ok(result);
        }

        // Step 3: Update affinity config — move SRs to new regions in srZoneMap
        try {
            Map<String, Object> config = affinityConfigStorageService.loadConfig();
            if (config == null) config = new HashMap<>();

            Map<String, String> srZoneMap = (Map<String, String>) config.get("srZoneMap");
            if (srZoneMap == null) {
                srZoneMap = new HashMap<>();
                config.put("srZoneMap", srZoneMap);
            }

            for (SrRebalanceSuggestion suggestion : toApply) {
                log.info("Rebalance/save: moving SR '{}' from region '{}' to region '{}'",
                        suggestion.srName(), suggestion.fromRegion(), suggestion.toRegion());
                srZoneMap.put(suggestion.srName(), suggestion.toRegion());
            }

            // Also update assignedSRs in region definitions
            List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
            if (regions != null) {
                for (SrRebalanceSuggestion suggestion : toApply) {
                    // Remove from source region's assignedSRs
                    for (Map<String, Object> region : regions) {
                        String regionName = (String) region.get("name");
                        if (suggestion.fromRegion().equals(regionName)) {
                            List<String> assignedSRs = (List<String>) region.get("assignedSRs");
                            if (assignedSRs != null) {
                                assignedSRs.remove(suggestion.srName());
                            }
                        }
                    }
                    // Add to target region's assignedSRs
                    for (Map<String, Object> region : regions) {
                        String regionName = (String) region.get("name");
                        if (suggestion.toRegion().equals(regionName)) {
                            List<String> assignedSRs = (List<String>) region.get("assignedSRs");
                            if (assignedSRs == null) {
                                assignedSRs = new ArrayList<>();
                                region.put("assignedSRs", assignedSRs);
                            }
                            if (!assignedSRs.contains(suggestion.srName())) {
                                assignedSRs.add(suggestion.srName());
                            }
                        }
                    }
                }
            }

            affinityConfigStorageService.saveConfig(config);
            log.info("Rebalance/save: affinity config updated with {} SR reassignment(s)", toApply.size());

        } catch (IOException e) {
            log.error("Rebalance/save: failed to update affinity config", e);
            DraftReassignmentDto errorResult = new DraftReassignmentDto(
                    dateStr, "error", toApply, toApply.size(), 0, 0,
                    80.0, 90.0, 90.0, 0,
                    "Failed to persist reassignment: " + e.getMessage()
            );
            return ResponseEntity.internalServerError().body(errorResult);
        }

        // Step 4: Re-run allocation with updated config
        AllocateRequest rerunRequest = new AllocateRequest(dateStr, "time-based");
        AllocationSummary newSummary = allocationEngineService.allocate(date, rerunRequest);

        int estimatedAdditional = toApply.stream()
                .mapToInt(SrRebalanceSuggestion::toRegionOverflow)
                .sum();

        int highCount = (int) toApply.stream()
                .filter(r -> "HIGH".equals(r.priority()))
                .count();
        int mediumCount = (int) toApply.stream()
                .filter(r -> "MEDIUM".equals(r.priority()))
                .count();

        DraftReassignmentDto result = new DraftReassignmentDto(
                dateStr,
                "saved",
                toApply,
                toApply.size(),
                highCount,
                mediumCount,
                80.0,
                90.0,
                90.0,
                estimatedAdditional,
                String.format("Applied %d reassignment(s). Allocation re-run complete. " +
                        "New allocation: %d allocated, %d unallocated.",
                        toApply.size(),
                        newSummary.allocatedShipments(),
                        newSummary.unallocatedShipments())
        );

        return ResponseEntity.ok(result);
    }

    /**
     * Resolve the date string format used in InMemoryStore for a given LocalDate.
     * Tries multiple formats (dd-MMM-yy, d-MMM-yy, dd/MM/yyyy, ISO) to find
     * the one that matches existing data in the store.
     */
    private String resolveStoreDateStr(LocalDate date) {
        DateTimeFormatter[] fmts = { FMT_DMY, FMT_DMY2, FMT_SLASH };
        for (DateTimeFormatter fmt : fmts) {
            String candidate = date.format(fmt);
            if (store.hasShipmentsForDate(candidate)) return candidate;
        }
        String iso = date.toString();
        if (store.hasShipmentsForDate(iso)) return iso;
        // Default to dd-MMM-yy format
        return date.format(FMT_DMY);
    }

    /**
     * Parse date strings in multiple formats:
     * - ISO: 2026-03-24
     * - dd-MMM-yy: 24-Mar-26
     * - dd/MM/yyyy: 24/03/2026
     */
    static LocalDate parseDate(String dateStr) {
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
}
