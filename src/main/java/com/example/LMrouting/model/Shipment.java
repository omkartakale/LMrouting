package com.example.LMrouting.model;

import lombok.*;

/**
 * Shipment domain object — pure POJO, no JPA annotations.
 * All persistence is handled by InMemoryStore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    private Long id;
    private String shippingId;
    private String allocationDate;
    private String hubName;
    private String dropPincode;
    private String cityName;
    private String stateName;
    private String shipmentFlow;
    private int isHeavy;
    private double phyWeight;
    private double volWeight;
    private String orderType;
    private double dropLatitude;
    private double dropLongitude;
    private String clientId;
    private String srName;
    private int runNumber;
    private String shipmentConversion;
    private double rate;
    private double expectedPayout;

    // Shipment priority: P0 (factor 1.0), P1 (factor 0.75), P2 (factor 0.5)
    // Populated from the "Priority" column in the uploaded CSV.
    // Defaults to "P2" (lowest priority) if not present in the CSV.
    @Builder.Default
    private String priority = "P2";

    // Allocation output fields
    private String assignedSr;
    private int routeSequence;

    // Override tracking
    @Builder.Default
    private boolean isOverride = false;
    private String originalSr;

    // Out-of-range flag: true if shipment is beyond hub.max.distance.km
    // These shipments are included in allocation but shown differently on map
    @Builder.Default
    private boolean outOfRange = false;

    // Actual Haversine distance from hub (computed during ingestion)
    private double distanceFromHubKm;

    /**
     * Priority weight factor:
     *   P0 → 1.00 (full value)
     *   P1 → 0.75
     *   P2 → 0.50
     *   unknown → 1.00 (treat as P0)
     */
    public double priorityFactor() {
        if (priority == null) return 1.0;
        return switch (priority.trim().toUpperCase()) {
            case "P1" -> 0.75;
            case "P2" -> 0.50;
            default   -> 1.00; // P0 or unknown
        };
    }

    /**
     * Effective payout = expectedPayout × priorityFactor.
     * This is the value used by the allocation algorithm for fairness scoring.
     */
    public double effectivePayout() {
        return expectedPayout * priorityFactor();
    }
}
