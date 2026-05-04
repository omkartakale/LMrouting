package com.example.LMrouting.controller;

import com.example.LMrouting.dto.SrAttendanceDto;
import com.example.LMrouting.service.AttendanceManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

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
}
