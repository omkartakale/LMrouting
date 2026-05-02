package com.example.LMrouting.dto;

/**
 * A single stop in an SR's route.
 */
public record ShipmentStopDto(
        int sequence,
        String shippingId,
        String dropPincode,
        double latitude,
        double longitude,
        String orderType,
        double phyWeight,
        boolean isHeavy,
        String shipmentFlow,
        boolean isOverride,
        boolean outOfRange
) {}
