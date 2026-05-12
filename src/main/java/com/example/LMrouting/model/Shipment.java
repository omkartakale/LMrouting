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

    // ── Priority tier (P0/P1/P2) ─────────────────────────────────────────────
    // Read from the CSV "Priority" column (case-insensitive). Defaults to P2
    // when the column is absent or the value cannot be parsed. The priority
    // factor (P0=1.00, P1=0.75, P2=0.50) weights the effective payout used by
    // the allocation engine when capacity is exceeded and during fairness
    // rebalancing — higher priority shipments are retained preferentially.
    @Builder.Default
    private Priority priority = Priority.P2;

    /**
     * Effective payout = expectedPayout × priority factor.
     *
     * <p>This is the value the allocation engine uses to (a) sort shipments
     * when capacity is exceeded, and (b) compute gross payout during fairness
     * rebalancing. P0 shipments retain 100% of their payout, P1 retain 75%,
     * and P2 retain 50% — so the engine naturally prefers retaining P0s.
     */
    public double effectivePayout() {
        Priority p = priority == null ? Priority.P2 : priority;
        return expectedPayout * p.getFactor();
    }
}
