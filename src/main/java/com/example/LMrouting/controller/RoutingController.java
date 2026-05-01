package com.example.LMrouting.controller;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.repository.ShipmentRepository;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.RoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routingService;
    private final ShipmentRepository shipmentRepository;
    private final GoogleMapsService googleMapsService;

    /**
     * Run routing algorithm for a given date and SR count.
     * POST /api/route?date=24-Mar-26&srCount=10
     */
    @PostMapping("/route")
    public RoutingSummary runRouting(
            @RequestParam(defaultValue = "24-Mar-26") String date,
            @RequestParam(defaultValue = "10") int srCount) {
        return routingService.runRouting(date, srCount);
    }

    /**
     * Get optimized route for a specific SR.
     * GET /api/route/{srName}
     */
    @GetMapping("/route/{srName}")
    public RouteResponse getRoute(@PathVariable String srName) {
        return routingService.getRouteForSR(srName);
    }

    /**
     * Get all available dates.
     */
    @GetMapping("/dates")
    public List<String> getDates() {
        return shipmentRepository.findDistinctAllocationDates();
    }

    /**
     * Get all assigned SRs.
     */
    @GetMapping("/srs")
    public List<String> getSRs() {
        return shipmentRepository.findDistinctAssignedSrs();
    }

    /**
     * Get pincodes for a date.
     */
    @GetMapping("/pincodes")
    public List<String> getPincodes(@RequestParam(defaultValue = "24-Mar-26") String date) {
        return shipmentRepository.findDistinctPincodesByDate(date);
    }

    /**
     * Check if Google Maps API is configured.
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("googleMapsConfigured", googleMapsService.isApiKeyConfigured());
        config.put("totalShipments", shipmentRepository.count());
        return config;
    }
}
