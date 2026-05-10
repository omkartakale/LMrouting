package com.example.LMrouting.dto;

import java.util.List;

/**
 * Per-region health summary for time-based affinity allocation.
 *
 * Included in AllocationSummary.regionSummaries when allocationMode = "time-based".
 * Gives the hub supervisor a clear picture of each region's load vs capacity,
 * and actionable suggestions for SR rebalancing.
 */
public record RegionSummaryDto(
        // ── Identity ──────────────────────────────────────────────────────────
        String regionName,

        // ── Shipment counts ───────────────────────────────────────────────────
        int totalShipmentsInRegion,   // all shipments that fall inside this region polygon
        int allocatedShipments,       // shipments successfully assigned to SRs in this region
        int overflowShipments,        // shipments that couldn't fit (all SRs at capacity)

        // ── SR capacity ───────────────────────────────────────────────────────
        int assignedSrCount,          // number of SRs assigned to this region
        int activeSrCount,            // SRs with at least 1 shipment
        int idleSrCount,              // SRs with 0 shipments (wasted capacity)
        List<String> assignedSrNames, // SR names assigned to this region
        List<String> activeSrNames,   // SR names that received shipments
        List<String> idleSrNames,     // SR names with 0 shipments

        // ── Workload metrics ──────────────────────────────────────────────────
        double avgUtilisationPct,     // average shift utilisation across active SRs
        double maxUtilisationPct,     // highest utilisation SR in this region
        double minUtilisationPct,     // lowest utilisation SR in this region (0 if idle SRs exist)

        // ── Health status ─────────────────────────────────────────────────────
        // "HEALTHY"    — all SRs near-full, no overflow
        // "OVERFLOW"   — shipments couldn't be allocated (need more SRs)
        // "UNDERLOADED" — SRs have low utilisation (too many SRs for the load)
        // "IDLE_SRS"   — some SRs assigned but got 0 shipments
        String healthStatus,

        // ── Rebalancing suggestions ───────────────────────────────────────────
        // Actionable suggestions for the hub supervisor
        List<SrRebalanceSuggestion> suggestions
) {
    /**
     * Compute health status from the region's metrics.
     */
    public static String computeHealthStatus(int overflowShipments, int idleSrCount,
                                              double avgUtilisationPct) {
        if (overflowShipments > 0) return "OVERFLOW";
        if (idleSrCount > 0) return "IDLE_SRS";
        if (avgUtilisationPct < 50.0) return "UNDERLOADED";
        return "HEALTHY";
    }
}
