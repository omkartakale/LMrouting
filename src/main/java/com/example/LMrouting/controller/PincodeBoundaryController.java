package com.example.LMrouting.controller;

import com.example.LMrouting.service.PincodeBoundaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/pincode-boundary")
@RequiredArgsConstructor
public class PincodeBoundaryController {

    private final PincodeBoundaryService pincodeBoundaryService;

    /** Get status and all pincode polygons for map rendering. */
    @GetMapping
    public Map<String, Object> getBoundaries() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaded", pincodeBoundaryService.isLoaded());
        result.put("pincodeCount", pincodeBoundaryService.getPincodeCount());
        result.put("pincodes", pincodeBoundaryService.getAllPincodes());
        result.put("polygons", pincodeBoundaryService.getAllPolygonsAsGeoJson());
        return result;
    }

    /** Get the exact GeoJSON polygon rings for a single pincode (for map highlighting). */
    @GetMapping("/{pincode}")
    public Map<String, Object> getPincodePolygon(@PathVariable String pincode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pincode", pincode);
        List<List<double[]>> rings = pincodeBoundaryService.getPolygonForPincode(pincode);
        // Convert [lng,lat] → [lat,lng] for Leaflet
        List<List<double[]>> leafletRings = new ArrayList<>();
        for (List<double[]> ring : rings) {
            List<double[]> leafletRing = new ArrayList<>();
            for (double[] coord : ring) {
                leafletRing.add(new double[]{coord[1], coord[0]}); // [lat, lng]
            }
            leafletRings.add(leafletRing);
        }
        result.put("rings", leafletRings);
        result.put("found", !rings.isEmpty());
        return result;
    }

    /** Check if a point is inside the service area. */
    @GetMapping("/check")
    public Map<String, Object> checkPoint(@RequestParam double lat, @RequestParam double lng) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lat", lat);
        result.put("lng", lng);
        result.put("insideServiceArea", pincodeBoundaryService.isInsideServiceArea(lat, lng));
        String pincode = pincodeBoundaryService.findPincodeForPoint(lat, lng);
        result.put("pincode", pincode);
        return result;
    }
}
