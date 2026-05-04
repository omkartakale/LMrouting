package com.example.LMrouting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * Loads pincode boundary polygons from a GeoJSON file at startup.
 * Provides point-in-polygon checks to determine if a shipment's
 * drop location falls within a known pincode boundary near the hub.
 *
 * Used by the allocation engine to filter shipments that are
 * outside the hub's serviceable pincode areas.
 */
@Service
@Slf4j
public class PincodeBoundaryService {

    @Value("${hub.pincode.boundary.file:pune-pincode-boundaries.geojson}")
    private String boundaryFile;

    // pincode → list of polygon rings (each ring = list of [lng, lat] pairs)
    private final Map<String, List<List<double[]>>> pincodePolygons = new LinkedHashMap<>();

    // All polygon rings flattened for point-in-any-polygon check
    private final List<PincodePolygon> allPolygons = new ArrayList<>();

    private boolean loaded = false;

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource(boundaryFile);
            if (!resource.exists()) {
                log.warn("Pincode boundary file not found: {}. Boundary filtering disabled.", boundaryFile);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            InputStream is = resource.getInputStream();
            JsonNode root = mapper.readTree(is);
            JsonNode features = root.get("features");

            if (features == null || !features.isArray()) {
                log.warn("No features in pincode boundary file.");
                return;
            }

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");
                JsonNode geom = feature.get("geometry");
                if (props == null || geom == null) continue;

                String pincode = props.has("Pincode") ? props.get("Pincode").asText() : null;
                if (pincode == null || pincode.isBlank()) continue;

                String geomType = geom.has("type") ? geom.get("type").asText() : "";
                JsonNode coordinates = geom.get("coordinates");
                if (coordinates == null) continue;

                List<List<double[]>> rings = new ArrayList<>();

                if ("Polygon".equals(geomType)) {
                    // coordinates = [ [ [lng,lat], [lng,lat], ... ] ]
                    for (JsonNode ring : coordinates) {
                        List<double[]> points = parseRing(ring);
                        if (!points.isEmpty()) rings.add(points);
                    }
                } else if ("MultiPolygon".equals(geomType)) {
                    // coordinates = [ [ [ [lng,lat], ... ] ], [ [ [lng,lat], ... ] ] ]
                    for (JsonNode polygon : coordinates) {
                        for (JsonNode ring : polygon) {
                            List<double[]> points = parseRing(ring);
                            if (!points.isEmpty()) rings.add(points);
                        }
                    }
                }

                if (!rings.isEmpty()) {
                    pincodePolygons.put(pincode, rings);
                    for (List<double[]> ring : rings) {
                        allPolygons.add(new PincodePolygon(pincode, ring));
                    }
                }
            }

            loaded = true;
            log.info("PincodeBoundaryService: loaded {} pincodes with {} polygon rings from {}",
                    pincodePolygons.size(), allPolygons.size(), boundaryFile);

        } catch (Exception e) {
            log.error("Failed to load pincode boundaries: {}", e.getMessage());
        }
    }

    /**
     * Check if a lat/lng point falls inside any known pincode polygon.
     * Returns the pincode if found, null otherwise.
     */
    public String findPincodeForPoint(double lat, double lng) {
        if (!loaded) return null;
        for (PincodePolygon pp : allPolygons) {
            if (pointInPolygon(lat, lng, pp.ring)) {
                return pp.pincode;
            }
        }
        return null;
    }

    /**
     * Check if a point is inside the hub's serviceable area
     * (i.e., inside ANY of the loaded pincode polygons).
     */
    public boolean isInsideServiceArea(double lat, double lng) {
        if (!loaded) return true; // if not loaded, don't filter
        return findPincodeForPoint(lat, lng) != null;
    }

    /**
     * Get all loaded pincode codes.
     */
    public Set<String> getAllPincodes() {
        return Collections.unmodifiableSet(pincodePolygons.keySet());
    }

    /**
     * Get polygon rings for a specific pincode.
     */
    public List<List<double[]>> getPolygonForPincode(String pincode) {
        return pincodePolygons.getOrDefault(pincode, List.of());
    }

    /**
     * Get all polygons as GeoJSON-compatible structure for frontend rendering.
     */
    public List<Map<String, Object>> getAllPolygonsAsGeoJson() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<List<double[]>>> entry : pincodePolygons.entrySet()) {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("pincode", entry.getKey());
            // Convert rings to [lat, lng] for Leaflet
            List<List<double[]>> leafletRings = new ArrayList<>();
            for (List<double[]> ring : entry.getValue()) {
                List<double[]> leafletRing = new ArrayList<>();
                for (double[] coord : ring) {
                    leafletRing.add(new double[]{coord[1], coord[0]}); // [lat, lng]
                }
                leafletRings.add(leafletRing);
            }
            feature.put("rings", leafletRings);
            result.add(feature);
        }
        return result;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int getPincodeCount() {
        return pincodePolygons.size();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private List<double[]> parseRing(JsonNode ring) {
        List<double[]> points = new ArrayList<>();
        if (ring == null || !ring.isArray()) return points;
        for (JsonNode coord : ring) {
            if (coord.isArray() && coord.size() >= 2) {
                double lng = coord.get(0).asDouble();
                double lat = coord.get(1).asDouble();
                points.add(new double[]{lng, lat});
            }
        }
        return points;
    }

    /**
     * Ray-casting point-in-polygon algorithm.
     * Coordinates in ring are [lng, lat].
     */
    private boolean pointInPolygon(double lat, double lng, List<double[]> ring) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = ring.get(i)[1]; // lat
            double xi = ring.get(i)[0]; // lng
            double yj = ring.get(j)[1];
            double xj = ring.get(j)[0];

            if ((yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private record PincodePolygon(String pincode, List<double[]> ring) {}
}
