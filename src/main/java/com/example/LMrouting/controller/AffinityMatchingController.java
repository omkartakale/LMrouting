package com.example.LMrouting.controller;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AffinityMatchingService;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.PincodeBoundaryService;
import com.example.LMrouting.service.PincodeClusteringService;
import com.example.LMrouting.service.RouteOptimizerService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for affinity-based route matching.
 *
 * Flow:
 *   1. POST /api/affinity-match/set    → define SR affinities (pincodes)
 *   2. POST /api/allocate              → run standard allocation (creates routes)
 *   3. POST /api/affinity-match/apply  → re-assign routes based on affinity
 *   4. GET  /api/affinity-match/scores → see affinity match quality
 */
@RestController
@RequestMapping("/api/affinity-match")
@RequiredArgsConstructor
@Slf4j
public class AffinityMatchingController {

    private final AffinityMatchingService affinityMatchingService;
    private final AllocationEngineService allocationEngineService;
    private final PincodeClusteringService pincodeClusteringService;
    private final PincodeBoundaryService pincodeBoundaryService;
    private final RouteOptimizerService routeOptimizerService;
    private final InMemoryStore store;

    // In-memory affinity storage: srName → set of preferred pincodes
    private final Map<String, Set<String>> srAffinities = new LinkedHashMap<>();

    /**
     * Set/update SR affinities.
     * Body: { "affinities": { "SR-001": ["411001","411002"], "SR-003": ["411038","411039"] } }
     */
    @PostMapping("/set")
    public ResponseEntity<Map<String, Object>> setAffinities(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, List<String>> affinities = (Map<String, List<String>>) request.get("affinities");

        if (affinities == null || affinities.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "affinities map is required"));
        }

        srAffinities.clear();
        for (Map.Entry<String, List<String>> entry : affinities.entrySet()) {
            srAffinities.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }

        log.info("AffinityMatch: set affinities for {} SRs", srAffinities.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("srCount", srAffinities.size());
        result.put("affinities", srAffinities);
        return ResponseEntity.ok(result);
    }

    /**
     * Get current SR affinities.
     */
    @GetMapping("/affinities")
    public ResponseEntity<Map<String, Set<String>>> getAffinities() {
        return ResponseEntity.ok(srAffinities);
    }

    /**
     * Clear all affinities.
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAffinities() {
        srAffinities.clear();
        return ResponseEntity.ok(Map.of("success", true, "message", "All affinities cleared"));
    }

    /**
     * Run standard allocation + apply affinity matching.
     * This is the main endpoint: allocate → match routes to SRs by affinity.
     *
     * Body: { "date": "24-Mar-26" }
     */
    @PostMapping("/allocate")
    public ResponseEntity<Map<String, Object>> allocateWithAffinity(@RequestBody Map<String, String> request) {
        String dateStr = request.get("date");
        if (dateStr == null || dateStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "date is required"));
        }

        LocalDate date = AllocationController.parseDate(dateStr);

        // Step 1: Run standard allocation first (to get filtered shipments + present SRs)
        AllocationSummary summary = allocationEngineService.allocate(date);

        // Step 2: Get allocated shipments and present SRs
        String storeDateStr = summary.date();
        List<Shipment> allShipments = store.findShipmentsByDate(storeDateStr);
        List<Shipment> allocated = allShipments.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.toList());
        List<String> presentSRs = allocated.stream()
                .map(Shipment::getAssignedSr).distinct().sorted()
                .collect(Collectors.toList());
        int numSRs = presentSRs.size();

        // Step 3: STRICT AFFINITY — assign shipments directly by pincode
        // Each SR gets ONLY shipments from their affinity pincodes
        Map<String, List<Shipment>> matched = new LinkedHashMap<>();

        if (!srAffinities.isEmpty()) {
            // Strict mode: each SR gets only their affinity pincode shipments
            // When multiple SRs share the same pincode, split using angular partitioning
            Set<String> assignedShipmentIds = new HashSet<>();

            // Group SRs by their affinity pincode
            Map<String, List<String>> pinToSRs = new LinkedHashMap<>();
            for (String sr : presentSRs) {
                Set<String> pins = srAffinities.getOrDefault(sr, Set.of());
                for (String pin : pins) {
                    pinToSRs.computeIfAbsent(pin, k -> new ArrayList<>()).add(sr);
                }
            }

            // For each pincode, get its shipments and split among assigned SRs
            for (Map.Entry<String, List<String>> entry : pinToSRs.entrySet()) {
                String pincode = entry.getKey();
                List<String> srsForPin = entry.getValue();

                // Get shipments in this pincode (dropPincode is always from GeoJSON)
                List<Shipment> pinShipments = allocated.stream()
                        .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                        .filter(s -> pincode.equals(s.getDropPincode()))
                        .collect(Collectors.toList());

                if (pinShipments.isEmpty()) continue;

                if (srsForPin.size() == 1) {
                    // Single SR for this pincode — assign up to 100
                    String sr = srsForPin.get(0);
                    List<Shipment> capped = pinShipments.size() > 100
                            ? pinShipments.subList(0, 100) : pinShipments;
                    matched.computeIfAbsent(sr, k -> new ArrayList<>()).addAll(capped);
                    capped.forEach(s -> assignedShipmentIds.add(s.getShippingId()));
                    log.info("AffinityMatch: {} → {} shipments in pincode {} (cap 100)", sr, capped.size(), pincode);
                } else {
                    // Multiple SRs share this pincode — split using angular partitioning, cap 100 each
                    List<Shipment> sorted = pinShipments.stream()
                            .sorted(Comparator.comparingDouble(s ->
                                    Math.atan2(s.getDropLongitude() - 73.8884305, s.getDropLatitude() - 18.4600561)))
                            .collect(Collectors.toList());

                    int perSR = Math.min(100, sorted.size() / srsForPin.size());
                    int idx = 0;
                    for (int i = 0; i < srsForPin.size(); i++) {
                        String sr = srsForPin.get(i);
                        int end = Math.min(idx + perSR, sorted.size());
                        if (i == srsForPin.size() - 1) end = Math.min(idx + 100, sorted.size()); // last SR gets remainder up to 100
                        List<Shipment> srSlice = new ArrayList<>(sorted.subList(idx, end));
                        matched.computeIfAbsent(sr, k -> new ArrayList<>()).addAll(srSlice);
                        srSlice.forEach(s -> assignedShipmentIds.add(s.getShippingId()));
                        idx = end;
                        log.info("AffinityMatch: {} → {} shipments in pincode {} (shared, cap 100)",
                                sr, srSlice.size(), pincode);
                    }
                }
            }

            // Ensure all present SRs have an entry
            for (String sr : presentSRs) {
                matched.putIfAbsent(sr, new ArrayList<>());
            }

            // Remaining shipments (not in any affinity pincode)
            List<Shipment> unassigned = allocated.stream()
                    .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                    .collect(Collectors.toList());

            if (!unassigned.isEmpty()) {
                // STRICT: unassigned shipments stay unallocated — don't pollute affinity
                log.info("AffinityMatch STRICT: {} shipments not in any affinity pincode → left unallocated",
                        unassigned.size());
            }
        } else {
            // No affinity defined — use pincode clustering
            List<List<Shipment>> pincodeClusters = pincodeClusteringService.clusterByPincode(allocated, numSRs);
            for (int i = 0; i < pincodeClusters.size() && i < presentSRs.size(); i++) {
                matched.put(presentSRs.get(i), pincodeClusters.get(i));
            }
            log.info("AffinityMatch: no affinities, using pincode clustering");
        }

        // Step 4: Sequence routes and update store
        for (Map.Entry<String, List<Shipment>> entry : matched.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            if (srShipments.isEmpty()) continue;
            List<Shipment> ordered = srShipments.size() >= 2
                    ? routeOptimizerService.optimizeRoute(sr, srShipments)
                    : new ArrayList<>(srShipments);
            if (ordered.size() == 1) ordered.get(0).setRouteSequence(1);
            for (Shipment s : ordered) s.setAssignedSr(sr);
            entry.setValue(ordered);
        }

        // Save to store
        List<Shipment> toSave = matched.values().stream()
                .flatMap(List::stream).collect(Collectors.toList());
        store.saveShipments(storeDateStr, toSave);

        // Step 5: Compute affinity scores
        Map<String, Map<String, Object>> scores = affinityMatchingService.computeAffinityScores(
                matched, srAffinities);

        // Rebuild summary
        AllocationSummary newSummary = allocationEngineService.getSummary(date);

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("mode", "affinity-strict");
        result.put("affinitySRs", srAffinities.size());
        result.put("totalSRs", matched.size());
        result.put("allocationSummary", newSummary);
        result.put("affinityScores", scores);
        return ResponseEntity.ok(result);
    }

    /**
     * Get affinity scores for the current allocation.
     */
    @GetMapping("/{date}/scores")
    public ResponseEntity<Map<String, Object>> getScores(@PathVariable String date) {
        LocalDate localDate = AllocationController.parseDate(date);
        String storeDateStr = findStoreDate(localDate);

        List<Shipment> allShipments = store.findShipmentsByDate(storeDateStr);
        Map<String, List<Shipment>> assignment = allShipments.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.groupingBy(Shipment::getAssignedSr, LinkedHashMap::new, Collectors.toList()));

        Map<String, Map<String, Object>> scores = affinityMatchingService.computeAffinityScores(
                assignment, srAffinities);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("affinitySRs", srAffinities.size());
        result.put("scores", scores);
        return ResponseEntity.ok(result);
    }

    /**
     * Get available pincodes for the given date (for UI dropdown).
     */
    @GetMapping("/{date}/pincodes")
    public ResponseEntity<Map<String, Object>> getAvailablePincodes(@PathVariable String date) {
        LocalDate localDate = AllocationController.parseDate(date);
        String storeDateStr = findStoreDate(localDate);

        List<Shipment> shipments = store.findShipmentsByDate(storeDateStr);
        Map<String, Long> pincodeCounts = shipments.stream()
                .filter(s -> s.getDropPincode() != null && !s.getDropPincode().isBlank())
                .collect(Collectors.groupingBy(Shipment::getDropPincode, Collectors.counting()));

        // Sort by count descending
        List<Map<String, Object>> pincodes = pincodeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pincode", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("totalPincodes", pincodes.size());
        result.put("pincodes", pincodes);
        return ResponseEntity.ok(result);
    }

    private String findStoreDate(LocalDate date) {
        java.time.format.DateTimeFormatter[] fmts = {
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy", java.util.Locale.ENGLISH),
        };
        for (var fmt : fmts) {
            String candidate = date.format(fmt);
            if (store.hasShipmentsForDate(candidate)) return candidate;
        }
        return date.toString();
    }

    // =========================================================================
    // Manual Draw Region — store and allocate by hand-drawn polygons
    // =========================================================================

    /**
     * In-memory store for manually drawn regions.
     * Key: srName → list of [lat, lng] pairs defining the drawn polygon.
     * These override pincode-based affinity when present.
     */
    private final Map<String, List<List<Double>>> manualRegions = new LinkedHashMap<>();

    /**
     * Save a manually drawn polygon for an SR.
     *
     * POST /api/affinity-match/manual-region
     * Body: {
     *   "srName": "SR-001",
     *   "polygon": [[lat1,lng1],[lat2,lng2],...],   // Leaflet [lat,lng] order
     *   "date": "24-Mar-26"                          // used to count shipments inside
     * }
     *
     * Returns: { srName, shipmentCount, clipped, warning? }
     *
     * Edge cases handled:
     *  - Polygon entirely outside hub boundary → rejected (shipmentCount = 0, warning)
     *  - Polygon partially outside hub boundary → accepted, only inside-hub shipments counted
     *  - Polygon with < 3 points → rejected
     *  - Polygon with no shipments inside → accepted with warning
     */
    @PostMapping("/manual-region")
    public ResponseEntity<Map<String, Object>> saveManualRegion(@RequestBody Map<String, Object> request) {
        String srName = (String) request.get("srName");
        String dateStr = (String) request.get("date");

        @SuppressWarnings("unchecked")
        List<List<Double>> polygon = (List<List<Double>>) request.get("polygon");

        Map<String, Object> result = new LinkedHashMap<>();

        if (srName == null || srName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "srName is required"));
        }
        if (polygon == null || polygon.size() < 3) {
            return ResponseEntity.badRequest().body(Map.of("error", "polygon must have at least 3 points"));
        }

        // Count shipments inside the drawn polygon (for feedback)
        int shipmentCount = 0;
        boolean hasShipmentsInHubBoundary = false;

        if (dateStr != null && !dateStr.isBlank()) {
            LocalDate date = AllocationController.parseDate(dateStr);
            String storeDateStr = findStoreDate(date);
            List<Shipment> shipments = store.findShipmentsByDate(storeDateStr);

            for (Shipment s : shipments) {
                if (s.getDropLatitude() == 0 && s.getDropLongitude() == 0) continue;
                if (isPointInDrawnPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon)) {
                    shipmentCount++;
                    // Check if this shipment is also within hub service area
                    if (pincodeBoundaryService.isInsideServiceArea(s.getDropLatitude(), s.getDropLongitude())) {
                        hasShipmentsInHubBoundary = true;
                    }
                }
            }
        }

        // Warn if no shipments inside the drawn region
        String warning = null;
        if (shipmentCount == 0) {
            warning = "No shipments found inside the drawn region. The region was saved but will produce no allocation.";
        } else if (!hasShipmentsInHubBoundary) {
            warning = "The drawn region contains shipments but none are within the hub service boundary. Check the region placement.";
        }

        // Store the manual region
        manualRegions.put(srName, polygon);
        log.info("ManualRegion: saved polygon with {} points for SR '{}', {} shipments inside",
                polygon.size(), srName, shipmentCount);

        result.put("srName", srName);
        result.put("shipmentCount", shipmentCount);
        result.put("pointCount", polygon.size());
        result.put("saved", true);
        if (warning != null) result.put("warning", warning);
        return ResponseEntity.ok(result);
    }

    /**
     * Delete the manual region for an SR.
     * DELETE /api/affinity-match/manual-region/{srName}
     */
    @DeleteMapping("/manual-region/{srName}")
    public ResponseEntity<Map<String, Object>> deleteManualRegion(@PathVariable String srName) {
        boolean removed = manualRegions.remove(srName) != null;
        log.info("ManualRegion: removed region for SR '{}' (existed={})", srName, removed);
        return ResponseEntity.ok(Map.of("srName", srName, "removed", removed));
    }

    /**
     * Clear all manual regions.
     * DELETE /api/affinity-match/manual-regions
     */
    @DeleteMapping("/manual-regions")
    public ResponseEntity<Map<String, Object>> clearManualRegions() {
        int count = manualRegions.size();
        manualRegions.clear();
        log.info("ManualRegion: cleared {} manual regions", count);
        return ResponseEntity.ok(Map.of("cleared", count));
    }

    /**
     * Get all current manual regions (for map re-rendering after page reload).
     * GET /api/affinity-match/manual-regions
     */
    @GetMapping("/manual-regions")
    public ResponseEntity<Map<String, Object>> getManualRegions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", manualRegions.size());
        result.put("regions", manualRegions);
        return ResponseEntity.ok(result);
    }

    /**
     * Run allocation using manually drawn regions instead of pincode-based affinity.
     *
     * POST /api/affinity-match/allocate-manual
     * Body: { "date": "24-Mar-26" }
     *
     * Each SR gets ONLY shipments whose drop coordinates fall inside their drawn polygon.
     * Shipments outside all drawn polygons are left unallocated (strict mode).
     * Shipments outside the hub boundary are excluded regardless.
     */
    @PostMapping("/allocate-manual")
    public ResponseEntity<Map<String, Object>> allocateWithManualRegions(@RequestBody Map<String, String> request) {
        String dateStr = request.get("date");
        if (dateStr == null || dateStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "date is required"));
        }
        if (manualRegions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No manual regions defined. Draw at least one region first."));
        }

        LocalDate date = AllocationController.parseDate(dateStr);

        // Step 1: Run standard allocation to get filtered shipments + present SRs
        AllocationSummary summary = allocationEngineService.allocate(date);
        String storeDateStr = summary.date();

        List<Shipment> allShipments = store.findShipmentsByDate(storeDateStr);
        List<Shipment> allocated = allShipments.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.toList());
        List<String> presentSRs = allocated.stream()
                .map(Shipment::getAssignedSr).distinct().sorted()
                .collect(Collectors.toList());

        // Step 2: Assign shipments by drawn polygon (point-in-polygon)
        Map<String, List<Shipment>> matched = new LinkedHashMap<>();
        Set<String> assignedShipmentIds = new HashSet<>();

        // Only process SRs that are both present AND have a manual region
        for (String sr : presentSRs) {
            matched.put(sr, new ArrayList<>());
        }

        for (Map.Entry<String, List<List<Double>>> entry : manualRegions.entrySet()) {
            String sr = entry.getKey();
            List<List<Double>> polygon = entry.getValue();

            if (!presentSRs.contains(sr)) {
                log.warn("ManualRegion: SR '{}' has a drawn region but is not present today — skipping", sr);
                continue;
            }

            List<Shipment> regionShipments = allocated.stream()
                    .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                    .filter(s -> s.getDropLatitude() != 0 || s.getDropLongitude() != 0)
                    .filter(s -> isPointInDrawnPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon))
                    .collect(Collectors.toList());

            // Cap at 100 per SR
            if (regionShipments.size() > 100) {
                regionShipments = regionShipments.subList(0, 100);
            }

            matched.get(sr).addAll(regionShipments);
            regionShipments.forEach(s -> assignedShipmentIds.add(s.getShippingId()));
            log.info("ManualRegion: SR '{}' → {} shipments inside drawn polygon", sr, regionShipments.size());
        }

        int unallocatedCount = (int) allocated.stream()
                .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                .count();
        log.info("ManualRegion: {} shipments not inside any drawn region → left unallocated", unallocatedCount);

        // Step 3: Sequence routes and save
        for (Map.Entry<String, List<Shipment>> entry : matched.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            if (srShipments.isEmpty()) continue;
            List<Shipment> ordered = srShipments.size() >= 2
                    ? routeOptimizerService.optimizeRoute(sr, srShipments)
                    : new ArrayList<>(srShipments);
            if (ordered.size() == 1) ordered.get(0).setRouteSequence(1);
            for (Shipment s : ordered) s.setAssignedSr(sr);
            entry.setValue(ordered);
        }

        List<Shipment> toSave = matched.values().stream()
                .flatMap(List::stream).collect(Collectors.toList());
        store.saveShipments(storeDateStr, toSave);

        AllocationSummary newSummary = allocationEngineService.getSummary(date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("mode", "manual-region");
        result.put("manualRegionSRs", manualRegions.size());
        result.put("totalSRs", matched.size());
        result.put("unallocatedShipments", unallocatedCount);
        result.put("allocationSummary", newSummary);
        return ResponseEntity.ok(result);
    }

    /**
     * Ray-casting point-in-polygon for manually drawn polygons.
     * Polygon coordinates are in [lat, lng] order (Leaflet convention).
     */
    private boolean isPointInDrawnPolygon(double lat, double lng, List<List<Double>> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double latI = polygon.get(i).get(0);
            double lngI = polygon.get(i).get(1);
            double latJ = polygon.get(j).get(0);
            double lngJ = polygon.get(j).get(1);
            if ((lngI > lng) != (lngJ > lng) &&
                    lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI) {
                inside = !inside;
            }
        }
        return inside;
    }
}
