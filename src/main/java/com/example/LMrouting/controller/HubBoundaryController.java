package com.example.LMrouting.controller;

import com.example.LMrouting.dto.HubBoundaryResponse;
import com.example.LMrouting.service.HubBoundaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Proxy controller for hub boundary fetching.
 *
 * GET /api/hub/boundary?hubName=PNQ+HDP
 *
 * Calls 3 external Xpressbees APIs server-side to avoid CORS issues.
 * Returns 204 No Content if boundary cannot be fetched (graceful degradation).
 */
@RestController
@RequestMapping("/api/hub")
@RequiredArgsConstructor
@Slf4j
public class HubBoundaryController {

    private final HubBoundaryService hubBoundaryService;

    @GetMapping("/boundary")
    public ResponseEntity<HubBoundaryResponse> getBoundary(
            @RequestParam(value = "hubName", required = false, defaultValue = "") String hubName) {

        if (hubName.isBlank()) {
            log.debug("HubBoundary: hubName param is blank, returning 204");
            return ResponseEntity.noContent().build();
        }

        HubBoundaryResponse response = hubBoundaryService.fetchBoundary(hubName.trim());

        if (response == null || response.coordinates() == null || response.coordinates().isEmpty()) {
            // Graceful degradation — frontend will simply not show the boundary
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(response);
    }
}
