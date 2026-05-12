package com.example.LMrouting.dto;

import java.util.Collections;
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
        // "UNDERLOADED" — avg utilisation < 50%
        // "HEALTHY"     — avg utilisation 50-90%
        // "OVERLOADED"  — avg utilisation > 90% OR overflow > 0
        String healthStatus,

        // ── Rebalancing suggestions ───────────────────────────────────────────
        // Actionable suggestions for the hub supervisor
        List<SrRebalanceSuggestion> suggestions,

        // ── Configuration warnings ────────────────────────────────────────────
        // Warnings about region configuration issues (e.g., no SRs assigned)
        List<String> configurationWarnings,

        // ── Small leftover warning ────────────────────────────────────────────
        // True when overflow < threshold and consolidation failed — shipments
        // marked unallocated instead of activating a new SR
        Boolean smallLeftoverWarning
) {
    /**
     * Backward-compatible constructor without configurationWarnings or smallLeftoverWarning.
     */
    public RegionSummaryDto(String regionName, int totalShipmentsInRegion, int allocatedShipments,
                             int overflowShipments, int assignedSrCount, int activeSrCount,
                             int idleSrCount, List<String> assignedSrNames, List<String> activeSrNames,
                             List<String> idleSrNames, double avgUtilisationPct, double maxUtilisationPct,
                             double minUtilisationPct, String healthStatus,
                             List<SrRebalanceSuggestion> suggestions) {
        this(regionName, totalShipmentsInRegion, allocatedShipments, overflowShipments,
             assignedSrCount, activeSrCount, idleSrCount, assignedSrNames, activeSrNames,
             idleSrNames, avgUtilisationPct, maxUtilisationPct, minUtilisationPct,
             healthStatus, suggestions, Collections.emptyList(), null);
    }

    /**
     * Backward-compatible constructor with configurationWarnings but without smallLeftoverWarning.
     */
    public RegionSummaryDto(String regionName, int totalShipmentsInRegion, int allocatedShipments,
                             int overflowShipments, int assignedSrCount, int activeSrCount,
                             int idleSrCount, List<String> assignedSrNames, List<String> activeSrNames,
                             List<String> idleSrNames, double avgUtilisationPct, double maxUtilisationPct,
                             double minUtilisationPct, String healthStatus,
                             List<SrRebalanceSuggestion> suggestions,
                             List<String> configurationWarnings) {
        this(regionName, totalShipmentsInRegion, allocatedShipments, overflowShipments,
             assignedSrCount, activeSrCount, idleSrCount, assignedSrNames, activeSrNames,
             idleSrNames, avgUtilisationPct, maxUtilisationPct, minUtilisationPct,
             healthStatus, suggestions, configurationWarnings, null);
    }

    /**
     * Compute health status from the region's metrics.
     * <p>
     * Classification thresholds:
     * - UNDERLOADED: avg utilisation < 50%
     * - HEALTHY: avg utilisation 50-90%
     * - OVERLOADED: avg utilisation > 90% OR overflow > 0
     */
    public static String computeHealthStatus(int overflowShipments, int idleSrCount,
                                              double avgUtilisationPct) {
        if (overflowShipments > 0) return "OVERLOADED";
        if (avgUtilisationPct > 90.0) return "OVERLOADED";
        if (avgUtilisationPct < 50.0) return "UNDERLOADED";
        return "HEALTHY";
    }
}
