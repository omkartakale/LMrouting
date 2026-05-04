package com.example.LMrouting.model;

import lombok.*;

/**
 * AffinityRegion represents a geographic territory defined by a unique pincode.
 * It stores shipment density metrics and geographic boundaries used for SR allocation.
 * 
 * The density classification determines SR allocation strategies:
 * - LOW_DENSITY regions may be merged with adjacent regions
 * - HIGH_DENSITY regions may be subdivided into multiple SR territories
 * 
 * Pure POJO, no JPA annotations. All persistence is handled by InMemoryStore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffinityRegion {
    
    /**
     * Unique identifier for the affinity region
     */
    private Long id;
    
    /**
     * The pincode that defines this region
     */
    private String pincode;
    
    /**
     * Total number of shipments in this region
     */
    private int shipmentCount;
    
    /**
     * Geographic area of the region in square kilometers
     */
    private double geographicArea;
    
    /**
     * Shipment density (shipments per square kilometer)
     * Calculated as: shipmentCount / geographicArea
     */
    private double density;
    
    /**
     * Classification based on shipment count thresholds:
     * - LOW_DENSITY: < 40 shipments
     * - MEDIUM_DENSITY: 40-150 shipments
     * - HIGH_DENSITY: > 150 shipments
     */
    private DensityClassification densityClassification;
    
    /**
     * Geographic boundary coordinates defining the region's perimeter.
     * Stored as a JSON string representing an array of [latitude, longitude] pairs.
     * Example: "[[12.9716,77.5946],[12.9800,77.6000],...]"
     */
    private String boundaryCoordinates;
}
