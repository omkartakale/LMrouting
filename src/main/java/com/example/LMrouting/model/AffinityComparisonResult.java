package com.example.LMrouting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AffinityComparisonResult holds side-by-side comparison of standard mode and affinity mode allocations.
 * 
 * This class contains:
 * - Earnings distribution for both modes
 * - Earnings variance for both modes
 * - Average distance per SR for both modes
 * - Differences in SR assignments
 * - Percentage improvement/degradation in key metrics
 * 
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffinityComparisonResult {

    /**
     * Standard mode allocation metrics.
     */
    private AllocationMetrics standardMode;

    /**
     * Affinity mode allocation metrics.
     */
    private AllocationMetrics affinityMode;

    /**
     * Percentage improvement/degradation in key metrics.
     * Positive values indicate affinity mode is better, negative values indicate standard mode is better.
     */
    private MetricComparison comparison;

    /**
     * Differences in SR assignments between modes.
     * Map key: SR name
     * Map value: Assignment difference details
     */
    private Map<String, SrAssignmentDifference> assignmentDifferences;

    /**
     * AllocationMetrics holds metrics for a single allocation mode.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationMetrics {
        /**
         * Earnings distribution across SRs.
         * Map key: SR name
         * Map value: Net earnings
         */
        private Map<String, Double> earningsDistribution;

        /**
         * Earnings variance across all SRs.
         */
        private double earningsVariance;

        /**
         * Average distance per SR in kilometers.
         */
        private double averageDistancePerSr;

        /**
         * Total shipments allocated.
         */
        private int totalShipments;

        /**
         * Total SRs in allocation.
         */
        private int totalSrs;

        /**
         * Average shipments per SR.
         */
        private double averageShipmentsPerSr;

        /**
         * Minimum shipments per SR.
         */
        private int minShipmentsPerSr;

        /**
         * Maximum shipments per SR.
         */
        private int maxShipmentsPerSr;

        /**
         * Mean net earnings across SRs.
         */
        private double meanNetEarnings;

        /**
         * Earnings range (max - min) across SRs.
         */
        private double earningsRange;
    }

    /**
     * MetricComparison holds percentage improvement/degradation in key metrics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricComparison {
        /**
         * Percentage change in earnings variance.
         * Negative values indicate improvement (lower variance in affinity mode).
         */
        private double earningsVarianceChange;

        /**
         * Percentage change in average distance per SR.
         * Negative values indicate improvement (shorter routes in affinity mode).
         */
        private double averageDistanceChange;

        /**
         * Percentage change in earnings range.
         * Negative values indicate improvement (smaller range in affinity mode).
         */
        private double earningsRangeChange;

        /**
         * Number of SRs with improved earnings in affinity mode.
         */
        private int srsWithImprovedEarnings;

        /**
         * Number of SRs with degraded earnings in affinity mode.
         */
        private int srsWithDegradedEarnings;

        /**
         * Number of SRs with unchanged earnings in affinity mode.
         */
        private int srsWithUnchangedEarnings;
    }

    /**
     * SrAssignmentDifference holds differences in SR assignments between modes.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SrAssignmentDifference {
        /**
         * SR name.
         */
        private String srName;

        /**
         * Shipment count in standard mode.
         */
        private int standardModeShipmentCount;

        /**
         * Shipment count in affinity mode.
         */
        private int affinityModeShipmentCount;

        /**
         * Net earnings in standard mode.
         */
        private double standardModeEarnings;

        /**
         * Net earnings in affinity mode.
         */
        private double affinityModeEarnings;

        /**
         * Distance in standard mode (km).
         */
        private double standardModeDistance;

        /**
         * Distance in affinity mode (km).
         */
        private double affinityModeDistance;

        /**
         * Pincodes covered in standard mode.
         */
        private List<String> standardModePincodes;

        /**
         * Pincodes covered in affinity mode.
         */
        private List<String> affinityModePincodes;

        /**
         * Pincodes unique to standard mode (not in affinity mode).
         */
        private List<String> pincodesOnlyInStandard;

        /**
         * Pincodes unique to affinity mode (not in standard mode).
         */
        private List<String> pincodesOnlyInAffinity;

        /**
         * Pincodes common to both modes.
         */
        private List<String> pincodesInBoth;
    }
}
