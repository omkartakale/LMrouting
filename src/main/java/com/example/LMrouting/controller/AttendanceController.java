package com.example.LMrouting.controller;

import com.example.LMrouting.dto.SrAttendanceDto;
import com.example.LMrouting.service.AffinityConfigStorageService;
import com.example.LMrouting.service.AttendanceManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for SR attendance management.
 *
 * <p>All endpoints are under {@code /api/attendance}. Date path variables are
 * parsed using {@link AllocationController#parseDate(String)}, which supports
 * both ISO format (yyyy-MM-dd) and the sample-data format "dd-MMM-yy".
 */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
@Slf4j
public class AttendanceController {

    private final AttendanceManagerService attendanceManagerService;
    private final AffinityConfigStorageService affinityConfigStorageService;

    // -------------------------------------------------------------------------
    // GET /api/attendance/{date}
    // -------------------------------------------------------------------------

    /**
     * Return all active SRs with their attendance status for the given date.
     * SRs with no explicit record default to {@code present = false}.
     *
     * @param dateStr path variable (ISO or "dd-MMM-yy")
     * @return list of {@link SrAttendanceDto}
     */
    @GetMapping("/{date}")
    public ResponseEntity<List<SrAttendanceDto>> getAttendance(
            @PathVariable("date") String dateStr) {
        LocalDate date = AllocationController.parseDate(dateStr);
        log.info("AttendanceController: GET /api/attendance/{}", date);
        List<SrAttendanceDto> attendance = attendanceManagerService.getAttendanceForDate(date);
        return ResponseEntity.ok(attendance);
    }

    // -------------------------------------------------------------------------
    // PUT /api/attendance/{date}/{srName}?present=
    // -------------------------------------------------------------------------

    /**
     * Mark an SR present or absent for the given date.
     *
     * @param dateStr path variable (ISO or "dd-MMM-yy")
     * @param srName  path variable — the SR name
     * @param present query parameter — {@code true} to mark present, {@code false} to mark absent
     * @return 204 No Content
     */
    @PutMapping("/{date}/{srName}")
    public ResponseEntity<Void> setAttendance(
            @PathVariable("date") String dateStr,
            @PathVariable("srName") String srName,
            @RequestParam("present") boolean present) {
        LocalDate date = AllocationController.parseDate(dateStr);
        log.info("AttendanceController: PUT /api/attendance/{}/{} present={}", date, srName, present);
        attendanceManagerService.setAttendance(date, srName, present);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // POST /api/attendance/sr — Add a new SR to the registry
    // -------------------------------------------------------------------------

    @PostMapping("/sr")
    public ResponseEntity<java.util.Map<String, String>> addSr(@RequestBody java.util.Map<String, String> body) {
        String srName = body.get("srName");
        if (srName == null || srName.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "SR name cannot be empty"));
        }
        log.info("AttendanceController: POST /api/attendance/sr — adding '{}'", srName.trim());
        attendanceManagerService.addSr(srName.trim());
        return ResponseEntity.ok(java.util.Map.of("success", "true", "srName", srName.trim()));
    }

    // -------------------------------------------------------------------------
    // GET /api/attendance/sr-shift-durations — Retrieve per-SR shift durations
    // -------------------------------------------------------------------------

    /**
     * Retrieve the current per-SR shift duration configuration.
     *
     * @return 200 OK with a map of SR name → shift duration in minutes (empty map if not configured)
     */
    @GetMapping("/sr-shift-durations")
    public ResponseEntity<Map<String, Integer>> getSrShiftDurations() {
        try {
            Map<String, Integer> durations = affinityConfigStorageService.loadSrShiftDurations();
            log.info("AttendanceController: GET /api/attendance/sr-shift-durations — {} entries", durations.size());
            return ResponseEntity.ok(durations);
        } catch (IOException e) {
            log.error("Failed to load SR shift durations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // -------------------------------------------------------------------------
    // PUT /api/attendance/sr-shift-durations — Save per-SR shift durations
    // -------------------------------------------------------------------------

    /**
     * Save per-SR shift duration configuration.
     *
     * <p>Each value must be a positive integer between 360 and 720 (minutes),
     * representing a shift duration of 6–12 hours.
     *
     * @param srShiftDurations map of SR name → shift duration in minutes
     * @return 200 OK with the saved map, or 400 Bad Request if validation fails
     */
    @PutMapping("/sr-shift-durations")
    public ResponseEntity<?> saveSrShiftDurations(@RequestBody Map<String, Integer> srShiftDurations) {
        // Validate input
        List<String> errors = new ArrayList<>();
        if (srShiftDurations == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body cannot be null"));
        }
        for (Map.Entry<String, Integer> entry : srShiftDurations.entrySet()) {
            String srName = entry.getKey();
            Integer duration = entry.getValue();
            if (srName == null || srName.isBlank()) {
                errors.add("SR name cannot be empty");
                continue;
            }
            if (duration == null) {
                errors.add("Shift duration for '" + srName + "' cannot be null");
            } else if (duration < 120 || duration > 720) {
                errors.add("Shift duration for '" + srName + "' must be between 120 and 720 minutes, got " + duration);
            }
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "details", errors));
        }

        try {
            affinityConfigStorageService.saveSrShiftDurations(srShiftDurations);
            log.info("AttendanceController: PUT /api/attendance/sr-shift-durations — saved {} entries", srShiftDurations.size());
            return ResponseEntity.ok(srShiftDurations);
        } catch (IOException e) {
            log.error("Failed to save SR shift durations", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save: " + e.getMessage()));
        }
    }
}
