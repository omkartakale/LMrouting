package com.example.LMrouting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AffinityAllocationResult holds the result of affinity-based allocation execution.
 * 
 * This class contains:
 * - SR assignments (map of SR name to list of shipments)
 * - Allocation metrics (cross-region overlap, outliers, per-SR metrics, etc.)
 * 
 * Requirements: 15.4, 15.5, 30.1, 33.1, 34.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffinityAllocationResult {

    /**
     * Map of SR name to list of assigned shipments.
     */
    private Map<String, List<Shipment>> srAssignments;

    /**
     * Allocation metrics including cross-region overlap, outliers, and per-SR metrics.
     */
    private AffinityAllocationMetrics metrics;
}
