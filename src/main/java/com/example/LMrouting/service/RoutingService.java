package com.example.LMrouting.service;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core routing logic:
 * 1. Groups shipments by pincode clusters
 * 2. Assigns shipments to SRs using geographic clustering (nearest-neighbor)
 * 3. Optimizes each SR's route using Google Maps Directions API
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingService {

    private final ShipmentRepository shipmentRepository;
    private final GoogleMapsService googleMapsService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    /**
     * Run the routing algorithm for a given date.
     * Assigns shipments to SRs and optimizes routes.
     */
    public RoutingSummary runRouting(String date, int srCount) {
        List<Shipment> shipments = shipmentRepository.findByAllocationDate(date);
        if (shipments.isEmpty()) {
            // If no date filter, use all shipments
            shipments = shipmentRepository.findAll();
        }

        // Filter out shipments with invalid coordinates
        shipments = shipments.stream()
                .filter(s -> s.getDropLatitude() > 0 && s.getDropLongitude() > 0)
                .filter(s -> isWithinServiceArea(s.getDropLatitude(), s.getDropLongitude()))
                .collect(Collectors.toList());

        log.info("Routing {} shipments across {} SRs", shipments.size(), srCount);

        // Get unique SR names from data, or generate them
        List<String> srNames = getOrGenerateSRNames(shipments, srCount);

        // Assign shipments to SRs using geographic clustering
        Map<String, List<Shipment>> srAssignments = assignShipmentsToSRs(shipments, srNames);

        // Save assignments
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> assigned = entry.getValue();
            for (int i = 0; i < assigned.size(); i++) {
                assigned.get(i).setAssignedSr(sr);
                assigned.get(i).setRouteSequence(i + 1);
            }
        }
        shipmentRepository.saveAll(shipments);

        // Build summary
        List<String> pincodes = shipments.stream()
                .map(Shipment::getDropPincode)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<RoutingSummary.SRSummary> srSummaries = srAssignments.entrySet().stream()
                .map(e -> RoutingSummary.SRSummary.builder()
                        .srName(e.getKey())
                        .shipmentCount(e.getValue().size())
                        .pincodesCovered(e.getValue().stream()
                                .map(Shipment::getDropPincode)
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList()))
                        .build())
                .sorted(Comparator.comparing(RoutingSummary.SRSummary::getSrName))
                .collect(Collectors.toList());

        return RoutingSummary.builder()
                .hubName(hubName)
                .hubLatitude(hubLat)
                .hubLongitude(hubLng)
                .allocationDate(date)
                .totalShipments(shipments.size())
                .totalSRs(srNames.size())
                .pincodes(pincodes)
                .srSummaries(srSummaries)
                .build();
    }

    /**
     * Get optimized route for a specific SR.
     */
    public RouteResponse getRouteForSR(String srName) {
        List<Shipment> shipments = shipmentRepository.findByAssignedSrOrderByRouteSequence(srName);
        if (shipments.isEmpty()) {
            return RouteResponse.builder()
                    .srName(srName)
                    .totalShipments(0)
                    .stops(List.of())
                    .polylinePoints(List.of())
                    .build();
        }

        // Build waypoints
        List<double[]> waypoints = shipments.stream()
                .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                .collect(Collectors.toList());

        // Get optimized route from Google Maps (or fallback)
        GoogleMapsService.DirectionsResult directions =
                googleMapsService.getOptimizedRoute(hubLat, hubLng, waypoints);

        // Reorder shipments based on optimized waypoint order
        List<RouteResponse.ShipmentStop> stops = new ArrayList<>();
        List<Integer> order = directions.waypointOrder();

        if (order != null && !order.isEmpty() && order.size() == shipments.size()) {
            for (int i = 0; i < order.size(); i++) {
                Shipment s = shipments.get(order.get(i));
                stops.add(buildStop(i + 1, s));
            }
        } else {
            // Use existing order
            for (int i = 0; i < shipments.size(); i++) {
                stops.add(buildStop(i + 1, shipments.get(i)));
            }
        }

        return RouteResponse.builder()
                .srName(srName)
                .totalShipments(shipments.size())
                .totalDistanceKm(Math.round(directions.totalDistanceKm() * 100.0) / 100.0)
                .totalDurationMinutes(Math.round(directions.totalDurationMinutes() * 10.0) / 10.0)
                .stops(stops)
                .polylinePoints(directions.polylinePoints())
                .build();
    }

    /**
     * Assign shipments to SRs using K-means-like geographic clustering.
     * Uses angular sectors from hub to distribute shipments geographically.
     */
    private Map<String, List<Shipment>> assignShipmentsToSRs(List<Shipment> shipments,
                                                              List<String> srNames) {
        int k = srNames.size();
        if (k == 0 || shipments.isEmpty()) return Map.of();

        // Calculate angle from hub for each shipment
        List<ShipmentWithAngle> withAngles = shipments.stream()
                .map(s -> new ShipmentWithAngle(s,
                        Math.atan2(s.getDropLongitude() - hubLng, s.getDropLatitude() - hubLat)))
                .sorted(Comparator.comparingDouble(a -> a.angle))
                .collect(Collectors.toList());

        // Divide into k roughly equal angular sectors
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        int shipmentsPerSR = shipments.size() / k;
        int remainder = shipments.size() % k;

        int idx = 0;
        for (int i = 0; i < k; i++) {
            String sr = srNames.get(i);
            int count = shipmentsPerSR + (i < remainder ? 1 : 0);
            List<Shipment> srShipments = new ArrayList<>();
            for (int j = 0; j < count && idx < withAngles.size(); j++, idx++) {
                srShipments.add(withAngles.get(idx).shipment);
            }

            // Sort within SR by nearest-neighbor from hub
            srShipments = optimizeOrderNearestNeighbor(srShipments);
            assignments.put(sr, srShipments);
        }

        return assignments;
    }

    /**
     * Optimize delivery order within an SR's shipments using nearest-neighbor heuristic.
     */
    private List<Shipment> optimizeOrderNearestNeighbor(List<Shipment> shipments) {
        if (shipments.size() <= 1) return shipments;

        List<Shipment> ordered = new ArrayList<>();
        boolean[] visited = new boolean[shipments.size()];
        double curLat = hubLat, curLng = hubLng;

        for (int i = 0; i < shipments.size(); i++) {
            double minDist = Double.MAX_VALUE;
            int nearest = -1;
            for (int j = 0; j < shipments.size(); j++) {
                if (!visited[j]) {
                    double dist = GoogleMapsService.haversine(curLat, curLng,
                            shipments.get(j).getDropLatitude(),
                            shipments.get(j).getDropLongitude());
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = j;
                    }
                }
            }
            if (nearest >= 0) {
                visited[nearest] = true;
                ordered.add(shipments.get(nearest));
                curLat = shipments.get(nearest).getDropLatitude();
                curLng = shipments.get(nearest).getDropLongitude();
            }
        }
        return ordered;
    }

    private List<String> getOrGenerateSRNames(List<Shipment> shipments, int srCount) {
        // Try to get unique SR names from data
        List<String> existingSRs = shipments.stream()
                .map(Shipment::getSrName)
                .filter(sr -> sr != null && !sr.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (existingSRs.size() >= srCount) {
            return existingSRs.subList(0, srCount);
        }

        // Generate SR names
        List<String> srNames = new ArrayList<>(existingSRs);
        for (int i = existingSRs.size(); i < srCount; i++) {
            srNames.add("SR-" + String.format("%03d", i + 1));
        }
        return srNames;
    }

    private boolean isWithinServiceArea(double lat, double lng) {
        // Filter out obviously wrong coordinates (e.g., different cities)
        double distFromHub = GoogleMapsService.haversine(hubLat, hubLng, lat, lng);
        return distFromHub < 50; // Within 50 km of hub
    }

    private RouteResponse.ShipmentStop buildStop(int seq, Shipment s) {
        return RouteResponse.ShipmentStop.builder()
                .sequence(seq)
                .shippingId(s.getShippingId())
                .dropPincode(s.getDropPincode())
                .latitude(s.getDropLatitude())
                .longitude(s.getDropLongitude())
                .orderType(s.getOrderType())
                .weight(s.getPhyWeight())
                .clientId(s.getClientId())
                .build();
    }

    private record ShipmentWithAngle(Shipment shipment, double angle) {}
}
