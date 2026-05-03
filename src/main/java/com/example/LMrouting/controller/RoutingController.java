package com.example.LMrouting.controller;

import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OpenRouteService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Utility endpoints: config, hub info, dates.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoutingController {

    private final InMemoryStore store;
    private final GoogleMapsService googleMapsService;
    private final OpenRouteService openRouteService;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${google.maps.api.key:}")
    private String googleMapsApiKey;

    @GetMapping("/dates")
    public List<String> getDates() {
        return store.findAllDates();
    }

    /** Hub info — used by the UI header. */
    @GetMapping("/hub")
    public Map<String, Object> getHubInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("hubName", hubName);
        info.put("hubLat", hubLat);
        info.put("hubLng", hubLng);
        return info;
    }

    /** Config — routing mode and API key status. */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("googleMapsConfigured", googleMapsService.isApiKeyConfigured());
        config.put("orsConfigured", openRouteService.isConfigured());

        String mode;
        if (googleMapsService.isApiKeyConfigured() && openRouteService.isConfigured()) {
            mode = "Both Google Maps and ORS available";
        } else if (googleMapsService.isApiKeyConfigured()) {
            mode = "Google Maps (real roads)";
        } else if (openRouteService.isConfigured()) {
            mode = "OpenRouteService (real roads)";
        } else {
            mode = "Straight Lines (no routing API configured)";
        }
        config.put("routingMode", mode);

        long total = store.findAllDates().stream()
                .mapToLong(d -> store.findShipmentsByDate(d).size()).sum();
        config.put("totalShipments", total);

        // Expose Google Maps API key for frontend map tile rendering
        if (googleMapsService.isApiKeyConfigured()) {
            config.put("googleMapsApiKey", googleMapsApiKey);
        }
        return config;
    }
}
