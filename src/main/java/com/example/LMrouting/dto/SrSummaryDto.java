package com.example.LMrouting.dto;

import java.util.List;

/**
 * Per-SR summary included in AllocationSummary.
 *
 * Earnings model:
 *   grossPayout     = sum of expectedPayout for all assigned shipments
 *   fuelCost        = estimatedDistanceKm × FUEL_COST_PER_KM (₹2.5/km)
 *   netEarnings     = grossPayout - fuelCost
 *
 * The allocation engine minimises the spread of netEarnings across SRs.
 */
public record SrSummaryDto(
        String srName,
        int shipmentCount,
        int heavyShipmentCount,
        double compositeLoadScore,   // legacy field — kept for API compatibility
        double estimatedDistanceKm,
        List<String> pincodesCovered,
        // ── Earnings breakdown (new) ──────────────────────────────────────────
        double grossPayout,          // Σ expectedPayout across all assigned shipments (₹)
        double fuelCost,             // estimatedDistanceKm × 2.5  (₹)
        double netEarnings           // grossPayout - fuelCost  (₹)
) {
    /**
     * Backward-compatible constructor for callers that don't supply earnings fields.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, 0.0, 0.0, 0.0);
    }
}
