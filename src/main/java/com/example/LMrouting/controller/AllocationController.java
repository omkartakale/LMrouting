package com.example.LMrouting.controller;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.OverrideManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/allocate")
@RequiredArgsConstructor
@Slf4j
public class AllocationController {

    private static final DateTimeFormatter FMT_DMY = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_DMY2 = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final AllocationEngineService allocationEngineService;
    private final OverrideManagerService overrideManagerService;

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
