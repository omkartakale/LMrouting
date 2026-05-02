package com.example.LMrouting.dto;

import java.util.List;

/**
 * Per-SR summary included in AllocationSummary.
 */
public record SrSummaryDto(
        String srName,
        int shipmentCount,
        int heavyShipmentCount,
        double compositeLoadScore,
        double estimatedDistanceKm,
        List<String> pincodesCovered
) {}
