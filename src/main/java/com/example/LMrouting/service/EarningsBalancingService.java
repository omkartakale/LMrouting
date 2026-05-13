package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-allocation earnings balancing — strictly WITHIN a single region (never cross-region).
 *
 * <p>This service is invoked once per region after dense packing (Phase 8b).
 * It iteratively swaps boundary shipments from the highest-earning SR to the
 * lowest-earning SR until the earnings distribution meets the balance target
 * or the maximum number of iterations is reached.
 *
 * <p><strong>Hard Constraint:</strong> Shipments are NEVER moved across region boundaries.
 * The balancing only swaps shipments between SRs assigned to the same region.
 * This preserves the strict affinity boundary rule.
 *
 * <p><strong>Workload Constraint:</strong> A shipment is only transferred if the
 * receiving SR's total workload stays within the effective shift duration
 * (480 min − 30 min break = 450 min).
 *
 * <p><strong>Termination:</strong> The algorithm stops when ≥ {@code balanceTarget}
 * fraction of SRs are within ±20% of the median earnings, or after
 * {@code maxIterations} iterations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EarningsBalancingService {

    private final ShiftWorkloadCalculatorService workloadCalculator;
    private final RouteOptimizerService routeOptimizerService;

    /** Fraction of SRs that must be within ±20% of median earnings to consider balanced. */
    @Value("${allocation.earnings.balance.target:0.50}")
    private double balanceTarget;

    /** Maximum iterations for the balancing loop. */
    @Value("${allocation.earnings.balance.maxIterations:50}")
    private int maxIterations;

    /** Effective shift duration in minutes (480 min shift − 30 min break). */
    private static final double EFFECTIVE_SHIFT_MINUTES = 450.0;

    /** Maximum boundary candidates to evaluate per iteration. */
    private static final int BOUNDARY_CANDIDATE_LIMIT = 10;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Balance earnings across SRs within a single region (backward-compatible overload).
     * Uses the default EFFECTIVE_SHIFT_MINUTES + 30 as the global shift duration.
     *
     * @param srAssignments map of SR name → ordered shipments (all within the same region)
     * @return result containing the (possibly modified) assignments and an imbalance warning flag
     */
    public BalancingResult balance(Map<String, List<Shipment>> srAssignments) {
        return balance(srAssignments, Collections.emptyMap(), (int) EFFECTIVE_SHIFT_MINUTES + 30);
    }

    /**
     * Balance earnings across SRs within a single region, using per-SR shift durations.
     *
     * @param srAssignments     map of SR name → ordered shipments (all within the same region)
     * @param srShiftDurations  per-SR shift duration overrides (may be null or empty)
     * @param globalShiftDuration fallback shift duration in minutes when no per-SR override exists
     * @return result containing the (possibly modified) assignments and an imbalance warning flag
     */
    public BalancingResult balance(Map<String, List<Shipment>> srAssignments,
                                   Map<String, Integer> srShiftDurations,
                                   int globalShiftDuration) {
        if (srAssignments == null || srAssignments.size() < 2) {
            return new BalancingResult(
                    srAssignments != null ? srAssignments : Map.of(),
                    false
            );
        }

        // Filter to active SRs (those with at least one shipment)
        Map<String, List<Shipment>> active = srAssignments.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        if (active.size() < 2) {
            return new BalancingResult(srAssignments, false);
        }

        // Compute initial earnings per SR
        Map<String, Double> earnings = computeEarningsMap(active);

        int iterations = 0;
        while (iterations < maxIterations && !isBalanced(earnings)) {
            // Find highest and lowest earning SRs
            String richSr = maxEarningsSr(earnings);
            String poorSr = minEarningsSr(earnings);
            if (richSr.equals(poorSr)) break;

            // Find the best boundary shipment to transfer
            Shipment best = findBestTransfer(richSr, poorSr, active, earnings, srShiftDurations, globalShiftDuration);
            if (best == null) break; // no valid transfer found

            // Execute the transfer
            active.get(richSr).remove(best);
            active.get(poorSr).add(best);

            // Recompute earnings for affected SRs
            earnings.put(richSr, computeSrEarnings(active.get(richSr)));
            earnings.put(poorSr, computeSrEarnings(active.get(poorSr)));

            iterations++;
        }

        boolean imbalanceWarning = !isBalanced(earnings);

        log.info("EarningsBalancing: {} iterations, balanced={}, activeSRs={}",
                iterations, !imbalanceWarning, active.size());

        // Merge back any empty SRs that were filtered out
        Map<String, List<Shipment>> result = new LinkedHashMap<>(srAssignments);
        result.putAll(active);

        return new BalancingResult(result, imbalanceWarning);
    }

    // =========================================================================
    // Balance check
    // =========================================================================

    /**
     * Check if ≥ balanceTarget fraction of active SRs are within ±20% of median earnings.
     */
    private boolean isBalanced(Map<String, Double> earnings) {
        if (earnings.size() < 2) return true;

        double median = computeMedian(earnings.values());
        if (median == 0.0) {
            // If median is zero, all SRs with zero earnings are "within ±20%"
            long zeroCount = earnings.values().stream().filter(e -> e == 0.0).count();
            return (double) zeroCount / earnings.size() >= balanceTarget;
        }

        double lowerBound = median * 0.80;
        double upperBound = median * 1.20;

        long withinRange = earnings.values().stream()
                .filter(e -> e >= lowerBound && e <= upperBound)
                .count();

        return (double) withinRange / earnings.size() >= balanceTarget;
    }

    // =========================================================================
    // Transfer logic
    // =========================================================================

    /**
     * Find the best boundary shipment to transfer from richSr to poorSr.
     *
     * <p>"Boundary" = shipments in richSr whose distance to poorSr's centroid is
     * less than the median distance of all shipments in richSr to poorSr's centroid.
     * This ensures only geographically border shipments are considered for transfer,
     * preserving territory compactness.
     *
     * <p>Additionally, rejects transfers that would increase the source SR's route
     * distance by more than 10%.
     */
    private Shipment findBestTransfer(String richSr, String poorSr,
                                      Map<String, List<Shipment>> assignments,
                                      Map<String, Double> earnings,
                                      Map<String, Integer> srShiftDurations,
                                      int globalShiftDuration) {
        List<Shipment> richShipments = assignments.get(richSr);
        if (richShipments == null || richShipments.size() <= 1) return null;

        List<Shipment> poorShipments = assignments.get(poorSr);
        double[] poorCentroid = computeCentroid(poorShipments);

        // Compute distances from all rich shipments to poor centroid
        List<Double> allDistances = richShipments.stream()
                .map(s -> haversine(s.getDropLatitude(), s.getDropLongitude(),
                        poorCentroid[0], poorCentroid[1]))
                .collect(Collectors.toList());
        double medianDistance = computeMedian(allDistances);

        // Compute rich SR centroid
        double[] richCentroid = computeCentroid(richShipments);

        // Only consider TRUE boundary candidates:
        // 1. Distance to poor centroid < distance to own (rich) centroid (genuinely closer to receiving SR)
        // 2. Distance to poor centroid < median distance (existing guard)
        List<Shipment> candidates = richShipments.stream()
                .filter(s -> {
                    double distToPoor = haversine(s.getDropLatitude(), s.getDropLongitude(),
                            poorCentroid[0], poorCentroid[1]);
                    double distToOwn = haversine(s.getDropLatitude(), s.getDropLongitude(),
                            richCentroid[0], richCentroid[1]);
                    // Only transfer if shipment is genuinely closer to the receiving SR
                    return distToPoor < distToOwn && distToPoor <= medianDistance;
                })
                .sorted(Comparator.comparingDouble((Shipment s) ->
                        haversine(s.getDropLatitude(), s.getDropLongitude(),
                                poorCentroid[0], poorCentroid[1])))
                .limit(BOUNDARY_CANDIDATE_LIMIT)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;

        double currentMedian = computeMedian(earnings.values());
        double bestImprovement = 0.0;
        Shipment bestShipment = null;

        // Determine the effective shift duration for the receiving (poor) SR
        int poorSrShiftDuration = srShiftDurations != null && srShiftDurations.containsKey(poorSr)
                ? srShiftDurations.get(poorSr) : globalShiftDuration;

        // Compute current route distance for the source SR (for 10% guard)
        double currentRichDistance = routeOptimizerService.estimateDistanceKm(richShipments);

        for (Shipment candidate : candidates) {
            // Simulate transfer: check workload constraint on receiving SR
            List<Shipment> newPoor = new ArrayList<>(poorShipments);
            newPoor.add(candidate);

            ShiftWorkloadCalculatorService.WorkloadResult poorWorkload =
                    workloadCalculator.computeWorkload(newPoor);
            if (poorWorkload.totalMinutes() > poorSrShiftDuration) {
                continue; // would exceed shift duration
            }

            // Simulate earnings after transfer
            List<Shipment> newRich = new ArrayList<>(richShipments);
            newRich.remove(candidate);

            // Reject if removing this shipment increases source SR route distance by > 10%
            if (currentRichDistance > 0 && newRich.size() > 1) {
                double newRichDistance = routeOptimizerService.estimateDistanceKm(newRich);
                if (newRichDistance > currentRichDistance * 1.10) {
                    continue; // would degrade source SR's route compactness
                }
            }

            double newRichEarnings = computeSrEarnings(newRich);
            double newPoorEarnings = computeSrEarnings(newPoor);

            // Compute improvement: how many more SRs are within ±20% of median
            Map<String, Double> simEarnings = new LinkedHashMap<>(earnings);
            simEarnings.put(richSr, newRichEarnings);
            simEarnings.put(poorSr, newPoorEarnings);

            double simMedian = computeMedian(simEarnings.values());
            double lowerBound = simMedian * 0.80;
            double upperBound = simMedian * 1.20;

            long withinRange = simEarnings.values().stream()
                    .filter(e -> e >= lowerBound && e <= upperBound)
                    .count();

            // Also check that the transfer actually reduces the spread
            // (don't transfer if it makes things worse)
            double currentSpread = earnings.get(richSr) - earnings.get(poorSr);
            double newSpread = newRichEarnings - newPoorEarnings;
            if (newSpread >= currentSpread) continue; // transfer doesn't help

            double improvement = (double) withinRange / simEarnings.size();
            if (improvement > bestImprovement) {
                bestImprovement = improvement;
                bestShipment = candidate;
            }
        }

        return bestShipment;
    }

    // =========================================================================
    // Earnings computation
    // =========================================================================

    /**
     * Compute net earnings for a single SR's shipments.
     * Uses grossPayout - fuelCost (same formula as CompositeLoadScoreCalculator).
     */
    private double computeSrEarnings(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) return 0.0;
        double distanceKm = routeOptimizerService.estimateDistanceKm(shipments);
        return CompositeLoadScoreCalculator.netEarnings(shipments, distanceKm);
    }

    /**
     * Compute earnings map for all active SRs.
     */
    private Map<String, Double> computeEarningsMap(Map<String, List<Shipment>> assignments) {
        Map<String, Double> earnings = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignments.entrySet()) {
            earnings.put(entry.getKey(), computeSrEarnings(entry.getValue()));
        }
        return earnings;
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    private String maxEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private String minEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /**
     * Compute the median of a collection of values.
     */
    private double computeMedian(Collection<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            return sorted.get(size / 2);
        }
    }

    /**
     * Compute the centroid (average lat/lng) of a list of shipments.
     */
    private double[] computeCentroid(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) {
            return new double[]{0.0, 0.0};
        }
        double avgLat = shipments.stream().mapToDouble(Shipment::getDropLatitude).average().orElse(0.0);
        double avgLng = shipments.stream().mapToDouble(Shipment::getDropLongitude).average().orElse(0.0);
        return new double[]{avgLat, avgLng};
    }

    /**
     * Haversine distance in km between two coordinate points.
     */
    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // =========================================================================
    // Result record
    // =========================================================================

    /**
     * Result of the earnings balancing pass.
     *
     * @param srAssignments   the (possibly modified) SR → shipments map
     * @param earningsImbalanceWarning true if the balance target was NOT met after all iterations
     */
    public record BalancingResult(
            Map<String, List<Shipment>> srAssignments,
            boolean earningsImbalanceWarning
    ) {}
}
