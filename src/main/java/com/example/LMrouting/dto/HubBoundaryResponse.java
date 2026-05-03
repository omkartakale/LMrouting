package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response DTO returned by /api/hub/boundary.
 *
 * coordinates:        facility.boundary.geometry.coordinates[0] — the hub's own service boundary
 *                     Already swapped from GeoJSON [lng,lat] → [lat,lng] for Leaflet.
 *
 * originalBoundary:   facility.originalBoundary.geometry.coordinates[0] — the broader/admin boundary
 *                     Also swapped to [lat,lng]. May be null if not present in API response.
 *
 * facilityName:       The actual name of the matched facility from the API (for debugging).
 */
public record HubBoundaryResponse(
        String hubName,
        String facilityName,
        List<double[]> coordinates,
        List<double[]> originalBoundary
) {}
