package com.example.LMrouting.dto;

import lombok.*;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResponse {
    private String srName;
    private int totalShipments;
    private double totalDistanceKm;
    private double totalDurationMinutes;
    private List<ShipmentStop> stops;
    private List<LatLng> polylinePoints;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ShipmentStop {
        private int sequence;
        private String shippingId;
        private String dropPincode;
        private double latitude;
        private double longitude;
        private String orderType;
        private double weight;
        private String clientId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatLng {
        private double lat;
        private double lng;
    }
}
