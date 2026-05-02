package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response for GET /api/allocate/{date}/sr/{srName}.
 */
public record SrRouteDto(
        String srName,
        String date,
        int totalShipments,
        double estimatedDistanceKm,
        List<ShipmentStopDto> stops,
        List<List<Double>> routeGeometry
) {
    /** Convenience constructor without routeGeometry (defaults to null). */
    public SrRouteDto(String srName, String date, int totalShipments,
                      double estimatedDistanceKm, List<ShipmentStopDto> stops) {
        this(srName, date, totalShipments, estimatedDistanceKm, stops, null);
    }
}
