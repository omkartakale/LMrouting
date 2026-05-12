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
     * Get ALL shipments across all dates — for "Plot All Points" visualization.
     * Returns every uploaded shipment regardless of date or allocation status.
     * GET /api/shipments/all
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllShipments() {
        try {
            List<String> dates = store.findAllDates();
            if (dates.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            List<Map<String, Object>> shipmentData = new java.util.ArrayList<>();
            for (String date : dates) {
                List<?> shipments = store.findShipmentsByDate(date);
                shipments.stream()
                        .filter(s -> s instanceof Shipment)
                        .map(s -> {
                            Shipment shipment = (Shipment) s;
                            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                            data.put("shipmentId", shipment.getShippingId() != null ? shipment.getShippingId() : "");
                            data.put("pincode", shipment.getDropPincode() != null ? shipment.getDropPincode() : "UNKNOWN");
                            data.put("latitude", shipment.getDropLatitude());
                            data.put("longitude", shipment.getDropLongitude());
                            data.put("isHeavy", shipment.getIsHeavy());
                            data.put("weight", shipment.getPhyWeight());
                            data.put("outOfRange", shipment.isOutOfRange());
                            data.put("assignedSr", shipment.getAssignedSr() != null ? shipment.getAssignedSr() : "");
                            data.put("priority", shipment.getPriority() != null ? shipment.getPriority() : "P2");
                            data.put("date", date);
                            return data;
                        })
                        .forEach(shipmentData::add);
            }
            log.info("Returning {} total shipments across {} dates", shipmentData.size(), dates.size());
            return ResponseEntity.ok(shipmentData);
        } catch (Exception e) {
            log.error("Error fetching all shipments", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error fetching shipments: " + e.getMessage()));
        }
    }

    /**
     * Get all shipments for a specific date.
     * Used by affinity mode visualization to display shipments on map.
     * GET /api/shipments/{date}
     */
    @GetMapping("/{date}")
    public ResponseEntity<?> getShipmentsByDate(@PathVariable String date) {
        try {
            log.info("Fetching shipments for date: {}", date);

            // Try the date as-is first, then try format conversions
            List<?> shipments = store.findShipmentsByDate(date);

            // If not found, try converting ISO date to the stored format (dd-MMM-yy)
            if (shipments.isEmpty()) {
                String converted = convertDateFormat(date);
                if (converted != null && !converted.equals(date)) {
                    log.info("Retrying with converted date: {}", converted);
                    shipments = store.findShipmentsByDate(converted);
                }
            }

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
                        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
                        data.put("shipmentId", shipment.getShippingId() != null ? shipment.getShippingId() : "");
                        data.put("pincode", shipment.getDropPincode() != null ? shipment.getDropPincode() : "UNKNOWN");
                        data.put("latitude", shipment.getDropLatitude());
                        data.put("longitude", shipment.getDropLongitude());
                        data.put("cityName", shipment.getCityName() != null ? shipment.getCityName() : "");
                        data.put("stateName", shipment.getStateName() != null ? shipment.getStateName() : "");
                        data.put("isHeavy", shipment.getIsHeavy());
                        data.put("weight", shipment.getPhyWeight());
                        data.put("outOfRange", shipment.isOutOfRange());
                        data.put("assignedSr", shipment.getAssignedSr() != null ? shipment.getAssignedSr() : "");
                        data.put("priority", shipment.getPriority() != null ? shipment.getPriority() : "P2");
                        return (Map<String, Object>) data;
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

    /**
     * Try to convert a date string from ISO format (yyyy-MM-dd) to the stored
     * ingestion format (dd-MMM-yy, e.g. "24-Mar-26").
     * Returns null if conversion fails.
     */
    private String convertDateFormat(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        // First try all stored dates — find one that matches when both are parsed
        try {
            java.time.LocalDate target = parseAnyDate(dateStr);
            if (target != null) {
                for (String stored : store.findAllDates()) {
                    java.time.LocalDate storedDate = parseAnyDate(stored);
                    if (target.equals(storedDate)) return stored;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private java.time.LocalDate parseAnyDate(String s) {
        if (s == null || s.isBlank()) return null;
        java.time.format.DateTimeFormatter[] fmts = {
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", java.util.Locale.ENGLISH),
        };
        for (var fmt : fmts) {
            try { return java.time.LocalDate.parse(s.trim(), fmt); } catch (Exception ignored) {}
        }
        return null;
    }
}
