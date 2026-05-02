package com.example.LMrouting.service;

import com.example.LMrouting.dto.ScoreWeights;
import com.example.LMrouting.model.Shipment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Pure utility class for computing the Composite Load Score and its variance.
 *
 * <pre>
 * Score(sr) = w1 × shipmentCount
 *           + w2 × totalPhyWeight
 *           + w3 × estimatedDistanceKm
 *           + w4 × heavyShipmentCount
 * </pre>
 *
 * No Spring annotations — this class is intentionally framework-free so it can
 * be used in property-based tests without a Spring context.
 */
public class CompositeLoadScoreCalculator {

    private CompositeLoadScoreCalculator() {
        // utility class — no instances
    }

    /**
     * Compute the Composite Load Score for a list of shipments assigned to one SR.
     *
     * @param shipments          the shipments assigned to the SR (may be empty)
     * @param estimatedDistanceKm pre-computed route distance in km (pass 0.0 when w3 = 0)
     * @param weights            the four configurable weights
     * @return the composite score
     */
    public static double compute(List<Shipment> shipments,
                                 double estimatedDistanceKm,
                                 ScoreWeights weights) {
        if (shipments == null || shipments.isEmpty()) {
            return 0.0;
        }

        int shipmentCount = shipments.size();

        double totalPhyWeight = shipments.stream()
                .mapToDouble(Shipment::getPhyWeight)
                .sum();

        long heavyShipmentCount = shipments.stream()
                .filter(s -> s.getIsHeavy() == 1)
                .count();

        return weights.getW1() * shipmentCount
             + weights.getW2() * totalPhyWeight
             + weights.getW3() * estimatedDistanceKm
             + weights.getW4() * heavyShipmentCount;
    }

    /**
     * Compute the population variance of Composite Load Scores across all SRs.
     *
     * @param scoresBySr map of SR name → score; must not be null
     * @return population variance, or 0.0 if the map has fewer than 2 entries
     */
    public static double variance(Map<String, Double> scoresBySr) {
        if (scoresBySr == null || scoresBySr.size() < 2) {
            return 0.0;
        }

        Collection<Double> values = scoresBySr.values();
        double mean = values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        return values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
    }
}
