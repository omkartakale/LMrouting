package com.example.LMrouting.controller;

import com.example.LMrouting.dto.IngestionResult;
import com.example.LMrouting.service.AttendanceManagerService;
import com.example.LMrouting.service.CsvIngestionService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST controller for CSV file upload and data management.
 */
@RestController
@RequestMapping("/api/csv")
@RequiredArgsConstructor
@Slf4j
public class CsvUploadController {

    private final CsvIngestionService csvIngestionService;
    private final AttendanceManagerService attendanceManagerService;
    private final InMemoryStore store;

    /**
     * Preview columns detected in an uploaded file without full parsing.
     * POST /api/csv/preview-columns
     */
    @PostMapping("/preview-columns")
    public ResponseEntity<?> previewColumns(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(csvIngestionService.previewColumns(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload and parse a shipment CSV file.
     * POST /api/csv/upload
     * Content-Type: multipart/form-data
     * Field name: file
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        log.info("CsvUploadController: received file '{}' ({} bytes)",
                file.getOriginalFilename(), file.getSize());

        try {
            IngestionResult result = csvIngestionService.ingest(file);
            log.info("CsvUploadController: ingestion complete — {} valid, {} skipped, dates: {}",
                    result.validCount(), result.skippedCount(), result.datesFound());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("CsvUploadController: validation error — {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("CsvUploadController: unexpected error", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Internal error processing file: " + e.getMessage()));
        }
    }

    /**
     * Get list of dates that have shipment data loaded.
     * GET /api/csv/dates
     */
    @GetMapping("/dates")
    public ResponseEntity<List<String>> getDates() {
        return ResponseEntity.ok(store.findAllDates());
    }

    /**
     * Get shipment count for a specific date.
     * GET /api/csv/count/{date}
     */
    @GetMapping("/count/{date}")
    public ResponseEntity<Map<String, Object>> getCount(@PathVariable String date) {
        List<?> shipments = store.findShipmentsByDate(date);
        long outOfRange = shipments.stream()
                .filter(s -> s instanceof com.example.LMrouting.model.Shipment &&
                        ((com.example.LMrouting.model.Shipment) s).isOutOfRange())
                .count();
        return ResponseEntity.ok(Map.of(
                "date", date,
                "total", shipments.size(),
                "outOfRange", outOfRange
        ));
    }

    /**
     * Clear all data for a specific date (allows re-upload).
     * DELETE /api/csv/clear/{date}
     */
    @DeleteMapping("/clear/{date}")
    public ResponseEntity<Map<String, String>> clearDate(@PathVariable String date) {
        store.clearDate(date);
        log.info("CsvUploadController: cleared data for date '{}'", date);
        return ResponseEntity.ok(Map.of("message", "Data cleared for date: " + date));
    }
}
