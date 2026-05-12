package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response from POST /api/allocate and GET /api/allocate/{date}/summary.
 *
 * Earnings fairness metrics:
 *   earningsVariance   — population variance of netEarnings across SRs (lower = fairer)
 *   earningsRange      — max(netEarnings) - min(netEarnings) across SRs (lower = fairer)
 *   meanNetEarnings    — mean netEarnings across SRs
 *
 * Capacity range fields:
 *   capacityRangeMin   — minimum shipments per SR (allocation.sr.capacity.min, default 80)
 *   capacityRangeMax   — maximum shipments per SR used for total capacity cap (allocation.sr.capacity.max, default 100)
 *
 * Time-based mode fields (null in count-based mode):
 *   allocationMode        — "time-based" | "count-based"
 *   shiftDurationMinutes  — configured shift duration used for this run
 *   overflowShipments     — shipments that couldn't fit in any affinity SR's shift
 *   noRegionShipments     — shipments with no matching affinity region
 */
public record AllocationSummary(
        String date,
        int totalShipments,
        int allocatedShipments,
        int unallocatedShipments,
        int capacityRangeMin,
        int capacityRangeMax,
        int totalSrs,
        int minShipmentsPerSr,
        int maxShipmentsPerSr,
        double avgShipmentsPerSr,
        double fairnessVariance,     // legacy: variance of composite load scores
        List<SrSummaryDto> srSummaries,
        // ── Earnings fairness metrics ─────────────────────────────────────────
        double earningsVariance,     // population variance of netEarnings (₹²)
        double earningsRange,        // max - min netEarnings (₹)
        double meanNetEarnings,      // mean netEarnings across SRs (₹)
        // ── Time-based mode fields (null in count-based mode) ─────────────────
        String allocationMode,       // "time-based" | "count-based" | null
        Integer shiftDurationMinutes, // null in count-based
        Integer overflowShipments,   // null in count-based
        Integer noRegionShipments,   // null in count-based
        // ── Region health summaries (null in count-based mode) ────────────────
        List<RegionSummaryDto> regionSummaries,  // per-region health + rebalancing suggestions
        // ── Operational warnings (null in count-based mode) ───────────────────
        List<String> operationalWarnings,  // no-SR region warnings, overloaded/idle SR warnings
        // ── Earnings imbalance warning (null in count-based mode) ─────────────
        Boolean earningsImbalanceWarning   // true when > 50% of SRs exceed ±20% of median earnings
) {
    /**
     * Backward-compatible constructor without earnings metrics or time-based fields.
     */
    public AllocationSummary(String date, int totalShipments, int allocatedShipments,
                              int unallocatedShipments, int capacityRangeMin, int capacityRangeMax,
                              int totalSrs, int minShipmentsPerSr, int maxShipmentsPerSr,
                              double avgShipmentsPerSr, double fairnessVariance,
                              List<SrSummaryDto> srSummaries) {
        this(date, totalShipments, allocatedShipments, unallocatedShipments,
             capacityRangeMin, capacityRangeMax,
             totalSrs, minShipmentsPerSr, maxShipmentsPerSr, avgShipmentsPerSr,
             fairnessVariance, srSummaries, 0.0, 0.0, 0.0,
             null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor with earnings metrics but without time-based fields.
     */
    public AllocationSummary(String date, int totalShipments, int allocatedShipments,
                              int unallocatedShipments, int capacityRangeMin, int capacityRangeMax,
                              int totalSrs, int minShipmentsPerSr, int maxShipmentsPerSr,
                              double avgShipmentsPerSr, double fairnessVariance,
                              List<SrSummaryDto> srSummaries,
                              double earningsVariance, double earningsRange, double meanNetEarnings) {
        this(date, totalShipments, allocatedShipments, unallocatedShipments,
             capacityRangeMin, capacityRangeMax,
             totalSrs, minShipmentsPerSr, maxShipmentsPerSr, avgShipmentsPerSr,
             fairnessVariance, srSummaries, earningsVariance, earningsRange, meanNetEarnings,
             null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor with time-based fields but without operationalWarnings.
     */
    public AllocationSummary(String date, int totalShipments, int allocatedShipments,
                              int unallocatedShipments, int capacityRangeMin, int capacityRangeMax,
                              int totalSrs, int minShipmentsPerSr, int maxShipmentsPerSr,
                              double avgShipmentsPerSr, double fairnessVariance,
                              List<SrSummaryDto> srSummaries,
                              double earningsVariance, double earningsRange, double meanNetEarnings,
                              String allocationMode, Integer shiftDurationMinutes,
                              Integer overflowShipments, Integer noRegionShipments,
                              List<RegionSummaryDto> regionSummaries) {
        this(date, totalShipments, allocatedShipments, unallocatedShipments,
             capacityRangeMin, capacityRangeMax,
             totalSrs, minShipmentsPerSr, maxShipmentsPerSr, avgShipmentsPerSr,
             fairnessVariance, srSummaries, earningsVariance, earningsRange, meanNetEarnings,
             allocationMode, shiftDurationMinutes, overflowShipments, noRegionShipments,
             regionSummaries, null, null);
    }

    /**
     * Backward-compatible constructor with operationalWarnings but without earningsImbalanceWarning.
     */
    public AllocationSummary(String date, int totalShipments, int allocatedShipments,
                              int unallocatedShipments, int capacityRangeMin, int capacityRangeMax,
                              int totalSrs, int minShipmentsPerSr, int maxShipmentsPerSr,
                              double avgShipmentsPerSr, double fairnessVariance,
                              List<SrSummaryDto> srSummaries,
                              double earningsVariance, double earningsRange, double meanNetEarnings,
                              String allocationMode, Integer shiftDurationMinutes,
                              Integer overflowShipments, Integer noRegionShipments,
                              List<RegionSummaryDto> regionSummaries,
                              List<String> operationalWarnings) {
        this(date, totalShipments, allocatedShipments, unallocatedShipments,
             capacityRangeMin, capacityRangeMax,
             totalSrs, minShipmentsPerSr, maxShipmentsPerSr, avgShipmentsPerSr,
             fairnessVariance, srSummaries, earningsVariance, earningsRange, meanNetEarnings,
             allocationMode, shiftDurationMinutes, overflowShipments, noRegionShipments,
             regionSummaries, operationalWarnings, null);
    }
}
