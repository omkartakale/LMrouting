package com.example.LMrouting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AffinityAllocationPreview holds the preview result of affinity-based allocation.
 * 
 * This class is used for preview mode (non-persistent allocation execution) and contains:
 * - Expected earnings per SR
 * - Shipment count per SR
 * - Earnings variance
 * - Cross-region percentage
 * - Warnings for SRs with earnings significantly above/below average (>20% deviation)
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffinityAllocationPreview {

    /**
     * Map of SR name to expected earnings.
     */
    private Map<String, Double> earningsPerSr;

    /**
     * Map of SR name to shipment count.
     */
    private Map<String, Integer> shipmentCountPerSr;

    /**
     * Earnings variance across all SRs.
     */
    private double earningsVariance;

    /**
     * Percentage of shipments assigned outside their affinity regions.
     */
    private double crossRegionPercentage;

    /**
     * Percentage of shipments assigned within their affinity regions.
     */
    private double withinRegionPercentage;

    /**
     * List of warnings for SRs with earnings significantly above/below average.
     * Each warning contains the SR name and the deviation percentage.
     */
    private List<EarningsWarning> warnings;

    /**
     * Total number of shipments in the allocation.
     */
    private int totalShipments;

    /**
     * Total number of SRs in the allocation.
     */
    private int totalSrs;

    /**
     * Average shipments per SR.
     */
    private double averageShipmentsPerSr;

    /**
     * EarningsWarning represents a warning for an SR with earnings significantly
     * above or below the average.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EarningsWarning {
        /**
         * SR name.
         */
        private String srName;

        /**
         * Expected earnings for this SR.
         */
        private double earnings;

        /**
         * Average earnings across all SRs.
         */
        private double averageEarnings;

        /**
         * Deviation from average as a percentage.
         * Positive values indicate above average, negative values indicate below average.
         */
        private double deviationPercentage;

        /**
         * Warning message describing the deviation.
         */
        private String message;
    }
}
