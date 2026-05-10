package com.example.LMrouting.dto;

/**
 * A specific SR rebalancing suggestion for the hub supervisor.
 *
 * Generated when one region has overflow (needs more SRs) and another
 * region has idle or underloaded SRs that could be reassigned.
 *
 * Example:
 *   "Move SR-009 from Region 3 (idle, 0% utilisation) to Region 4
 *    (overflow: 668 shipments unallocated). Estimated impact: +668 shipments allocated."
 */
public record SrRebalanceSuggestion(
        // ── The suggested action ──────────────────────────────────────────────
        String srName,                // SR to move
        String fromRegion,            // current region (idle/underloaded)
        String toRegion,              // target region (overflow/overloaded)

        // ── Reason ───────────────────────────────────────────────────────────
        String reason,                // human-readable explanation
        double fromRegionUtilisation, // current utilisation in source region (%)
        int toRegionOverflow,         // overflow shipments in target region

        // ── Priority ─────────────────────────────────────────────────────────
        // "HIGH"   — idle SR + large overflow
        // "MEDIUM" — underloaded SR + moderate overflow
        // "LOW"    — minor rebalancing opportunity
        String priority
) {}
