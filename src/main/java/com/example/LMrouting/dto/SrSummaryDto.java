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
 *
 * Time-based mode fields (null in count-based mode):
 *   affinityStatus          — "AFFINITY_ASSIGNED" | "NON_AFFINITY" | null
 *   estimatedWorkloadMinutes — total estimated shift workload in minutes
 *   shiftUtilisationPct     — (estimatedWorkloadMinutes / shiftDurationMinutes) × 100
 *   handlingTimeMinutes     — sum of per-shipment handling times
 *   travelTimeMinutes       — route travel time (hub → stops)
 *   returnToHubTimeMinutes  — travel time from last stop back to hub
 */
public record SrSummaryDto(
        String srName,
        int shipmentCount,
        int heavyShipmentCount,
        double compositeLoadScore,   // legacy field — kept for API compatibility
        double estimatedDistanceKm,
        List<String> pincodesCovered,
        // ── Earnings breakdown ────────────────────────────────────────────────
        double grossPayout,          // Σ expectedPayout across all assigned shipments (₹)
        double fuelCost,             // estimatedDistanceKm × 2.5  (₹)
        double netEarnings,          // grossPayout - fuelCost  (₹)
        // ── Time-based mode fields (null in count-based mode) ─────────────────
        String affinityStatus,              // "AFFINITY_ASSIGNED" | "NON_AFFINITY" | null
        Double estimatedWorkloadMinutes,    // null in count-based
        Double shiftUtilisationPct,         // null in count-based
        Double handlingTimeMinutes,         // null in count-based
        Double travelTimeMinutes,           // null in count-based
        Double returnToHubTimeMinutes,      // null in count-based
        // ── Operational warning (null if no issue) ────────────────────────────
        String operationalWarning,          // "OVERLOADED" | "IDLE" | null
        // ── Territory boundary (null in count-based mode) ─────────────────────
        List<double[]> territoryBoundary    // convex hull [lat,lng] pairs (null in count-based)
) {
    /**
     * Backward-compatible constructor for callers that don't supply earnings fields.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, 0.0, 0.0, 0.0,
             null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for callers that supply earnings but not time-based fields.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered,
                        double grossPayout, double fuelCost, double netEarnings) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, grossPayout, fuelCost, netEarnings,
             null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for callers that supply time-based fields but not operationalWarning.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered,
                        double grossPayout, double fuelCost, double netEarnings,
                        String affinityStatus, Double estimatedWorkloadMinutes,
                        Double shiftUtilisationPct, Double handlingTimeMinutes,
                        Double travelTimeMinutes, Double returnToHubTimeMinutes) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, grossPayout, fuelCost, netEarnings,
             affinityStatus, estimatedWorkloadMinutes, shiftUtilisationPct,
             handlingTimeMinutes, travelTimeMinutes, returnToHubTimeMinutes, null, null);
    }

    /**
     * Backward-compatible constructor with operationalWarning but without territoryBoundary.
     */
    public SrSummaryDto(String srName, int shipmentCount, int heavyShipmentCount,
                        double compositeLoadScore, double estimatedDistanceKm,
                        List<String> pincodesCovered,
                        double grossPayout, double fuelCost, double netEarnings,
                        String affinityStatus, Double estimatedWorkloadMinutes,
                        Double shiftUtilisationPct, Double handlingTimeMinutes,
                        Double travelTimeMinutes, Double returnToHubTimeMinutes,
                        String operationalWarning) {
        this(srName, shipmentCount, heavyShipmentCount, compositeLoadScore,
             estimatedDistanceKm, pincodesCovered, grossPayout, fuelCost, netEarnings,
             affinityStatus, estimatedWorkloadMinutes, shiftUtilisationPct,
             handlingTimeMinutes, travelTimeMinutes, returnToHubTimeMinutes,
             operationalWarning, null);
    }
}
