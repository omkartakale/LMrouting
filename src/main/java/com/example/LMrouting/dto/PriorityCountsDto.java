package com.example.LMrouting.dto;

/**
 * Priority tier breakdown for the allocation summary.
 *
 * <p>Provides total, allocated, and unallocated counts for each priority tier (P0/P1/P2).
 * Included in AllocationSummary to give the hub supervisor visibility into how
 * priority shipments are distributed across the allocation.
 */
public record PriorityCountsDto(
        // P0 (Critical) — highest priority
        int p0Total,
        int p0Allocated,
        int p0Unallocated,
        // P1 (High)
        int p1Total,
        int p1Allocated,
        int p1Unallocated,
        // P2 (Normal) — lowest priority
        int p2Total,
        int p2Allocated,
        int p2Unallocated
) {}
