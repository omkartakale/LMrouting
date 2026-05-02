package com.example.LMrouting.controller;

import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OpenRouteService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Legacy routing controller — kept for backward compatibility.
 * New allocation endpoints are in AllocationController.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoutingController {

    private final InMemoryStore store;
    private final GoogleMapsService googleMapsService;
    private final OpenRouteService openRouteService;

    @GetMapping("/dates")
    public List<String> getDates() {
        return store.findAllDates();
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("googleMapsConfigured", googleMapsService.isApiKeyConfigured());
        config.put("orsConfigured", openRouteService.isConfigured());
        config.put("routingMode", openRouteService.isConfigured() ? "OpenRouteService (real roads)" :
                googleMapsService.isApiKeyConfigured() ? "Google Maps (real roads)" :
                "Nearest-neighbor (straight lines)");
        long total = store.findAllDates().stream()
                .mapToLong(d -> store.findShipmentsByDate(d).size()).sum();
        config.put("totalShipments", total);
        return config;
    }
}
