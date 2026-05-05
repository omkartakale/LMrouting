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

    // In-memory custom region storage: srName → polygon ring as [lat,lng] pairs
    private final Map<String, double[][]> customRegions = new LinkedHashMap<>();

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
    // Custom Region Drawing endpoints
    // =========================================================================

    /**
     * Store custom polygon regions for each SR.
     *
     * POST /api/affinity-match/set-custom-regions
     * Body: { "regions": { "SR-001": [[lat,lng], [lat,lng], ...], "SR-002": [...] } }
     */
    @PostMapping("/set-custom-regions")
    public ResponseEntity<Map<String, Object>> setCustomRegions(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, List<List<Double>>> regions =
                (Map<String, List<List<Double>>>) request.get("regions");

        if (regions == null || regions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "regions map is required"));
        }

        customRegions.clear();
        for (Map.Entry<String, List<List<Double>>> entry : regions.entrySet()) {
            String srName = entry.getKey();
            List<List<Double>> polygon = entry.getValue();
            if (polygon != null && polygon.size() >= 3) {
                // Convert to double[][] for storage
                double[][] ring = polygon.stream()
                        .map(pt -> new double[]{pt.get(0), pt.get(1)})
                        .toArray(double[][]::new);
                customRegions.put(srName, ring);
            }
        }

        log.info("AffinityMatch: stored custom regions for {} SRs", customRegions.size());
        return ResponseEntity.ok(Map.of("success", true, "srCount", customRegions.size()));
    }

    /**
     * Run allocation using custom drawn polygon regions.
     * Each SR gets ONLY shipments whose coordinates fall inside their drawn polygon.
     *
     * POST /api/affinity-match/allocate-custom
     * Body: { "date": "24-Mar-26" }
     */
    @PostMapping("/allocate-custom")
    public ResponseEntity<Map<String, Object>> allocateWithCustomRegions(
            @RequestBody Map<String, String> request) {

        String dateStr = request.get("date");
        if (dateStr == null || dateStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "date is required"));
        }
        if (customRegions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No custom regions defined. Call /set-custom-regions first."));
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

        // Step 2: Assign shipments using point-in-polygon against custom regions
        Map<String, List<Shipment>> matched = new LinkedHashMap<>();
        Set<String> assignedIds = new HashSet<>();

        for (String sr : presentSRs) {
            matched.put(sr, new ArrayList<>());
        }

        for (Map.Entry<String, double[][]> entry : customRegions.entrySet()) {
            String sr = entry.getKey();
            double[][] polygon = entry.getValue();

            if (!matched.containsKey(sr)) continue; // SR not present today

            List<Shipment> inRegion = allocated.stream()
                    .filter(s -> !assignedIds.contains(s.getShippingId()))
                    .filter(s -> pointInPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon))
                    .collect(Collectors.toList());

            // Enforce 100-shipment cap — sort by payout descending to keep highest-value deliveries
            List<Shipment> capped = inRegion.size() > 100
                    ? inRegion.stream()
                        .sorted(Comparator.comparingDouble(Shipment::getExpectedPayout).reversed())
                        .limit(100)
                        .collect(Collectors.toList())
                    : inRegion;

            matched.get(sr).addAll(capped);
            capped.forEach(s -> assignedIds.add(s.getShippingId()));
            log.info("AffinityMatch custom: {} → {} shipments in drawn region ({}  in polygon, capped at 100)",
                    sr, capped.size(), inRegion.size());
        }

        // Unassigned shipments stay unallocated (strict custom region mode)
        long unassigned = allocated.stream()
                .filter(s -> !assignedIds.contains(s.getShippingId()))
                .count();
        if (unassigned > 0) {
            log.info("AffinityMatch custom: {} shipments outside all custom regions → unallocated", unassigned);
        }

        // Step 3: Sequence routes
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

        // Save
        List<Shipment> toSave = matched.values().stream()
                .flatMap(List::stream).collect(Collectors.toList());
        store.saveShipments(storeDateStr, toSave);

        // Compute affinity scores (using custom region membership)
        Map<String, Map<String, Object>> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : matched.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            double[][] polygon = customRegions.get(sr);
            int inRegion = polygon == null ? 0 : (int) srShipments.stream()
                    .filter(s -> pointInPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon))
                    .count();
            int total = srShipments.size();
            double pct = total > 0 ? Math.round(inRegion * 1000.0 / total) / 10.0 : 0;
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("affinityShipments", inRegion);
            sc.put("totalShipments", total);
            sc.put("affinityPct", pct);
            scores.put(sr, sc);
        }

        AllocationSummary newSummary = allocationEngineService.getSummary(date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("mode", "affinity-custom");
        result.put("customRegionSRs", customRegions.size());
        result.put("totalSRs", matched.size());
        result.put("unallocatedShipments", unassigned);
        result.put("allocationSummary", newSummary);
        result.put("affinityScores", scores);
        return ResponseEntity.ok(result);
    }

    /**
     * Ray-casting point-in-polygon.
     * polygon is double[][] where each row is [lat, lng].
     */
    private boolean pointInPolygon(double lat, double lng, double[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon[i][0], xi = polygon[i][1];
            double yj = polygon[j][0], xj = polygon[j][1];
            if ((yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
