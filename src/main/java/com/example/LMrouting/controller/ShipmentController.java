package com.example.LMrouting.controller;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for shipment data access.
 * Provides endpoints for querying shipment data for visualization and analysis.
 */
@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
@Slf4j
public class ShipmentController {

    private final InMemoryStore store;

    /**
     * Get all shipments for a specific date.
     * Used by affinity mode visualization to display shipments on map.
     * 
     * GET /api/shipments/{date}
     * 
     * @param date The allocation date (e.g., "2024-01-15")
     * @return List of shipments with coordinates and metadata
     */
    @GetMapping("/{date}")
    public ResponseEntity<?> getShipmentsByDate(@PathVariable String date) {
        try {
            log.info("Fetching shipments for date: {}", date);
            
            List<?> shipments = store.findShipmentsByDate(date);
            
            if (shipments.isEmpty()) {
                log.warn("No shipments found for date: {}", date);
                return ResponseEntity.status(404)
                        .body(Map.of("error", "No shipments found for date: " + date));
            }
            
            // Convert to a format suitable for frontend visualization
            List<Map<String, Object>> shipmentData = shipments.stream()
                    .filter(s -> s instanceof Shipment)
                    .map(s -> {
                        Shipment shipment = (Shipment) s;
                        Map<String, Object> data = Map.of(
                                "shipmentId", shipment.getShippingId() != null ? shipment.getShippingId() : "",
                                "pincode", shipment.getDropPincode() != null ? shipment.getDropPincode() : "UNKNOWN",
                                "latitude", shipment.getDropLatitude(),
                                "longitude", shipment.getDropLongitude(),
                                "cityName", shipment.getCityName() != null ? shipment.getCityName() : "",
                                "stateName", shipment.getStateName() != null ? shipment.getStateName() : "",
                                "isHeavy", shipment.getIsHeavy(),
                                "weight", shipment.getPhyWeight(),
                                "outOfRange", shipment.isOutOfRange(),
                                "assignedSr", shipment.getAssignedSr() != null ? shipment.getAssignedSr() : ""
                        );
                        return (Map<String, Object>) (Map<?, ?>) data;
                    })
                    .toList();
            
            log.info("Returning {} shipments for date '{}'", shipmentData.size(), date);
            return ResponseEntity.ok(shipmentData);
            
        } catch (Exception e) {
            log.error("Error fetching shipments for date '{}'", date, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error fetching shipments: " + e.getMessage()));
        }
    }

    /**
     * Get shipment statistics for a specific date.
     * 
     * GET /api/shipments/{date}/stats
     * 
     * @param date The allocation date
     * @return Statistics including total count, out-of-range count, unique pincodes
     */
    @GetMapping("/{date}/stats")
    public ResponseEntity<?> getShipmentStats(@PathVariable String date) {
        try {
            List<?> shipments = store.findShipmentsByDate(date);
            
            if (shipments.isEmpty()) {
                return ResponseEntity.status(404)
                        .body(Map.of("error", "No shipments found for date: " + date));
            }
            
            long totalCount = shipments.size();
            long outOfRangeCount = shipments.stream()
                    .filter(s -> s instanceof Shipment && ((Shipment) s).isOutOfRange())
                    .count();
            
            long uniquePincodes = shipments.stream()
                    .filter(s -> s instanceof Shipment)
                    .map(s -> ((Shipment) s).getDropPincode())
                    .filter(p -> p != null && !p.isEmpty())
                    .distinct()
                    .count();
            
            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "totalShipments", totalCount,
                    "outOfRangeShipments", outOfRangeCount,
                    "uniquePincodes", uniquePincodes
            ));
            
        } catch (Exception e) {
            log.error("Error fetching shipment stats for date '{}'", date, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error fetching stats: " + e.getMessage()));
        }
    }
}
