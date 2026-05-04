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
