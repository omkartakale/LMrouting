package com.example.LMrouting.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "shipments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipping_id")
    private String shippingId;

    @Column(name = "allocation_date")
    private String allocationDate;

    @Column(name = "hub_name")
    private String hubName;

    @Column(name = "drop_pincode")
    private String dropPincode;

    @Column(name = "city_name")
    private String cityName;

    @Column(name = "state_name")
    private String stateName;

    @Column(name = "shipment_flow")
    private String shipmentFlow;

    @Column(name = "is_heavy")
    private int isHeavy;

    @Column(name = "phy_weight")
    private double phyWeight;

    @Column(name = "vol_weight")
    private double volWeight;

    @Column(name = "order_type")
    private String orderType;

    @Column(name = "drop_latitude")
    private double dropLatitude;

    @Column(name = "drop_longitude")
    private double dropLongitude;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "sr_name")
    private String srName;

    @Column(name = "run_number")
    private int runNumber;

    @Column(name = "shipment_conversion")
    private String shipmentConversion;

    @Column(name = "rate")
    private double rate;

    @Column(name = "expected_payout")
    private double expectedPayout;

    // Assigned SR and route
    @Column(name = "assigned_sr")
    private String assignedSr;

    @Column(name = "route_sequence")
    private int routeSequence;
}
