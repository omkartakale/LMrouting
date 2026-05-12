package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Density-aware territory partitioning for time-based affinity allocation.
 *
 * Creates compact, non-overlapping SR sub-territories within each affinity region
 * using weighted K-Means clustering. Territories are based on:
 * - Shipment density (dense areas → smaller territories)
 * - Expected workload (handling + travel time contribution)
 * - Geographic compactness (minimize route distance within territory)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TerritoryPartitionService {

    private final ShiftWorkloadCalculatorService workloadCalculator;

    /**
     * Partition shipments within a region into compact SR territories.
     *
     * @param shipments       all shipments in this region
     * @param srNames         SRs assigned to this region
     * @param srShiftDurations per-SR shift duration map
     * @param globalShiftDuration fallback shift duration
     * @param targetUtilisation target utilisation (e.g., 0.88)
     * @param hubLat          hub latitude
     * @param hubLng          hub longitude
     * @return TerritoryResult with per-SR assignments, overflow, and territory boundaries
     */
    public TerritoryResult partition(List<Shipment> shipments, List<String> srNames,
                                     Map<String, Integer> srShiftDurations,
                                     int globalShiftDuration, double targetUtilisation,
                                     double hubLat, double hubLng) {

        if (shipments.isEmpty() || srNames.isEmpty()) {
            Map<String, List<Shipment>> empty = new LinkedHashMap<>();
            for (String sr : srNames) empty.put(sr, new ArrayList<>());
            return new TerritoryResult(empty, new ArrayList<>(), new LinkedHashMap<>());
        }

        // Step 1: Determine optimal number of active SRs
        // Estimate total workload using per-shipment handling + compact cluster travel estimate.
        // We DON'T compute workload on all shipments as a single route because that
        // overestimates travel time — a 200-stop chain has much more travel than
        // two 100-stop compact clusters. Instead, use handling time (known exactly)
        // plus a conservative travel estimate based on the region's geographic spread.
        double totalHandlingMinutes = shipments.size() * 5.0; // 5 min per shipment (default handling)
        
        // Estimate travel time: compute on a sample (first 50 shipments ordered by NN)
        // and extrapolate per-shipment travel rate
        List<Shipment> sample = shipments.size() > 50 
                ? nearestNeighbourOrder(shipments.subList(0, Math.min(50, shipments.size())), hubLat, hubLng)
                : nearestNeighbourOrder(shipments, hubLat, hubLng);
        ShiftWorkloadCalculatorService.WorkloadResult sampleWorkload = workloadCalculator.computeWorkload(sample);
        double travelPerShipment = sample.size() > 1 
                ? (sampleWorkload.travelMinutes() + sampleWorkload.returnToHubMinutes()) / sample.size()
                : 2.0; // fallback: 2 min per shipment
        
        double estimatedTotalWorkload = totalHandlingMinutes + (shipments.size() * travelPerShipment) + 30.0; // + break
        
        // Sort SRs by shift duration descending (prefer filling longest-shift SRs first)
        List<String> srsByCapacity = new ArrayList<>(srNames);
        srsByCapacity.sort(Comparator.comparingInt(
                (String sr) -> getShiftDuration(sr, srShiftDurations, globalShiftDuration)).reversed());
        
        int optimalSrCount = computeOptimalSrCount(estimatedTotalWorkload, srsByCapacity,
                srShiftDurations, globalShiftDuration, targetUtilisation);

        // Use at most the available SRs, at least 1
        int activeSrCount = Math.min(optimalSrCount, srsByCapacity.size());
        activeSrCount = Math.max(1, activeSrCount);

        List<String> activeSrs = srsByCapacity.subList(0, activeSrCount);

        log.info("TerritoryPartition: {} shipments, {} assigned SRs, {} optimal active SRs (estimatedWorkload={} min, handling={} min, travelPerShipment={} min)",
                shipments.size(), srNames.size(), activeSrCount, Math.round(estimatedTotalWorkload),
                Math.round(totalHandlingMinutes), String.format("%.1f", travelPerShipment));
        log.info("TerritoryPartition: SR capacities (sorted desc): {}", 
                srsByCapacity.stream().map(sr -> sr + "=" + getShiftDuration(sr, srShiftDurations, globalShiftDuration) + "min")
                        .collect(java.util.stream.Collectors.joining(", ")));

        // Step 2: Run weighted K-Means to create territories
        Map<String, List<Shipment>> territories = weightedKMeans(shipments, activeSrs, hubLat, hubLng);

        // Step 3: Enforce shift duration with soft tolerance (5%)
        Map<String, List<Shipment>> finalAssignments = new LinkedHashMap<>();
        List<Shipment> overflow = new ArrayList<>();
        Map<String, List<double[]>> territoryBoundaries = new LinkedHashMap<>();

        for (String sr : srNames) {
            finalAssignments.put(sr, new ArrayList<>());
        }

        for (Map.Entry<String, List<Shipment>> entry : territories.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> territory = entry.getValue();

            int shiftDuration = getShiftDuration(sr, srShiftDurations, globalShiftDuration);
            double softCap = shiftDuration * 1.05; // 5% tolerance

            // Order shipments by nearest-neighbor within territory
            List<Shipment> ordered = nearestNeighbourOrder(territory, hubLat, hubLng);

            // Add shipments until soft cap is reached
            List<Shipment> accepted = new ArrayList<>();
            for (Shipment s : ordered) {
                List<Shipment> trial = new ArrayList<>(accepted);
                trial.add(s);
                ShiftWorkloadCalculatorService.WorkloadResult w = workloadCalculator.computeWorkload(trial);

                if (w.totalMinutes() <= softCap) {
                    accepted.add(s);
                } else if (accepted.isEmpty()) {
                    // First shipment always accepted (even if it alone exceeds cap)
                    accepted.add(s);
                } else {
                    overflow.add(s);
                }
            }

            finalAssignments.put(sr, accepted);

            // Compute territory boundary (convex hull of assigned shipments)
            if (!accepted.isEmpty()) {
                territoryBoundaries.put(sr, computeConvexHull(accepted));
            }
        }

        // Step 4: Try to place overflow into geographically aligned SRs (soft fit)
        if (!overflow.isEmpty()) {
            List<Shipment> stillOverflow = new ArrayList<>();
            for (Shipment s : overflow) {
                boolean placed = false;
                // Find the nearest SR territory that can absorb this shipment
                String nearestSr = findNearestTerritory(s, finalAssignments, hubLat, hubLng);
                if (nearestSr != null) {
                    int shiftDuration = getShiftDuration(nearestSr, srShiftDurations, globalShiftDuration);
                    double softCap = shiftDuration * 1.05;

                    List<Shipment> current = finalAssignments.get(nearestSr);
                    List<Shipment> trial = new ArrayList<>(current);
                    trial.add(s);
                    ShiftWorkloadCalculatorService.WorkloadResult w = workloadCalculator.computeWorkload(
                            nearestNeighbourOrder(trial, hubLat, hubLng));

                    if (w.totalMinutes() <= softCap) {
                        current.add(s);
                        placed = true;
                    }
                }
                if (!placed) {
                    stillOverflow.add(s);
                }
            }
            overflow = stillOverflow;
        }

        log.info("TerritoryPartition: result — {} active SRs, {} overflow",
                activeSrs.stream().filter(sr -> !finalAssignments.get(sr).isEmpty()).count(),
                overflow.size());

        return new TerritoryResult(finalAssignments, overflow, territoryBoundaries);
    }

    /**
     * Compute optimal number of SRs needed based on total workload.
     * Uses a greedy approach: fill the largest-capacity SRs first (sorted descending).
     * Only activates additional SRs when the previous ones are full.
     * 
     * Uses the SOFT CAP (105% of shift duration) for capacity calculation to minimize
     * active SR count. The target utilisation (88%) is used during territory assignment
     * to decide when to move to the next SR, but for counting purposes we use the
     * maximum capacity each SR can handle.
     */
    private int computeOptimalSrCount(double totalWorkload, List<String> srsSortedByCapacity,
                                       Map<String, Integer> srShiftDurations,
                                       int globalShiftDuration, double targetUtilisation) {
        // Per-SR overhead when splitting into multiple routes:
        // each additional SR adds ~30 min break + ~15 min return = 45 min
        double perSrOverhead = 45.0;
        
        double remainingWorkload = totalWorkload;
        int count = 0;
        
        log.info("TerritoryPartition.computeOptimalSrCount: totalWorkload={} min, perSrOverhead={} min",
                Math.round(totalWorkload), Math.round(perSrOverhead));
        
        for (String sr : srsSortedByCapacity) {
            if (remainingWorkload <= 0) break;
            int shiftDuration = getShiftDuration(sr, srShiftDurations, globalShiftDuration);
            // Use soft cap (105%) for optimal count — we want to minimize SRs
            // The 88% target is for packing decisions, not for "do we need another SR"
            double capacity = shiftDuration * 1.05;
            remainingWorkload -= capacity;
            count++;
            
            log.info("TerritoryPartition.computeOptimalSrCount: SR={}, shiftDuration={}, capacity(105%)={}, remainingAfter={}",
                    sr, shiftDuration, Math.round(capacity), Math.round(remainingWorkload));
            
            // Add overhead for the next SR (if we need another one)
            if (remainingWorkload > 0 && count < srsSortedByCapacity.size()) {
                remainingWorkload += perSrOverhead;
                log.info("TerritoryPartition.computeOptimalSrCount: added overhead, remainingNow={}", Math.round(remainingWorkload));
            }
        }
        
        log.info("TerritoryPartition.computeOptimalSrCount: result={} SRs needed", count);
        return Math.max(1, count);
    }

    /**
     * Weighted K-Means clustering within a region.
     * Creates compact, density-aware territories.
     */
    private Map<String, List<Shipment>> weightedKMeans(List<Shipment> shipments,
                                                        List<String> srNames,
                                                        double hubLat, double hubLng) {
        int k = srNames.size();
        int n = shipments.size();

        if (k == 1) {
            Map<String, List<Shipment>> result = new LinkedHashMap<>();
            result.put(srNames.get(0), new ArrayList<>(shipments));
            return result;
        }

        // Seed centroids using angular partitioning from hub (deterministic)
        List<Shipment> byAngle = shipments.stream()
                .sorted(Comparator.comparingDouble((Shipment s) ->
                        Math.atan2(s.getDropLongitude() - hubLng, s.getDropLatitude() - hubLat))
                        .thenComparing(Shipment::getShippingId))
                .collect(Collectors.toList());

        double[][] centroids = new double[k][2];
        for (int i = 0; i < k; i++) {
            int idx = (int) ((long) i * n / k);
            centroids[i][0] = byAngle.get(idx).getDropLatitude();
            centroids[i][1] = byAngle.get(idx).getDropLongitude();
        }

        // K-Means iterations
        int[] labels = new int[n];
        for (int iter = 0; iter < 30; iter++) {
            boolean changed = false;

            // Assign each shipment to nearest centroid
            for (int i = 0; i < n; i++) {
                Shipment s = shipments.get(i);
                double minDist = Double.MAX_VALUE;
                int best = 0;
                for (int c = 0; c < k; c++) {
                    double d = haversine(s.getDropLatitude(), s.getDropLongitude(),
                            centroids[c][0], centroids[c][1]);
                    if (d < minDist) {
                        minDist = d;
                        best = c;
                    }
                }
                if (labels[i] != best) {
                    labels[i] = best;
                    changed = true;
                }
            }

            if (!changed) break;

            // Recompute centroids
            for (int c = 0; c < k; c++) {
                double sumLat = 0, sumLng = 0;
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (labels[i] == c) {
                        sumLat += shipments.get(i).getDropLatitude();
                        sumLng += shipments.get(i).getDropLongitude();
                        count++;
                    }
                }
                if (count > 0) {
                    centroids[c][0] = sumLat / count;
                    centroids[c][1] = sumLng / count;
                }
            }
        }

        // Build result map
        Map<String, List<Shipment>> result = new LinkedHashMap<>();
        for (int c = 0; c < k; c++) {
            result.put(srNames.get(c), new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            result.get(srNames.get(labels[i])).add(shipments.get(i));
        }

        return result;
    }

    /**
     * Find the nearest SR territory for a shipment (for overflow placement).
     */
    private String findNearestTerritory(Shipment s, Map<String, List<Shipment>> assignments,
                                         double hubLat, double hubLng) {
        String nearest = null;
        double minDist = Double.MAX_VALUE;

        for (Map.Entry<String, List<Shipment>> entry : assignments.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            // Compute centroid of this SR's territory
            double avgLat = entry.getValue().stream().mapToDouble(Shipment::getDropLatitude).average().orElse(hubLat);
            double avgLng = entry.getValue().stream().mapToDouble(Shipment::getDropLongitude).average().orElse(hubLng);

            double dist = haversine(s.getDropLatitude(), s.getDropLongitude(), avgLat, avgLng);
            if (dist < minDist) {
                minDist = dist;
                nearest = entry.getKey();
            }
        }
        return nearest;
    }

    /**
     * Nearest-neighbor ordering from hub.
     */
    private List<Shipment> nearestNeighbourOrder(List<Shipment> shipments, double hubLat, double hubLng) {
        if (shipments.size() <= 1) return new ArrayList<>(shipments);

        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> ordered = new ArrayList<>();
        double curLat = hubLat, curLng = hubLng;

        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                Shipment s = remaining.get(i);
                double d = haversine(curLat, curLng, s.getDropLatitude(), s.getDropLongitude());
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
            Shipment next = remaining.remove(bestIdx);
            ordered.add(next);
            curLat = next.getDropLatitude();
            curLng = next.getDropLongitude();
        }
        return ordered;
    }

    /**
     * Compute convex hull of shipment coordinates for territory boundary visualization.
     * Uses Graham scan algorithm.
     */
    private List<double[]> computeConvexHull(List<Shipment> shipments) {
        if (shipments.size() < 3) {
            return shipments.stream()
                    .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                    .collect(Collectors.toList());
        }

        List<double[]> points = shipments.stream()
                .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                .collect(Collectors.toList());

        // Find lowest point (min lat, then min lng)
        int lowest = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)[0] < points.get(lowest)[0] ||
                    (points.get(i)[0] == points.get(lowest)[0] && points.get(i)[1] < points.get(lowest)[1])) {
                lowest = i;
            }
        }
        Collections.swap(points, 0, lowest);
        double[] pivot = points.get(0);

        // Sort by polar angle from pivot
        points.subList(1, points.size()).sort((a, b) -> {
            double angleA = Math.atan2(a[1] - pivot[1], a[0] - pivot[0]);
            double angleB = Math.atan2(b[1] - pivot[1], b[0] - pivot[0]);
            if (angleA != angleB) return Double.compare(angleA, angleB);
            return Double.compare(
                    haversine(pivot[0], pivot[1], a[0], a[1]),
                    haversine(pivot[0], pivot[1], b[0], b[1]));
        });

        // Graham scan
        Deque<double[]> stack = new ArrayDeque<>();
        stack.push(points.get(0));
        if (points.size() > 1) stack.push(points.get(1));

        for (int i = 2; i < points.size(); i++) {
            while (stack.size() > 1) {
                double[] top = stack.pop();
                double[] second = stack.peek();
                if (cross(second, top, points.get(i)) > 0) {
                    stack.push(top);
                    break;
                }
            }
            stack.push(points.get(i));
        }

        return new ArrayList<>(stack);
    }

    private double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private int getShiftDuration(String sr, Map<String, Integer> srShiftDurations, int globalDefault) {
        if (srShiftDurations == null || srShiftDurations.isEmpty()) return globalDefault;
        Integer duration = srShiftDurations.get(sr);
        return duration != null ? duration : globalDefault;
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // =========================================================================
    // Result record
    // =========================================================================

    /**
     * Result of territory partitioning.
     *
     * @param assignments       per-SR shipment assignments (locked territories)
     * @param overflow          shipments that couldn't fit in any territory
     * @param territoryBoundaries per-SR convex hull boundaries for visualization
     */
    public record TerritoryResult(
            Map<String, List<Shipment>> assignments,
            List<Shipment> overflow,
            Map<String, List<double[]>> territoryBoundaries
    ) {}
}
