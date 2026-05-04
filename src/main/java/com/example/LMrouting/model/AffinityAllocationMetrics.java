package com.example.LMrouting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AffinityAllocationMetrics holds allocation metrics for affinity-based allocation.
 * 
 * This class contains metrics calculated after allocation execution including:
 * - Cross-region overlap percentage
 * - List of outlier SRs (>30% cross-region assignments)
 * - Within-region assignment percentage
 * - Per-SR metrics (shipment count, earnings, cross-region percentage)
 * 
 * Requirements: 15.4, 15.5, 30.1, 33.1, 34.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffinityAllocationMetrics {

    /**
     * Cross-region overlap percentage (0-100).
     * Represents the percentage of shipments assigned outside their SR's affinity region.
     */
    private double crossRegionOverlapPercentage;

    /**
     * Within-region assignment percentage (0-100).
     * Represents the percentage of shipments assigned within their SR's affinity region.
     */
    private double withinRegionPercentage;

    /**
     * List of SR names flagged as outliers (>30% cross-region assignments).
     */
    private List<String> outlierSrs;

    /**
     * Per-SR metrics including shipment count, earnings, and cross-region percentage.
     * Map key: SR name
     * Map value: SrMetrics object
     */
    private Map<String, SrMetrics> perSrMetrics;

    /**
     * Total number of shipments allocated.
     */
    private int totalShipments;

    /**
     * Total number of SRs in the allocation.
     */
    private int totalSrs;

    /**
     * Earnings variance across all SRs.
     */
    private double earningsVariance;

    /**
     * Average shipments per SR.
     */
    private double averageShipmentsPerSr;

    /**
     * SrMetrics holds per-SR allocation metrics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SrMetrics {
        /**
         * SR name.
         */
        private String srName;

        /**
         * Number of shipments assigned to this SR.
         */
        private int shipmentCount;

        /**
         * Expected earnings for this SR.
         */
        private double earnings;

        /**
         * Percentage of shipments from outside the SR's affinity region (0-100).
         */
        private double crossRegionPercentage;

        /**
         * Number of shipments from outside the SR's affinity region.
         */
        private int crossRegionShipmentCount;

        /**
         * Number of shipments from within the SR's affinity region.
         */
        private int withinRegionShipmentCount;

        /**
         * Estimated route distance in kilometers.
         */
        private double routeDistanceKm;
    }
}
