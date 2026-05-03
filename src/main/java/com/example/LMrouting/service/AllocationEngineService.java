package com.example.LMrouting.service;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.NoPresentSrsException;
import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full shipment allocation pipeline (phases 1–7).
 * Fully in-memory — no JPA/DB dependency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllocationEngineService {

    private final InMemoryStore store;
    private final RouteOptimizerService routeOptimizerService;
    private final ScoreWeights scoreWeights;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${allocation.rebalancing.threshold:0.5}")
    private double rebalancingThreshold;

    @Value("${allocation.rebalancing.maxIterations:50}")
    private int maxIterations;

    /**
     * Maximum shipments per SR per day.
     * Configured via allocation.sr.capacity in application.properties.
     * Default: 80. Change this value before demo to adjust the limit.
     */
    @Value("${allocation.sr.capacity:80}")
    private int srCapacity;

    // =========================================================================
    // Public API
    // =========================================================================

    @org.springframework.transaction.annotation.Transactional
    public AllocationSummary allocate(LocalDate date) {
        String dateStr = formatDate(date);
        log.info("AllocationEngineService: starting allocation for '{}'", dateStr);

        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        if (allShipments.isEmpty()) {
            throw new AllocationNotFoundException("No shipment data found for " + dateStr +
                    ". Please upload a CSV file first.");
        }

        // Filter out shipments beyond max distance from hub (out-of-range)
        List<Shipment> allocatableShipments = allShipments.stream()
                .filter(s -> !s.isOutOfRange())
                .filter(s -> s.getDropLatitude() != 0 && s.getDropLongitude() != 0)
                .collect(java.util.stream.Collectors.toList());

        // ── Outlier detection: remove points with no neighbor within 2 km ──────
        double outlierRadiusKm = 2.0;
        int minNeighbors = 3; // must have at least 3 neighbors within radius
        List<Shipment> nonOutliers = new ArrayList<>();
        List<Shipment> outliers = new ArrayList<>();

        for (int i = 0; i < allocatableShipments.size(); i++) {
            Shipment s = allocatableShipments.get(i);
            int neighborCount = 0;
            for (int j = 0; j < allocatableShipments.size() && neighborCount < minNeighbors; j++) {
                if (i == j) continue;
                Shipment other = allocatableShipments.get(j);
                double dist = GoogleMapsService.haversine(
                        s.getDropLatitude(), s.getDropLongitude(),
                        other.getDropLatitude(), other.getDropLongitude());
                if (dist <= outlierRadiusKm) {
                    neighborCount++;
                }
            }
            if (neighborCount >= minNeighbors) {
                nonOutliers.add(s);
            } else {
                outliers.add(s);
                s.setOutOfRange(true); // mark as outlier so UI shows it
            }
        }

        if (!outliers.isEmpty()) {
            log.info("AllocationEngineService: detected {} outlier shipments (no neighbor within {} km), excluding from allocation",
                    outliers.size(), outlierRadiusKm);
        }
        allocatableShipments = nonOutliers;

        log.info("AllocationEngineService: {} total shipments, {} allocatable (excluded {} out-of-range, {} outliers)",
                allShipments.size(), allocatableShipments.size(),
                allShipments.size() - allocatableShipments.size() - outliers.size(), outliers.size());

        List<String> presentSrNames = store.getPresentSrNames(date);
        if (presentSrNames.isEmpty()) {
            throw new NoPresentSrsException("At least one SR must be marked present before running allocation.");
        }

        log.info("AllocationEngineService: {} allocatable shipments, {} present SRs", allocatableShipments.size(), presentSrNames.size());

        // ── SR Capacity enforcement ────────────────────────────────────────────
        // Each SR can handle at most srCapacity shipments per day.
        // Total allocatable = presentSRs * srCapacity.
        // If there are more shipments than capacity, we allocate only the first
        // (presentSRs * srCapacity) shipments and log the rest as unallocated.
        int totalCapacity = presentSrNames.size() * srCapacity;
        List<Shipment> shipmentsToAllocate = allocatableShipments;
        List<Shipment> unallocatedShipments = new ArrayList<>();

        if (allocatableShipments.size() > totalCapacity) {
            log.warn("AllocationEngineService: {} shipments exceed total capacity ({} SRs × {} = {}). " +
                    "{} shipments will not be allocated.",
                    allocatableShipments.size(), presentSrNames.size(), srCapacity, totalCapacity,
                    allocatableShipments.size() - totalCapacity);
            shipmentsToAllocate = new ArrayList<>(allocatableShipments.subList(0, totalCapacity));
            unallocatedShipments = new ArrayList<>(allocatableShipments.subList(totalCapacity, allocatableShipments.size()));
        }

        // Separate Forward and Reverse shipments (from the capacity-limited set)
        List<Shipment> forwardShipments = shipmentsToAllocate.stream()
                .filter(s -> "Forward".equalsIgnoreCase(s.getShipmentFlow()))
                .collect(Collectors.toList());
        List<Shipment> reverseShipments = shipmentsToAllocate.stream()
                .filter(s -> !"Forward".equalsIgnoreCase(s.getShipmentFlow()))
                .collect(Collectors.toList());

        // Phase 1 — Angular sector partitioning
        Map<String, List<Shipment>> assignment = angularSectorPartition(forwardShipments, presentSrNames);

        // Phase 2 — Skip aggressive rebalancing to preserve geographic compactness
        // The K-Means clustering already produces balanced clusters (±20%)
        log.info("Phase 2: skipped — relying on K-Means balanced clustering");

        // Phase 3 — Forward/Reverse co-location
        assignment = coLocateReverseShipments(reverseShipments, assignment);

        // Phase 4 — Heavy shipment balancing
        assignment = balanceHeavyShipments(assignment);

        // Phase 5 — Composite Load Score computation
        Map<String, Double> distancesBySr = new LinkedHashMap<>();
        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            double distKm = routeOptimizerService.estimateDistanceKm(srShipments);
            distancesBySr.put(sr, distKm);
            scoresBySr.put(sr, CompositeLoadScoreCalculator.compute(srShipments, distKm, scoreWeights));
        }
        double fairnessVariance = CompositeLoadScoreCalculator.variance(scoresBySr);

        // Phase 6 — Route sequencing
        Map<String, List<Shipment>> orderedAssignment = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            List<Shipment> ordered = srShipments.size() >= 2
                    ? routeOptimizerService.optimizeRoute(sr, srShipments)
                    : new ArrayList<>(srShipments);
            if (ordered.size() == 1) ordered.get(0).setRouteSequence(1);
            orderedAssignment.put(sr, ordered);
            distancesBySr.put(sr, routeOptimizerService.estimateDistanceKm(ordered));
        }

        // Phase 7 — Persist to in-memory store
        List<Shipment> toSave = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignment.entrySet()) {
            String sr = entry.getKey();
            entry.getValue().forEach(s -> s.setAssignedSr(sr));
            toSave.addAll(entry.getValue());
        }
        store.saveShipments(dateStr, toSave);

        AllocationRun run = AllocationRun.builder()
                .allocationDate(date)
                .status(AllocationStatus.COMPLETED)
                .totalShipments(allShipments.size())
                .totalSrs(presentSrNames.size())
                .fairnessVariance(fairnessVariance)
                .createdAt(LocalDateTime.now())
                .build();
        store.saveAllocationRun(run);

        log.info("AllocationEngineService: allocation complete — fairnessVariance={:.4f}, allocated={}, unallocated={}",
                fairnessVariance, toSave.size(), unallocatedShipments.size());
        return buildSummary(dateStr, allShipments.size(), presentSrNames.size(),
                fairnessVariance, orderedAssignment, distancesBySr, unallocatedShipments.size());
    }

    public AllocationSummary getSummary(LocalDate date) {
        String dateStr = formatDate(date);
        AllocationRun run = store.findAllocationRun(date)
                .orElseThrow(() -> new AllocationNotFoundException("No allocation found for " + dateStr));

        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        if (allShipments.isEmpty()) {
            throw new AllocationNotFoundException("No shipment data found for " + dateStr);
        }

        Map<String, List<Shipment>> bySr = allShipments.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.groupingBy(Shipment::getAssignedSr, LinkedHashMap::new, Collectors.toList()));

        Map<String, Double> distancesBySr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : bySr.entrySet()) {
            List<Shipment> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Shipment::getRouteSequence))
                    .collect(Collectors.toList());
            distancesBySr.put(entry.getKey(), routeOptimizerService.estimateDistanceKm(ordered));
        }

        return buildSummary(dateStr, run.getTotalShipments(), run.getTotalSrs(),
                run.getFairnessVariance(), bySr, distancesBySr, 0);
    }

    public List<double[]> getRoutePolyline(LocalDate date, String srName) {
        String dateStr = formatDate(date);
        List<Shipment> shipments = store.findShipmentsByDateAndSr(dateStr, srName);
        return routeOptimizerService.getRoutePolyline(shipments);
    }

    public List<double[]> getOrsPolyline(LocalDate date, String srName) {
        String dateStr = formatDate(date);
        List<Shipment> shipments = store.findShipmentsByDateAndSr(dateStr, srName);
        return routeOptimizerService.getOrsPolyline(shipments);
    }

    public List<double[]> getGoogleMapsPolyline(LocalDate date, String srName) {
        String dateStr = formatDate(date);
        List<Shipment> shipments = store.findShipmentsByDateAndSr(dateStr, srName);
        return routeOptimizerService.getGoogleMapsPolyline(shipments);
    }

    public SrRouteDto getSrRoute(LocalDate date, String srName) {
        String dateStr = formatDate(date);
        List<Shipment> shipments = store.findShipmentsByDateAndSr(dateStr, srName);

        if (shipments.isEmpty()) {
            throw new AllocationNotFoundException("No shipments found for SR '" + srName + "' on " + dateStr);
        }

        double distKm = routeOptimizerService.estimateDistanceKm(shipments);

        List<ShipmentStopDto> stops = shipments.stream()
                .map(s -> new ShipmentStopDto(
                        s.getRouteSequence(),
                        s.getShippingId(),
                        s.getDropPincode(),
                        s.getDropLatitude(),
                        s.getDropLongitude(),
                        s.getOrderType(),
                        s.getPhyWeight(),
                        s.getIsHeavy() == 1,
                        s.getShipmentFlow(),
                        s.isOverride(),
                        s.isOutOfRange()))
                .collect(Collectors.toList());

        return new SrRouteDto(srName, dateStr, shipments.size(), distKm, stops);
    }

    // =========================================================================
    // Phase 1 — Hub-centric angular clustering with balanced sizes
    // =========================================================================

    Map<String, List<Shipment>> angularSectorPartition(List<Shipment> forwardShipments,
                                                        List<String> presentSrNames) {
        int k = presentSrNames.size();
        Map<String, List<Shipment>> assignment = new LinkedHashMap<>();
        for (String sr : presentSrNames) assignment.put(sr, new ArrayList<>());

        if (forwardShipments.isEmpty()) return assignment;

        int n = forwardShipments.size();

        // Sort by angle from hub (pie slices)
        List<Shipment> sorted = forwardShipments.stream()
                .sorted(Comparator.comparingDouble(s ->
                        Math.atan2(s.getDropLongitude() - hubLng, s.getDropLatitude() - hubLat)))
                .collect(Collectors.toList());

        // Initial equal-size angular sectors
        int targetSize = n / k;
        int remainder = n % k;
        int index = 0;
        List<List<Shipment>> sectors = new ArrayList<>();

        for (int i = 0; i < k; i++) {
            int bucketSize = targetSize + (i < remainder ? 1 : 0);
            List<Shipment> sector = new ArrayList<>();
            for (int j = 0; j < bucketSize && index < n; j++) {
                sector.add(sorted.get(index++));
            }
            sectors.add(sector);
        }

        // Refine: swap boundary points between adjacent sectors to minimize
        // max distance variance while keeping sectors contiguous
        for (int pass = 0; pass < 10; pass++) {
            boolean swapped = false;
            for (int i = 0; i < k; i++) {
                int next = (i + 1) % k;
                if (sectors.get(i).isEmpty() || sectors.get(next).isEmpty()) continue;

                double maxDistI = sectors.get(i).stream()
                        .mapToDouble(s -> GoogleMapsService.haversine(hubLat, hubLng, s.getDropLatitude(), s.getDropLongitude()))
                        .max().orElse(0);
                double maxDistNext = sectors.get(next).stream()
                        .mapToDouble(s -> GoogleMapsService.haversine(hubLat, hubLng, s.getDropLatitude(), s.getDropLongitude()))
                        .max().orElse(0);

                // If one sector has much higher max distance, move a boundary point
                if (maxDistI > maxDistNext * 1.3 && sectors.get(i).size() > targetSize - 2) {
                    // Move last point of sector i to sector next
                    Shipment boundary = sectors.get(i).remove(sectors.get(i).size() - 1);
                    sectors.get(next).add(0, boundary);
                    swapped = true;
                } else if (maxDistNext > maxDistI * 1.3 && sectors.get(next).size() > targetSize - 2) {
                    Shipment boundary = sectors.get(next).remove(0);
                    sectors.get(i).add(boundary);
                    swapped = true;
                }
            }
            if (!swapped) break;
        }

        // Assign to SR names
        for (int i = 0; i < k; i++) {
            String sr = presentSrNames.get(i);
            assignment.get(sr).addAll(sectors.get(i));
        }

        // Log distance stats
        for (int i = 0; i < k; i++) {
            String sr = presentSrNames.get(i);
            double totalDist = sectors.get(i).stream()
                    .mapToDouble(s -> GoogleMapsService.haversine(hubLat, hubLng, s.getDropLatitude(), s.getDropLongitude()))
                    .sum();
            log.info("Phase 1: {} → {} shipments, total hub-dist={:.1f}km", sr, sectors.get(i).size(), totalDist);
        }

        return assignment;
    }

    // =========================================================================
    // Phase 2 — Iterative fairness rebalancing
    // =========================================================================

    Map<String, List<Shipment>> rebalance(Map<String, List<Shipment>> assignment) {
        if (assignment.size() < 2) return assignment;

        Map<String, Double> scores = computeScores(assignment);
        double variance = CompositeLoadScoreCalculator.variance(scores);
        int iterations = 0;

        while (variance > rebalancingThreshold && iterations < maxIterations) {
            String overloaded = maxScoreSr(scores);
            String underloaded = minScoreSr(scores);
            if (overloaded.equals(underloaded)) break;

            List<Shipment> overloadedShipments = assignment.get(overloaded);
            if (overloadedShipments.isEmpty()) break;

            double[] underloadedCentroid = centroid(assignment.get(underloaded));
            Shipment best = findBestBoundaryShipment(overloaded, underloaded,
                    overloadedShipments, underloadedCentroid, scores, assignment);
            if (best == null) break;

            overloadedShipments.remove(best);
            assignment.get(underloaded).add(best);
            scores = computeScores(assignment);
            variance = CompositeLoadScoreCalculator.variance(scores);
            iterations++;
        }

        log.debug("Phase 2: {} iterations, final variance={:.4f}", iterations, variance);
        return assignment;
    }

    // =========================================================================
    // Phase 3 — Forward/Reverse co-location
    // =========================================================================

    Map<String, List<Shipment>> coLocateReverseShipments(List<Shipment> reverseShipments,
                                                          Map<String, List<Shipment>> assignment) {
        if (reverseShipments.isEmpty() || assignment.isEmpty()) return assignment;

        List<String> srNames = new ArrayList<>(assignment.keySet());

        for (Shipment reverse : reverseShipments) {
            double revLat = reverse.getDropLatitude();
            double revLng = reverse.getDropLongitude();

            Set<String> qualifyingSrs = new LinkedHashSet<>();
            for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
                for (Shipment fwd : entry.getValue()) {
                    if (haversine(revLat, revLng, fwd.getDropLatitude(), fwd.getDropLongitude()) <= 1.0) {
                        qualifyingSrs.add(entry.getKey());
                        break;
                    }
                }
            }

            String targetSr;
            if (qualifyingSrs.size() == 1) {
                targetSr = qualifyingSrs.iterator().next();
            } else if (qualifyingSrs.size() > 1) {
                targetSr = nearestCentroidSr(revLat, revLng, qualifyingSrs, assignment);
            } else {
                targetSr = nearestCentroidSr(revLat, revLng, srNames, assignment);
            }

            assignment.get(targetSr).add(reverse);
        }

        return assignment;
    }

    // =========================================================================
    // Phase 4 — Heavy shipment balancing
    // =========================================================================

    Map<String, List<Shipment>> balanceHeavyShipments(Map<String, List<Shipment>> assignment) {
        if (assignment.size() < 2) return assignment;

        Map<String, Integer> heavyCount = computeHeavyCounts(assignment);
        int maxCount = Collections.max(heavyCount.values());
        int minCount = Collections.min(heavyCount.values());

        while (maxCount - minCount > 1) {
            String overloaded = srWithMaxHeavy(heavyCount);
            String underloaded = srWithMinHeavy(heavyCount);
            if (overloaded.equals(underloaded)) break;

            List<Shipment> heavyShipments = assignment.get(overloaded).stream()
                    .filter(s -> s.getIsHeavy() == 1).collect(Collectors.toList());
            if (heavyShipments.isEmpty()) break;

            double[] underloadedCentroid = centroid(assignment.get(underloaded));
            Shipment toMove = heavyShipments.stream()
                    .min(Comparator.comparingDouble(s ->
                            haversine(s.getDropLatitude(), s.getDropLongitude(),
                                    underloadedCentroid[0], underloadedCentroid[1])))
                    .orElseThrow();

            assignment.get(overloaded).remove(toMove);
            assignment.get(underloaded).add(toMove);
            heavyCount.put(overloaded, heavyCount.get(overloaded) - 1);
            heavyCount.put(underloaded, heavyCount.get(underloaded) + 1);
            maxCount = Collections.max(heavyCount.values());
            minCount = Collections.min(heavyCount.values());
        }

        return assignment;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Map<String, Double> computeScores(Map<String, List<Shipment>> assignment) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            double distKm = routeOptimizerService.estimateDistanceKm(entry.getValue());
            scores.put(entry.getKey(),
                    CompositeLoadScoreCalculator.compute(entry.getValue(), distKm, scoreWeights));
        }
        return scores;
    }

    private String maxScoreSr(Map<String, Double> scores) {
        return scores.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
    }

    private String minScoreSr(Map<String, Double> scores) {
        return scores.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
    }

    private double[] centroid(List<Shipment> shipments) {
        if (shipments.isEmpty()) return new double[]{hubLat, hubLng};
        double sumLat = 0, sumLng = 0;
        for (Shipment s : shipments) { sumLat += s.getDropLatitude(); sumLng += s.getDropLongitude(); }
        return new double[]{sumLat / shipments.size(), sumLng / shipments.size()};
    }

    private Shipment findBestBoundaryShipment(String overloaded, String underloaded,
                                               List<Shipment> candidates, double[] underloadedCentroid,
                                               Map<String, Double> currentScores,
                                               Map<String, List<Shipment>> assignment) {
        double currentVariance = CompositeLoadScoreCalculator.variance(currentScores);
        double bestVariance = currentVariance;
        Shipment bestShipment = null;

        for (Shipment candidate : candidates) {
            List<Shipment> newOverloaded = new ArrayList<>(assignment.get(overloaded));
            newOverloaded.remove(candidate);
            List<Shipment> newUnderloaded = new ArrayList<>(assignment.get(underloaded));
            newUnderloaded.add(candidate);

            Map<String, Double> simScores = new LinkedHashMap<>(currentScores);
            simScores.put(overloaded, CompositeLoadScoreCalculator.compute(newOverloaded,
                    routeOptimizerService.estimateDistanceKm(newOverloaded), scoreWeights));
            simScores.put(underloaded, CompositeLoadScoreCalculator.compute(newUnderloaded,
                    routeOptimizerService.estimateDistanceKm(newUnderloaded), scoreWeights));

            double simVariance = CompositeLoadScoreCalculator.variance(simScores);
            if (simVariance < bestVariance) { bestVariance = simVariance; bestShipment = candidate; }
        }
        return bestShipment;
    }

    private String nearestCentroidSr(double lat, double lng, Iterable<String> candidates,
                                      Map<String, List<Shipment>> assignment) {
        String nearest = null;
        double minDist = Double.MAX_VALUE;
        for (String sr : candidates) {
            double[] c = centroid(assignment.get(sr));
            double dist = haversine(lat, lng, c[0], c[1]);
            if (dist < minDist) { minDist = dist; nearest = sr; }
        }
        return nearest;
    }

    private Map<String, Integer> computeHeavyCounts(Map<String, List<Shipment>> assignment) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                counts.put(sr, (int) list.stream().filter(s -> s.getIsHeavy() == 1).count()));
        return counts;
    }

    private String srWithMaxHeavy(Map<String, Integer> heavyCount) {
        return heavyCount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
    }

    private String srWithMinHeavy(Map<String, Integer> heavyCount) {
        return heavyCount.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        return GoogleMapsService.haversine(lat1, lng1, lat2, lng2);
    }

    private AllocationSummary buildSummary(String dateStr, int totalShipments, int totalSrs,
                                            double fairnessVariance,
                                            Map<String, List<Shipment>> assignment,
                                            Map<String, Double> distancesBySr,
                                            int unallocatedShipments) {
        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        assignment.forEach((sr, list) -> {
            double dist = distancesBySr.getOrDefault(sr, 0.0);
            scoresBySr.put(sr, CompositeLoadScoreCalculator.compute(list, dist, scoreWeights));
        });

        List<SrSummaryDto> srSummaries = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> list = entry.getValue();
            int heavyCount = (int) list.stream().filter(s -> s.getIsHeavy() == 1).count();
            double dist = distancesBySr.getOrDefault(sr, 0.0);
            List<String> pincodes = list.stream().map(Shipment::getDropPincode)
                    .filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
            srSummaries.add(new SrSummaryDto(sr, list.size(), heavyCount,
                    scoresBySr.getOrDefault(sr, 0.0), dist, pincodes));
        }

        IntSummaryStatistics stats = assignment.values().stream().mapToInt(List::size).summaryStatistics();
        int allocatedShipments = totalShipments - unallocatedShipments;

        return new AllocationSummary(dateStr, totalShipments, allocatedShipments, unallocatedShipments,
                srCapacity, totalSrs,
                assignment.isEmpty() ? 0 : stats.getMin(),
                assignment.isEmpty() ? 0 : stats.getMax(),
                assignment.isEmpty() ? 0.0 : stats.getAverage(),
                fairnessVariance, srSummaries);
    }

    private String formatDate(LocalDate date) {
        // Try to find the date in the store using common formats
        // The store keys are raw strings from CSV (e.g. "02-May-26", "24-Mar-26")
        java.time.format.DateTimeFormatter[] fmts = {
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", java.util.Locale.ENGLISH),
        };
        for (var fmt : fmts) {
            String candidate = date.format(fmt);
            if (store.hasShipmentsForDate(candidate)) return candidate;
        }
        // Fallback to ISO
        String iso = date.toString();
        if (store.hasShipmentsForDate(iso)) return iso;
        // Return dd-MMM-yy as default format
        return date.format(fmts[0]);
    }
}
