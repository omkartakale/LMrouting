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
}
