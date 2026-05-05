package com.example.LMrouting.service;

import com.example.LMrouting.dto.HubBoundaryResponse;
import com.example.LMrouting.model.AffinityRegion;
import com.example.LMrouting.model.DensityClassification;
import com.example.LMrouting.model.Shipment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RegionDensityAnalyzer analyzes shipment data to create affinity regions.
 * 
 * Core responsibilities:
 * - Group shipments by pincode to create regions
 * - Calculate geographic boundaries using bounding box
 * - Calculate area in square kilometers using Haversine formula
 * - Calculate shipment density (count / area)
 * - Classify regions as LOW_DENSITY (<40), MEDIUM_DENSITY (40-150), HIGH_DENSITY (>150)
 * - Filter regions to only include those within hub boundary
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 14.1, 14.2, 14.3
 */
@Service
@Slf4j
public class RegionDensityAnalyzer {

    private final HubBoundaryService hubBoundaryService;
    private final ObjectMapper objectMapper;
    private long regionIdSequence = 1L;

    public RegionDensityAnalyzer(HubBoundaryService hubBoundaryService) {
        this.hubBoundaryService = hubBoundaryService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Analyzes shipment data to create affinity regions with density metrics.
     * 
     * @param shipments List of shipments to analyze
     * @param hubName Hub name for boundary filtering
     * @return List of AffinityRegion objects, filtered to those within hub boundary
     */
    public List<AffinityRegion> analyzeRegionDensity(List<Shipment> shipments, String hubName) {
        if (shipments == null || shipments.isEmpty()) {
            log.info("RegionDensityAnalyzer: no shipments provided, returning empty list");
            return Collections.emptyList();
        }

        log.info("RegionDensityAnalyzer: analyzing {} shipments for hub '{}'", shipments.size(), hubName);

        // Step 1: Group shipments by pincode
        Map<String, List<Shipment>> shipmentsByPincode = groupShipmentsByPincode(shipments);
        log.info("RegionDensityAnalyzer: found {} unique pincodes", shipmentsByPincode.size());

        // Step 2: Create regions with geographic boundaries and density metrics
        List<AffinityRegion> regions = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : shipmentsByPincode.entrySet()) {
            String pincode = entry.getKey();
            List<Shipment> pincodeShipments = entry.getValue();

            AffinityRegion region = createRegion(pincode, pincodeShipments);
            if (region != null) {
                regions.add(region);
            }
        }

        log.info("RegionDensityAnalyzer: created {} regions before boundary filtering", regions.size());

        // Step 3: Filter regions to only include those within hub boundary
        List<AffinityRegion> filteredRegions = filterRegionsWithinHubBoundary(regions, hubName);
        log.info("RegionDensityAnalyzer: {} regions remain after hub boundary filtering", filteredRegions.size());

        return filteredRegions;
    }

    /**
     * Groups shipments by their pincode.
     * Skips shipments with null or empty pincodes.
     */
    private Map<String, List<Shipment>> groupShipmentsByPincode(List<Shipment> shipments) {
        return shipments.stream()
                .filter(s -> s.getDropPincode() != null && !s.getDropPincode().trim().isEmpty())
                .collect(Collectors.groupingBy(Shipment::getDropPincode));
    }

    /**
     * Creates an AffinityRegion from a pincode and its shipments.
     * Calculates geographic boundaries, area, density, and classification.
     * 
     * @param pincode The pincode for this region
     * @param shipments List of shipments in this pincode
     * @return AffinityRegion or null if region cannot be created
     */
    private AffinityRegion createRegion(String pincode, List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) {
            return null;
        }

        // Filter shipments with valid coordinates
        List<Shipment> validShipments = shipments.stream()
                .filter(s -> s.getDropLatitude() != 0.0 && s.getDropLongitude() != 0.0)
                .collect(Collectors.toList());

        if (validShipments.isEmpty()) {
            log.debug("RegionDensityAnalyzer: pincode '{}' has no shipments with valid coordinates, skipping", pincode);
            return null;
        }

        // Calculate bounding box
        BoundingBox bbox = calculateBoundingBox(validShipments);

        // Calculate geographic area in square kilometers
        double areaKm2 = calculateAreaKm2(bbox);

        // Ensure minimum area to avoid division by zero
        if (areaKm2 < 0.01) {
            areaKm2 = 0.01; // Minimum 0.01 km² (10,000 m²)
        }

        // Calculate density
        int shipmentCount = shipments.size(); // Use all shipments for count, not just valid coordinates
        double density = shipmentCount / areaKm2;

        // Classify density
        DensityClassification classification = classifyDensity(shipmentCount);

        // Create boundary coordinates JSON
        String boundaryCoordinates = createBoundaryCoordinatesJson(bbox);

        return AffinityRegion.builder()
                .id(regionIdSequence++)
                .pincode(pincode)
                .shipmentCount(shipmentCount)
                .geographicArea(areaKm2)
                .density(density)
                .densityClassification(classification)
                .boundaryCoordinates(boundaryCoordinates)
                .build();
    }

    /**
     * Calculates the bounding box (min/max lat/lng) for a list of shipments.
     */
    private BoundingBox calculateBoundingBox(List<Shipment> shipments) {
        double minLat = Double.MAX_VALUE;
        double maxLat = Double.MIN_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = Double.MIN_VALUE;

        for (Shipment s : shipments) {
            double lat = s.getDropLatitude();
            double lng = s.getDropLongitude();

            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLng = Math.min(minLng, lng);
            maxLng = Math.max(maxLng, lng);
        }

        return new BoundingBox(minLat, maxLat, minLng, maxLng);
    }

    /**
     * Calculates the area of a bounding box in square kilometers using Haversine formula.
     * 
     * The area is approximated as:
     * - Width (km) = Haversine distance between (centerLat, minLng) and (centerLat, maxLng)
     * - Height (km) = Haversine distance between (minLat, centerLng) and (maxLat, centerLng)
     * - Area = Width × Height
     * 
     * This is an approximation that works well for small regions.
     */
    private double calculateAreaKm2(BoundingBox bbox) {
        double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
        double centerLng = (bbox.minLng + bbox.maxLng) / 2.0;

        // Calculate width at center latitude
        double widthKm = GoogleMapsService.haversine(centerLat, bbox.minLng, centerLat, bbox.maxLng);

        // Calculate height at center longitude
        double heightKm = GoogleMapsService.haversine(bbox.minLat, centerLng, bbox.maxLat, centerLng);

        return widthKm * heightKm;
    }

    /**
     * Classifies region density based on shipment count thresholds.
     * - LOW_DENSITY: < 40 shipments
     * - MEDIUM_DENSITY: 40-150 shipments
     * - HIGH_DENSITY: > 150 shipments
     */
    private DensityClassification classifyDensity(int shipmentCount) {
        if (shipmentCount < 40) {
            return DensityClassification.LOW_DENSITY;
        } else if (shipmentCount <= 150) {
            return DensityClassification.MEDIUM_DENSITY;
        } else {
            return DensityClassification.HIGH_DENSITY;
        }
    }

    /**
     * Creates a JSON string representing the boundary coordinates.
     * Format: [[lat1,lng1],[lat2,lng2],[lat3,lng3],[lat4,lng4],[lat1,lng1]]
     * (closed polygon with 5 points - 4 corners + closing point)
     */
    private String createBoundaryCoordinatesJson(BoundingBox bbox) {
        List<double[]> coordinates = Arrays.asList(
                new double[]{bbox.minLat, bbox.minLng}, // Bottom-left
                new double[]{bbox.minLat, bbox.maxLng}, // Bottom-right
                new double[]{bbox.maxLat, bbox.maxLng}, // Top-right
                new double[]{bbox.maxLat, bbox.minLng}, // Top-left
                new double[]{bbox.minLat, bbox.minLng}  // Close the polygon
        );

        try {
            return objectMapper.writeValueAsString(coordinates);
        } catch (JsonProcessingException e) {
            log.warn("RegionDensityAnalyzer: failed to serialize boundary coordinates: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Filters regions to only include those within the hub boundary.
     *
     * A region is considered within the hub boundary if:
     * 1. Hub boundary is available AND
     * 2. The region's bounding box overlaps with the hub boundary polygon
     *    (any corner inside, OR the region center inside, OR the hub centroid inside the region)
     *
     * If hub boundary is not available, OR if filtering would remove ALL regions
     * (which indicates a coordinate mismatch rather than truly out-of-boundary data),
     * all regions are returned unfiltered.
     */
    private List<AffinityRegion> filterRegionsWithinHubBoundary(List<AffinityRegion> regions, String hubName) {
        // Fetch hub boundary
        HubBoundaryResponse hubBoundary = hubBoundaryService.fetchBoundary(hubName);

        if (hubBoundary == null || hubBoundary.coordinates() == null || hubBoundary.coordinates().isEmpty()) {
            log.info("RegionDensityAnalyzer: hub boundary not available for '{}', skipping boundary filtering", hubName);
            return regions;
        }

        log.info("RegionDensityAnalyzer: filtering regions using hub boundary with {} points",
                 hubBoundary.coordinates().size());

        // Filter regions
        List<AffinityRegion> filtered = regions.stream()
                .filter(region -> isRegionWithinHubBoundary(region, hubBoundary.coordinates()))
                .collect(Collectors.toList());

        // Safety fallback: if boundary filtering removed ALL regions, the shipment coordinates
        // likely don't align with the stored hub boundary polygon (e.g. different CRS, slight
        // offset, or boundary data mismatch). Return all regions so the user can still work.
        if (filtered.isEmpty() && !regions.isEmpty()) {
            log.warn("RegionDensityAnalyzer: hub boundary filtering removed all {} regions for hub '{}'. " +
                     "Returning all regions unfiltered to avoid empty result. " +
                     "Check that shipment coordinates match the hub boundary polygon.",
                     regions.size(), hubName);
            return regions;
        }

        return filtered;
    }

    /**
     * Checks if a region intersects with the hub boundary.
     *
     * Strategy (most-lenient-first):
     * 1. Any corner of the region bounding box is inside the hub polygon
     * 2. The region center is inside the hub polygon
     * 3. The hub polygon centroid is inside the region bounding box
     *    (handles the case where the hub is large and fully contains the region)
     */
    private boolean isRegionWithinHubBoundary(AffinityRegion region, List<double[]> hubBoundary) {
        // Parse region boundary coordinates
        List<double[]> regionCorners = parseBoundaryCoordinates(region.getBoundaryCoordinates());
        if (regionCorners.isEmpty()) {
            log.debug("RegionDensityAnalyzer: region '{}' has no valid boundary coordinates, excluding",
                      region.getPincode());
            return false;
        }

        // Check 1: any corner of the region is inside the hub polygon
        for (double[] corner : regionCorners) {
            if (isPointInPolygon(corner[0], corner[1], hubBoundary)) {
                return true;
            }
        }

        // Check 2: region center is inside the hub polygon
        double centerLat = regionCorners.stream().mapToDouble(c -> c[0]).average().orElse(0.0);
        double centerLng = regionCorners.stream().mapToDouble(c -> c[1]).average().orElse(0.0);
        if (isPointInPolygon(centerLat, centerLng, hubBoundary)) {
            return true;
        }

        // Check 3: hub polygon centroid is inside the region bounding box
        // (covers the case where the hub boundary is large and the region is fully inside)
        double hubCentroidLat = hubBoundary.stream().mapToDouble(p -> p[0]).average().orElse(0.0);
        double hubCentroidLng = hubBoundary.stream().mapToDouble(p -> p[1]).average().orElse(0.0);
        double minLat = regionCorners.stream().mapToDouble(c -> c[0]).min().orElse(0.0);
        double maxLat = regionCorners.stream().mapToDouble(c -> c[0]).max().orElse(0.0);
        double minLng = regionCorners.stream().mapToDouble(c -> c[1]).min().orElse(0.0);
        double maxLng = regionCorners.stream().mapToDouble(c -> c[1]).max().orElse(0.0);
        if (hubCentroidLat >= minLat && hubCentroidLat <= maxLat &&
                hubCentroidLng >= minLng && hubCentroidLng <= maxLng) {
            return true;
        }

        return false;
    }

    /**
     * Parses boundary coordinates JSON string into a list of [lat, lng] arrays.
     */
    private List<double[]> parseBoundaryCoordinates(String boundaryJson) {
        try {
            double[][] coords = objectMapper.readValue(boundaryJson, double[][].class);
            return Arrays.asList(coords);
        } catch (JsonProcessingException e) {
            log.debug("RegionDensityAnalyzer: failed to parse boundary coordinates: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Ray casting algorithm to determine if a point is inside a polygon.
     * 
     * @param lat Point latitude
     * @param lng Point longitude
     * @param polygon List of [lat, lng] coordinates defining the polygon
     * @return true if point is inside polygon, false otherwise
     */
    private boolean isPointInPolygon(double lat, double lng, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }

        boolean inside = false;
        int n = polygon.size();

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double latI = polygon.get(i)[0];
            double lngI = polygon.get(i)[1];
            double latJ = polygon.get(j)[0];
            double lngJ = polygon.get(j)[1];

            // Ray casting algorithm
            if ((lngI > lng) != (lngJ > lng) &&
                    lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * Simple record to hold bounding box coordinates.
     */
    private record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {}
}
