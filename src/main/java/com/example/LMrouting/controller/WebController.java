package com.example.LMrouting.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @Value("${google.maps.api.key:}")
    private String apiKey;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("apiKey", apiKey);
        model.addAttribute("hubLat", hubLat);
        model.addAttribute("hubLng", hubLng);
        model.addAttribute("hubName", hubName);
        return "index";
    }
}
