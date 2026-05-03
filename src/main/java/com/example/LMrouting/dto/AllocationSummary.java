package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response from POST /api/allocate and GET /api/allocate/{date}/summary.
 *
 * Earnings fairness metrics (new fields):
 *   earningsVariance   — population variance of netEarnings across SRs (lower = fairer)
 *   earningsRange      — max(netEarnings) - min(netEarnings) across SRs (lower = fairer)
 *   meanNetEarnings    — mean netEarnings across SRs
 */
public record AllocationSummary(
        String date,
        int totalShipments,
        int allocatedShipments,
        int unallocatedShipments,
        int srCapacity,
        int totalSrs,
        int minShipmentsPerSr,
        int maxShipmentsPerSr,
        double avgShipmentsPerSr,
        double fairnessVariance,     // legacy: variance of composite load scores
        List<SrSummaryDto> srSummaries,
        // ── Earnings fairness metrics (new) ───────────────────────────────────
        double earningsVariance,     // population variance of netEarnings (₹²)
        double earningsRange,        // max - min netEarnings (₹)
        double meanNetEarnings       // mean netEarnings across SRs (₹)
) {
    /**
     * Backward-compatible constructor without earnings metrics.
     */
    public AllocationSummary(String date, int totalShipments, int allocatedShipments,
                              int unallocatedShipments, int srCapacity, int totalSrs,
                              int minShipmentsPerSr, int maxShipmentsPerSr,
                              double avgShipmentsPerSr, double fairnessVariance,
                              List<SrSummaryDto> srSummaries) {
        this(date, totalShipments, allocatedShipments, unallocatedShipments, srCapacity,
             totalSrs, minShipmentsPerSr, maxShipmentsPerSr, avgShipmentsPerSr,
             fairnessVariance, srSummaries, 0.0, 0.0, 0.0);
    }
}
