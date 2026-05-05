package com.example.LMrouting.controller;

import com.example.LMrouting.dto.ShipmentStopDto;
import com.example.LMrouting.dto.SrRouteDto;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.RouteOptimizerService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shows the "previous" allocation — how shipments were originally assigned
 * based on the srname column from the uploaded CSV/XLSX.
 * This allows side-by-side comparison with the v3 optimized allocation.
 */
@RestController
@RequestMapping("/api/previous")
@RequiredArgsConstructor
@Slf4j
public class PreviousAllocationController {

    private final InMemoryStore store;
    private final RouteOptimizerService routeOptimizerService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    private static final double FUEL_COST_PER_KM = 2.5;

    /**
     * Get summary of the original (previous) allocation based on srname from CSV.
     * Groups shipments by their original srname and computes metrics.
     */
    @GetMapping("/{date}/summary")
    public ResponseEntity<Map<String, Object>> getPreviousSummary(@PathVariable String date) {
        LocalDate localDate = AllocationController.parseDate(date);
        String dateStr = formatDate(localDate);

        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        if (allShipments.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "No shipments found for " + dateStr));
        }

        // Filter to valid shipments (non-zero coords, not out-of-range)
        List<Shipment> valid = allShipments.stream()
                .filter(s -> s.getDropLatitude() != 0 && s.getDropLongitude() != 0)
                .filter(s -> !s.isOutOfRange())
                .collect(Collectors.toList());

        // Group by original srname
        Map<String, List<Shipment>> bySr = valid.stream()
                .filter(s -> s.getSrName() != null && !s.getSrName().isBlank())
                .collect(Collectors.groupingBy(Shipment::getSrName, LinkedHashMap::new, Collectors.toList()));

        if (bySr.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "error", "No srname data in uploaded file. The CSV must have a 'srname' column.",
                    "totalShipments", allShipments.size()));
        }

        // Compute per-SR metrics
        List<SrSummaryDto> srSummaries = new ArrayList<>();
        double totalDistance = 0;
        double totalPayout = 0;

        for (Map.Entry<String, List<Shipment>> entry : bySr.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> shipments = entry.getValue();

            // Sequence by nearest-neighbor from hub
            List<Shipment> ordered = shipments.size() >= 2
                    ? routeOptimizerService.optimizeRoute(sr, shipments)
                    : new ArrayList<>(shipments);
            if (ordered.size() == 1) ordered.get(0).setRouteSequence(1);

            double distKm = routeOptimizerService.estimateDistanceKm(ordered);
            int heavyCount = (int) shipments.stream().filter(s -> s.getIsHeavy() == 1).count();
            double grossPayout = shipments.stream().mapToDouble(Shipment::getExpectedPayout).sum();
            double fuelCost = distKm * FUEL_COST_PER_KM;
            double netEarnings = grossPayout - fuelCost;

            List<String> pincodes = shipments.stream()
                    .map(Shipment::getDropPincode)
                    .filter(Objects::nonNull).distinct().sorted()
                    .collect(Collectors.toList());

            srSummaries.add(new SrSummaryDto(sr, shipments.size(), heavyCount,
                    0.0, distKm, pincodes, grossPayout, fuelCost, netEarnings));

            totalDistance += distKm;
            totalPayout += grossPayout;
        }

        // Sort by SR name
        srSummaries.sort(Comparator.comparing(SrSummaryDto::srName));

        // Earnings stats
        double[] earnings = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).toArray();
        double meanEarnings = Arrays.stream(earnings).average().orElse(0);
        double maxEarnings = Arrays.stream(earnings).max().orElse(0);
        double minEarnings = Arrays.stream(earnings).min().orElse(0);
        double earningsRange = maxEarnings - minEarnings;
        double earningsVariance = 0;
        for (double e : earnings) earningsVariance += (e - meanEarnings) * (e - meanEarnings);
        if (earnings.length > 0) earningsVariance /= earnings.length;

        // Count SRs within 10% of average
        long within10pct = Arrays.stream(earnings)
                .filter(e -> Math.abs(e - meanEarnings) <= meanEarnings * 0.1)
                .count();

        int totalAllocated = bySr.values().stream().mapToInt(List::size).sum();
        int unallocated = valid.size() - totalAllocated;

        // Shipments with no srname
        long noSr = valid.stream()
                .filter(s -> s.getSrName() == null || s.getSrName().isBlank())
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", dateStr);
        result.put("mode", "previous");
        result.put("totalShipments", allShipments.size());
        result.put("validShipments", valid.size());
        result.put("allocatedShipments", totalAllocated);
        result.put("unallocatedShipments", unallocated);
        result.put("noSrNameCount", noSr);
        result.put("totalSrs", bySr.size());
        result.put("totalDistanceKm", Math.round(totalDistance * 10.0) / 10.0);
        result.put("meanEarnings", Math.round(meanEarnings * 100.0) / 100.0);
        result.put("maxEarnings", Math.round(maxEarnings * 100.0) / 100.0);
        result.put("minEarnings", Math.round(minEarnings * 100.0) / 100.0);
        result.put("earningsRange", Math.round(earningsRange * 100.0) / 100.0);
        result.put("earningsVariance", Math.round(earningsVariance * 100.0) / 100.0);
        result.put("within10PctOfAvg", within10pct);
        result.put("within10PctPct", bySr.size() > 0
                ? Math.round(within10pct * 1000.0 / bySr.size()) / 10.0 : 0);
        result.put("srSummaries", srSummaries);
        return ResponseEntity.ok(result);
    }

    /**
     * Get route details for a specific SR from the previous allocation.
     */
    @GetMapping("/{date}/sr/{srName}")
    public ResponseEntity<SrRouteDto> getSrRoute(@PathVariable String date,
                                                  @PathVariable String srName) {
        LocalDate localDate = AllocationController.parseDate(date);
        String dateStr = formatDate(localDate);

        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        List<Shipment> srShipments = allShipments.stream()
                .filter(s -> srName.equals(s.getSrName()))
                .filter(s -> s.getDropLatitude() != 0 && s.getDropLongitude() != 0)
                .filter(s -> !s.isOutOfRange())
                .collect(Collectors.toList());

        if (srShipments.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Sequence
        List<Shipment> ordered = srShipments.size() >= 2
                ? routeOptimizerService.optimizeRoute(srName, srShipments)
                : new ArrayList<>(srShipments);
        if (ordered.size() == 1) ordered.get(0).setRouteSequence(1);

        double distKm = routeOptimizerService.estimateDistanceKm(ordered);

        List<ShipmentStopDto> stops = ordered.stream()
                .map(s -> new ShipmentStopDto(
                        s.getRouteSequence(), s.getShippingId(), s.getDropPincode(),
                        s.getDropLatitude(), s.getDropLongitude(),
                        s.getOrderType(), s.getPhyWeight(), s.getIsHeavy() == 1,
                        s.getShipmentFlow(), false, s.isOutOfRange()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new SrRouteDto(srName, dateStr, srShipments.size(), distKm, stops));
    }

    private String formatDate(LocalDate date) {
        java.time.format.DateTimeFormatter[] fmts = {
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy", java.util.Locale.ENGLISH),
        };
        for (var fmt : fmts) {
            String candidate = date.format(fmt);
            if (store.hasShipmentsForDate(candidate)) return candidate;
        }
        String iso = date.toString();
        if (store.hasShipmentsForDate(iso)) return iso;
        return date.format(fmts[0]);
    }
}
