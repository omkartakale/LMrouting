package com.example.LMrouting.service;

import com.example.LMrouting.dto.ScoreWeights;
import com.example.LMrouting.model.Shipment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Pure utility class for computing fairness metrics used by the allocation engine.
 *
 * <h2>Primary objective — Net Earnings Fairness</h2>
 * <pre>
 * grossPayout(sr)  = Σ expectedPayout  for all shipments assigned to sr
 * fuelCost(sr)     = estimatedDistanceKm × FUEL_COST_PER_KM
 * netEarnings(sr)  = grossPayout(sr) - fuelCost(sr)
 * </pre>
 * The allocation engine minimises the <em>range</em> (max − min) and <em>variance</em>
 * of netEarnings across all present SRs.
 *
 * <h2>Secondary objective — Composite Load Score (legacy)</h2>
 * <pre>
 * Score(sr) = w1 × shipmentCount
 *           + w2 × totalPhyWeight
 *           + w3 × estimatedDistanceKm
 *           + w4 × heavyShipmentCount
 * </pre>
 * Still used for the initial K-Means seeding and as a tie-breaker.
 *
 * No Spring annotations — intentionally framework-free for use in property-based tests.
 */
public class CompositeLoadScoreCalculator {

    /** Fuel cost per km in rupees. Matches the business rule: ₹2.5/km. */
    public static final double FUEL_COST_PER_KM = 2.5;

    private CompositeLoadScoreCalculator() { /* utility class */ }

    // =========================================================================
    // Net Earnings (primary fairness objective)
    // =========================================================================

    /**
     * Compute the gross payout for a list of shipments (sum of expectedPayout).
     *
     * @param shipments shipments assigned to one SR
     * @return total expected payout in ₹
     */
    public static double grossPayout(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) return 0.0;
        return shipments.stream().mapToDouble(Shipment::getExpectedPayout).sum();
    }

    /**
     * Compute the fuel cost for a route.
     *
     * @param distanceKm Haversine-estimated route distance in km
     * @return fuel cost in ₹ (distanceKm × 2.5)
     */
    public static double fuelCost(double distanceKm) {
        return distanceKm * FUEL_COST_PER_KM;
    }

    /**
     * Compute the net earnings for one SR.
     *
     * <pre>netEarnings = grossPayout - fuelCost</pre>
     *
     * @param shipments   shipments assigned to the SR
     * @param distanceKm  Haversine-estimated route distance in km
     * @return net earnings in ₹
     */
    public static double netEarnings(List<Shipment> shipments, double distanceKm) {
        return grossPayout(shipments) - fuelCost(distanceKm);
    }

    /**
     * Compute the population variance of net earnings across all SRs.
     * Lower variance = fairer allocation.
     *
     * @param earningsBySr map of SR name → net earnings; must not be null
     * @return population variance in ₹², or 0.0 if fewer than 2 SRs
     */
    public static double earningsVariance(Map<String, Double> earningsBySr) {
        if (earningsBySr == null || earningsBySr.size() < 2) return 0.0;
        Collection<Double> values = earningsBySr.values();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
    }

    /**
     * Compute the range (max − min) of net earnings across all SRs.
     * This is the primary fairness metric: the absolute difference between
     * the highest-earning and lowest-earning SR.
     *
     * @param earningsBySr map of SR name → net earnings
     * @return earnings range in ₹, or 0.0 if fewer than 2 SRs
     */
    public static double earningsRange(Map<String, Double> earningsBySr) {
        if (earningsBySr == null || earningsBySr.size() < 2) return 0.0;
        double max = earningsBySr.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double min = earningsBySr.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        return max - min;
    }

    /**
     * Compute the mean net earnings across all SRs.
     *
     * @param earningsBySr map of SR name → net earnings
     * @return mean net earnings in ₹
     */
    public static double meanEarnings(Map<String, Double> earningsBySr) {
        if (earningsBySr == null || earningsBySr.isEmpty()) return 0.0;
        return earningsBySr.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // =========================================================================
    // Composite Load Score (legacy / secondary objective)
    // =========================================================================

    /**
     * Compute the Composite Load Score for a list of shipments assigned to one SR.
     *
     * <pre>
     * Score = w1 × shipmentCount
     *       + w2 × totalPhyWeight
     *       + w3 × estimatedDistanceKm
     *       + w4 × heavyShipmentCount
     * </pre>
     *
     * @param shipments          shipments assigned to the SR (may be empty)
     * @param estimatedDistanceKm pre-computed route distance in km
     * @param weights            the four configurable weights
     * @return the composite score
     */
    public static double compute(List<Shipment> shipments,
                                 double estimatedDistanceKm,
                                 ScoreWeights weights) {
        if (shipments == null || shipments.isEmpty()) return 0.0;

        int shipmentCount = shipments.size();
        double totalPhyWeight = shipments.stream().mapToDouble(Shipment::getPhyWeight).sum();
        long heavyShipmentCount = shipments.stream().filter(s -> s.getIsHeavy() == 1).count();

        return weights.getW1() * shipmentCount
             + weights.getW2() * totalPhyWeight
             + weights.getW3() * estimatedDistanceKm
             + weights.getW4() * heavyShipmentCount;
    }

    /**
     * Compute the population variance of Composite Load Scores across all SRs.
     *
     * @param scoresBySr map of SR name → score; must not be null
     * @return population variance, or 0.0 if fewer than 2 entries
     */
    public static double variance(Map<String, Double> scoresBySr) {
        if (scoresBySr == null || scoresBySr.size() < 2) return 0.0;
        Collection<Double> values = scoresBySr.values();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
    }
}
