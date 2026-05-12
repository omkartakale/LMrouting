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
        // ── Earnings breakdown ────────────────────────────────────────────────
        double grossPayout,          // Σ effectivePayout (priority-weighted) across all assigned shipments (₹)
        double fuelCost,             // estimatedDistanceKm × 2.5  (₹)
        double netEarnings,          // grossPayout - fuelCost  (₹)
        // ── Priority breakdown ────────────────────────────────────────────────
        int p0Count,                 // number of P0 shipments (factor 1.0)
        int p1Count,                 // number of P1 shipments (factor 0.75)
        int p2Count                  // number of P2 shipments (factor 0.50)
) {
    /**
     * Backward-compatible constructor for callers that don't supply earnings/priority fields.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, 0.0, 0.0, 0.0, 0, 0, 0);
    }

    /**
     * Constructor without priority breakdown (for callers that only supply earnings).
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered,
                        double grossPayout, double fuelCost, double netEarnings) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, grossPayout, fuelCost, netEarnings, 0, 0, 0);
    }
}
