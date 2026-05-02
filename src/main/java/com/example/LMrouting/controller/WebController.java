package com.example.LMrouting.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Provides hub configuration to the frontend via API.
 * The frontend is served as a static HTML file from /static/index.html.
 */
@RestController
public class WebController {

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @GetMapping("/api/hub")
    public Map<String, Object> getHubConfig() {
        return Map.of(
            "hubName", hubName,
            "hubLat", hubLat,
            "hubLng", hubLng
        );
    }
}
