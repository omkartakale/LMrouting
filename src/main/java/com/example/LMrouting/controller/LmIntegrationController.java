package com.example.LMrouting.controller;

import com.example.LMrouting.dto.SrRouteDto;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.LmApiProxyService;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Integration controller for pushing v3 optimized routes to the
 * XpressBees Last Mile Allocation API (Stage environment).
 *
 * Flow:
 *   1. Frontend runs allocation (creates routes per SR)
 *   2. GET /api/lm/token-status  → check if Keycloak token is valid
 *   3. POST /api/lm/delivery-users → fetch real SRs from LM
 *   4. User maps each v3 SR to a real LM DeliveryUserId
 *   5. POST /api/lm/push-route  → bulk allocate all shipments for one SR
 *   6. POST /api/lm/confirm     → confirm allocation (mark OFD, create trip)
 *   7. POST /api/lm/push-all    → push all routes at once
 */
@RestController
@RequestMapping("/api/lm")
@RequiredArgsConstructor
@Slf4j
public class LmIntegrationController {

    private final LmApiProxyService lmApiProxyService;
    private final AllocationEngineService allocationEngineService;
    private final InMemoryStore store;

    /** Check Keycloak token status and LM config. */
    @GetMapping("/token-status")
    public ResponseEntity<Map<String, Object>> getTokenStatus() {
        return ResponseEntity.ok(lmApiProxyService.getTokenStatus());
    }

    /** Force-refresh Keycloak token. */
    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, Object>> refreshToken() {
        try {
            // Calling getToken with null forces auto-refresh
            String token = lmApiProxyService.getToken(null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("tokenPreview", token.substring(0, Math.min(token.length(), 30)) + "...");
            result.putAll(lmApiProxyService.getTokenStatus());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** Get LM delivery users (SRs) for hub 614. */
    @PostMapping("/delivery-users")
    public ResponseEntity<Map<String, Object>> getDeliveryUsers(
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {
        List<Map<String, Object>> users = lmApiProxyService.fetchDeliveryUsers(authToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", users.size());
        result.put("users", users);
        return ResponseEntity.ok(result);
    }

    /** Get LM dashboard counts (pending/allocated). */
    @PostMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {
        return ResponseEntity.ok(lmApiProxyService.getDashboardCounts(authToken));
    }

    /** Get pending shipments for an SR. */
    @PostMapping("/pending")
    public ResponseEntity<Map<String, Object>> getPending(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {
        int srId = request.containsKey("srId") ? ((Number) request.get("srId")).intValue() : 0;
        return ResponseEntity.ok(lmApiProxyService.getPendingShipments(srId, authToken));
    }

    /** Get allocated shipments for an SR. */
    @PostMapping("/allocated")
    public ResponseEntity<Map<String, Object>> getAllocated(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {
        int srId = request.containsKey("srId") ? ((Number) request.get("srId")).intValue() : 0;
        return ResponseEntity.ok(lmApiProxyService.getAllocatedShipments(srId, authToken));
    }

    /**
     * Push a single SR's route to LM API.
     * Allocates all shipments in the route to the specified delivery user.
     */
    @PostMapping("/push-route")
    public ResponseEntity<Map<String, Object>> pushRoute(
            @RequestBody PushRouteRequest request,
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {

        LocalDate date = AllocationController.parseDate(request.date);
        SrRouteDto route = allocationEngineService.getSrRoute(date, request.srName);

        List<String> shippingIds = route.stops().stream()
                .map(s -> s.shippingId())
                .collect(Collectors.toList());

        log.info("Pushing {} shipments for {} → LM user {} ({})",
                shippingIds.size(), request.srName, request.deliveryUserId, request.deliveryUserName);

        Map<String, Object> result = lmApiProxyService.bulkAllocateForSr(
                shippingIds, request.deliveryUserId, request.deliveryUserName,
                request.empType, request.allocationType, authToken);

        result.put("srName", request.srName);
        result.put("date", request.date);
        return ResponseEntity.ok(result);
    }

    /** Confirm allocation for an SR (mark OFD, create trip). */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmAllocation(
            @RequestBody ConfirmRequest request,
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {

        Map<String, Object> result = lmApiProxyService.confirmAllocation(
                request.deliveryUserId, request.srName,
                request.vehicleNo, request.vehicleType, request.tripType, authToken);

        result.put("srName", request.srName);
        return ResponseEntity.ok(result);
    }

    /**
     * Push ALL routes at once: allocate + optionally confirm for every SR.
     */
    @PostMapping("/push-all")
    public ResponseEntity<Map<String, Object>> pushAllRoutes(
            @RequestBody PushAllRequest request,
            @RequestHeader(value = "X-LM-Auth", required = false) String authToken) {

        LocalDate date = AllocationController.parseDate(request.date);
        List<Map<String, Object>> srResults = new ArrayList<>();
        int totalSuccess = 0, totalFailed = 0;

        for (SrMapping mapping : request.srMappings) {
            try {
                SrRouteDto route = allocationEngineService.getSrRoute(date, mapping.srName);
                List<String> shippingIds = route.stops().stream()
                        .map(s -> s.shippingId())
                        .collect(Collectors.toList());

                log.info("Push-all: {} → {} ({} shipments)",
                        mapping.srName, mapping.deliveryUserName, shippingIds.size());

                // Step 1: Allocate all shipments
                Map<String, Object> allocResult = lmApiProxyService.bulkAllocateForSr(
                        shippingIds, mapping.deliveryUserId, mapping.deliveryUserName,
                        mapping.empType != null ? mapping.empType : "ownsr",
                        request.allocationType != null ? request.allocationType : "Delivery",
                        authToken);

                int srSuccess = (int) allocResult.getOrDefault("success", 0);
                int srFailed = (int) allocResult.getOrDefault("failed", 0);
                totalSuccess += srSuccess;
                totalFailed += srFailed;

                // Step 2: Confirm allocation if requested
                Map<String, Object> confirmResult = null;
                if (request.autoConfirm && srSuccess > 0) {
                    confirmResult = lmApiProxyService.confirmAllocation(
                            mapping.deliveryUserId, mapping.deliveryUserName,
                            mapping.vehicleNo,
                            mapping.vehicleType != null ? mapping.vehicleType : "bike",
                            request.tripType != null ? request.tripType : "Delivery",
                            authToken);
                }

                Map<String, Object> srResult = new LinkedHashMap<>();
                srResult.put("srName", mapping.srName);
                srResult.put("deliveryUserId", mapping.deliveryUserId);
                srResult.put("deliveryUserName", mapping.deliveryUserName);
                srResult.put("shipmentCount", shippingIds.size());
                srResult.put("allocated", srSuccess);
                srResult.put("failed", srFailed);
                if (confirmResult != null) srResult.put("confirmResult", confirmResult);
                srResults.add(srResult);

            } catch (Exception e) {
                log.error("Push-all failed for {}: {}", mapping.srName, e.getMessage());
                srResults.add(Map.of("srName", mapping.srName, "error", e.getMessage()));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", request.date);
        summary.put("totalSrs", request.srMappings.size());
        summary.put("totalAllocated", totalSuccess);
        summary.put("totalFailed", totalFailed);
        summary.put("autoConfirm", request.autoConfirm);
        summary.put("srResults", srResults);
        return ResponseEntity.ok(summary);
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public static class PushRouteRequest {
        public String date;
        public String srName;
        public String deliveryUserId;
        public String deliveryUserName;
        public String empType = "ownsr";
        public String allocationType = "Delivery";
    }

    public static class ConfirmRequest {
        public String deliveryUserId;
        public String srName;
        public String vehicleNo;
        public String vehicleType = "bike";
        public String tripType = "Delivery";
    }

    public static class PushAllRequest {
        public String date;
        public List<SrMapping> srMappings;
        public boolean autoConfirm = true;
        public String allocationType = "Delivery";
        public String tripType = "Delivery";
    }

    public static class SrMapping {
        public String srName;
        public String deliveryUserId;
        public String deliveryUserName;
        public String empType = "ownsr";
        public String vehicleNo;
        public String vehicleType;
    }
}
