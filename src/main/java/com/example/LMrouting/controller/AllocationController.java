package com.example.LMrouting.controller;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OverrideManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/allocate")
@RequiredArgsConstructor
@Slf4j
public class AllocationController {

    private static final DateTimeFormatter FMT_DMY  = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_DMY2 = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    // Timeline constants
    private static final double DELIVERY_TIME_MIN = 3.0;   // 3 min handling per stop
    private static final double BREAK_BUFFER_MIN  = 30.0;  // 30 min break buffer per run

    private final AllocationEngineService allocationEngineService;
    private final OverrideManagerService overrideManagerService;
    private final GoogleMapsService googleMapsService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @PostMapping
    public ResponseEntity<AllocationSummary> allocate(@RequestBody AllocateRequest request) {
        LocalDate date = parseDate(request.date());
        AllocationSummary summary = allocationEngineService.allocate(date);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{date}/summary")
    public ResponseEntity<AllocationSummary> getSummary(@PathVariable("date") String dateStr) {
        AllocationSummary summary = allocationEngineService.getSummary(parseDate(dateStr));
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{date}/sr/{srName}")
    public ResponseEntity<SrRouteDto> getSrRoute(@PathVariable("date") String dateStr,
                                                  @PathVariable("srName") String srName) {
        SrRouteDto route = allocationEngineService.getSrRoute(parseDate(dateStr), srName);
        return ResponseEntity.ok(route);
    }

    @PostMapping("/{date}/override")
    public ResponseEntity<OverrideResult> override(@PathVariable("date") String dateStr,
                                                    @RequestBody OverrideRequest request) {
        OverrideResult result = overrideManagerService.reassign(
                request.shippingId(), request.targetSrName(), parseDate(dateStr));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{date}/override")
    public ResponseEntity<Void> undoOverride(@PathVariable("date") String dateStr) {
        overrideManagerService.undoLastOverride(parseDate(dateStr));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{date}/finalize")
    public ResponseEntity<Void> finalize(@PathVariable("date") String dateStr) {
        overrideManagerService.finalizeAllocation(parseDate(dateStr));
        return ResponseEntity.noContent().build();
    }

    /**
     * Get the road polyline for an SR's route.
     * Returns list of [lat, lng] pairs — follows real roads if ORS/Google Maps is configured.
     * GET /api/allocate/{date}/sr/{srName}/polyline
     */
    @GetMapping("/{date}/sr/{srName}/polyline")
    public ResponseEntity<List<double[]>> getSrPolyline(@PathVariable("date") String dateStr,
                                                         @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getRoutePolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the ORS (OpenStreetMap) road polyline for an SR's route.
     * GET /api/allocate/{date}/sr/{srName}/polyline/ors
     * GET /api/allocate/{date}/sr/{srName}/polyline/osm  (alias)
     */
    @GetMapping({"/{date}/sr/{srName}/polyline/ors", "/{date}/sr/{srName}/polyline/osm"})
    public ResponseEntity<List<double[]>> getSrOrsPolyline(@PathVariable("date") String dateStr,
                                                            @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getOrsPolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the Google Maps road polyline for an SR's route.
     * GET /api/allocate/{date}/sr/{srName}/polyline/google
     */
    @GetMapping("/{date}/sr/{srName}/polyline/google")
    public ResponseEntity<List<double[]>> getSrGooglePolyline(@PathVariable("date") String dateStr,
                                                               @PathVariable("srName") String srName) {
        List<double[]> polyline = allocationEngineService.getGoogleMapsPolyline(parseDate(dateStr), srName);
        return ResponseEntity.ok(polyline);
    }

    /**
     * Get the delivery timeline for an SR's route.
     *
     * Calculates: Hub → Stop 1 (travel + 3 min delivery) → Stop 2 → … → Hub return
     * Includes a 30-minute break buffer inserted at the midpoint of the route.
     *
     * GET /api/allocate/{date}/sr/{srName}/timeline
     * Optional query param: startTime=09:00 (default 09:00)
     *
     * Response: {
     *   srName, date, startTime, endTime, totalDurationMinutes,
     *   travelMinutes, deliveryMinutes, breakMinutes,
     *   stops: [ { sequence, shippingId, pincode, lat, lng,
     *              arrivalTime, departureTime, travelFromPrevMin, deliveryMin } ],
     *   returnToHub: { arrivalTime, travelMin }
     * }
     */
    @GetMapping("/{date}/sr/{srName}/timeline")
    public ResponseEntity<Map<String, Object>> getSrTimeline(
            @PathVariable("date") String dateStr,
            @PathVariable("srName") String srName,
            @RequestParam(value = "startTime", defaultValue = "09:00") String startTime) {

        SrRouteDto route = allocationEngineService.getSrRoute(parseDate(dateStr), srName);
        List<ShipmentStopDto> stops = route.stops();

        if (stops == null || stops.isEmpty()) {
            return ResponseEntity.ok(Map.of("srName", srName, "date", dateStr,
                    "message", "No stops assigned", "stops", List.of()));
        }

        // Build ordered waypoints [lat, lng]
        List<double[]> waypoints = stops.stream()
                .sorted(Comparator.comparingInt(ShipmentStopDto::sequence))
                .map(s -> new double[]{s.latitude(), s.longitude()})
                .collect(Collectors.toList());

        // Get per-leg travel durations from Google Maps (or Haversine fallback)
        List<Double> legDurations = googleMapsService.getLegDurationsMinutes(hubLat, hubLng, waypoints);

        // Compute per-leg Haversine distances (km) — hub→stop1, stop1→stop2, …, stopN→hub
        List<Double> legDistances = new ArrayList<>();
        double prevLat = hubLat, prevLng = hubLng;
        for (double[] wp : waypoints) {
            legDistances.add(GoogleMapsService.haversine(prevLat, prevLng, wp[0], wp[1]));
            prevLat = wp[0]; prevLng = wp[1];
        }
        // Return leg distance
        double returnDistKm = GoogleMapsService.haversine(prevLat, prevLng, hubLat, hubLng);
        legDistances.add(returnDistKm);

        // Parse start time
        int startHour = 9, startMin = 0;
        try {
            String[] parts = startTime.split(":");
            startHour = Integer.parseInt(parts[0]);
            startMin  = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (Exception ignored) {}

        // Build timeline
        double currentMinutes = startHour * 60.0 + startMin;
        double totalTravelMin = 0, totalDeliveryMin = 0;
        int breakInsertedAfterStop = stops.size() / 2; // break at midpoint
        boolean breakInserted = false;

        List<Map<String, Object>> timelineStops = new ArrayList<>();
        List<ShipmentStopDto> orderedStops = stops.stream()
                .sorted(Comparator.comparingInt(ShipmentStopDto::sequence))
                .collect(Collectors.toList());

        for (int i = 0; i < orderedStops.size(); i++) {
            ShipmentStopDto stop = orderedStops.get(i);
            double travelMin = i < legDurations.size() ? legDurations.get(i) : 0.0;

            // Insert break buffer at midpoint
            if (!breakInserted && i == breakInsertedAfterStop) {
                currentMinutes += BREAK_BUFFER_MIN;
                breakInserted = true;
            }

            currentMinutes += travelMin;
            totalTravelMin += travelMin;
            double arrivalMinutes = currentMinutes;

            currentMinutes += DELIVERY_TIME_MIN;
            totalDeliveryMin += DELIVERY_TIME_MIN;
            double departureMinutes = currentMinutes;

            Map<String, Object> stopEntry = new LinkedHashMap<>();
            stopEntry.put("sequence",          stop.sequence());
            stopEntry.put("shippingId",        stop.shippingId());
            stopEntry.put("pincode",           stop.dropPincode());
            stopEntry.put("lat",               stop.latitude());
            stopEntry.put("lng",               stop.longitude());
            stopEntry.put("orderType",         stop.orderType());
            stopEntry.put("isHeavy",           stop.isHeavy());
            stopEntry.put("travelFromPrevMin", Math.round(travelMin * 10.0) / 10.0);
            // Haversine distance from previous point (hub or last stop) in km
            double distKm = i < legDistances.size() ? legDistances.get(i) : 0.0;
            stopEntry.put("distFromPrevKm",    Math.round(distKm * 100.0) / 100.0);
            stopEntry.put("deliveryMin",       DELIVERY_TIME_MIN);
            stopEntry.put("arrivalTime",       minutesToTime(arrivalMinutes));
            stopEntry.put("departureTime",     minutesToTime(departureMinutes));
            stopEntry.put("arrivalMinutes",    Math.round(arrivalMinutes * 10.0) / 10.0);
            timelineStops.add(stopEntry);
        }

        // Return to hub
        double returnTravelMin = legDurations.size() > orderedStops.size()
                ? legDurations.get(orderedStops.size()) : 0.0;
        currentMinutes += returnTravelMin;
        totalTravelMin += returnTravelMin;

        double totalDuration = currentMinutes - (startHour * 60.0 + startMin);

        Map<String, Object> returnToHub = new LinkedHashMap<>();
        returnToHub.put("travelMin",   Math.round(returnTravelMin * 10.0) / 10.0);
        returnToHub.put("distKm",      Math.round(returnDistKm * 100.0) / 100.0);
        returnToHub.put("arrivalTime", minutesToTime(currentMinutes));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("srName",               srName);
        result.put("date",                 dateStr);
        result.put("startTime",            minutesToTime(startHour * 60.0 + startMin));
        result.put("endTime",              minutesToTime(currentMinutes));
        result.put("totalDurationMinutes", Math.round(totalDuration * 10.0) / 10.0);
        result.put("travelMinutes",        Math.round(totalTravelMin * 10.0) / 10.0);
        result.put("deliveryMinutes",      Math.round(totalDeliveryMin * 10.0) / 10.0);
        result.put("breakMinutes",         BREAK_BUFFER_MIN);
        result.put("stopCount",            orderedStops.size());
        result.put("breakAfterStop",       breakInsertedAfterStop);
        result.put("stops",                timelineStops);
        result.put("returnToHub",          returnToHub);

        log.info("Timeline for {} on {}: {} stops, {:.1f} min total ({:.1f} travel + {:.1f} delivery + {} break)",
                srName, dateStr, orderedStops.size(), totalDuration,
                totalTravelMin, totalDeliveryMin, BREAK_BUFFER_MIN);

        return ResponseEntity.ok(result);
    }

    /** Convert minutes-since-midnight to "HH:MM" string. */
    private static String minutesToTime(double totalMinutes) {
        int h = (int)(totalMinutes / 60) % 24;
        int m = (int)(totalMinutes % 60);
        return String.format("%02d:%02d", h, m);
    }

    /**
     * Parse date strings in multiple formats:
     * - ISO: 2026-03-24
     * - dd-MMM-yy: 24-Mar-26
     * - dd/MM/yyyy: 24/03/2026
     */
    static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Date cannot be blank.");
        }
        String s = dateStr.trim();

        // Try ISO first
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignored) {}

        // Try dd-MMM-yy (e.g. 24-Mar-26)
        try { return LocalDate.parse(s, FMT_DMY); } catch (DateTimeParseException ignored) {}

        // Try d-MMM-yy (e.g. 4-Mar-26)
        try { return LocalDate.parse(s, FMT_DMY2); } catch (DateTimeParseException ignored) {}

        // Try dd/MM/yyyy
        try { return LocalDate.parse(s, FMT_SLASH); } catch (DateTimeParseException ignored) {}

        throw new IllegalArgumentException(
                "Cannot parse date '" + s + "'. Supported formats: yyyy-MM-dd, dd-MMM-yy, dd/MM/yyyy");
    }
}
