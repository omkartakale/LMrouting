package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response DTO for the draft reassignment workflow.
 *
 * <p>Returned by GET /api/allocate/{date}/rebalance/draft — contains computed
 * reassignment recommendations that have NOT been persisted yet.
 *
 * <p>The supervisor reviews these recommendations and can then POST to
 * /api/allocate/{date}/rebalance/save to persist and re-run allocation.
 *
 * <p>Draft targets:
 * <ul>
 *   <li>80-90% utilization for reassigned SRs (moved to a new region)</li>
 *   <li>&gt;90% utilization for native-region SRs (staying in their original region)</li>
 * </ul>
 */
public record DraftReassignmentDto(
        // ── Context ───────────────────────────────────────────────────────────
        String date,
        String status,               // "draft" (not persisted) or "saved" (persisted)

        // ── Recommendations ───────────────────────────────────────────────────
        List<SrRebalanceSuggestion> recommendations,

        // ── Summary metrics ───────────────────────────────────────────────────
        int totalRecommendations,
        int highPriorityCount,
        int mediumPriorityCount,

        // ── Utilization targets ───────────────────────────────────────────────
        double reassignedSrTargetMinUtilization,   // 80%
        double reassignedSrTargetMaxUtilization,   // 90%
        double nativeRegionSrMinUtilization,       // 90%

        // ── Impact estimate ───────────────────────────────────────────────────
        int estimatedAdditionalShipmentsAllocated,

        // ── Message ───────────────────────────────────────────────────────────
        String message
) {}
