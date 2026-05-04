package com.example.LMrouting.controller;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AffinityMatchingService;
import com.example.LMrouting.service.AllocationEngineService;
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

        // Step 1: Run standard allocation
        AllocationSummary summary = allocationEngineService.allocate(date);

        // Step 2: Get the current assignment from store
        String storeDateStr = summary.date();
        List<Shipment> allShipments = store.findShipmentsByDate(storeDateStr);
        Map<String, List<Shipment>> currentAssignment = allShipments.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.groupingBy(Shipment::getAssignedSr, LinkedHashMap::new, Collectors.toList()));

        // Step 3: Apply affinity matching
        Map<String, List<Shipment>> matched;
        if (srAffinities.isEmpty()) {
            matched = currentAssignment;
            log.info("AffinityMatch: no affinities defined, using standard assignment");
        } else {
            matched = affinityMatchingService.matchRoutesToSRs(currentAssignment, srAffinities);

            // Step 4: Update shipments with new SR assignments
            for (Map.Entry<String, List<Shipment>> entry : matched.entrySet()) {
                String sr = entry.getKey();
                for (Shipment s : entry.getValue()) {
                    s.setAssignedSr(sr);
                }
            }
            // Save back to store
            List<Shipment> toSave = matched.values().stream()
                    .flatMap(List::stream).collect(Collectors.toList());
            store.saveShipments(storeDateStr, toSave);
        }

        // Step 5: Compute affinity scores
        Map<String, Map<String, Object>> scores = affinityMatchingService.computeAffinityScores(
                matched, srAffinities);

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", storeDateStr);
        result.put("mode", srAffinities.isEmpty() ? "standard" : "affinity");
        result.put("affinitySRs", srAffinities.size());
        result.put("totalSRs", matched.size());
        result.put("allocationSummary", summary);
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
}
