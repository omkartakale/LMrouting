package com.example.LMrouting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Proxy service to call the XpressBees Last Mile Allocation & POD APIs
 * on the Stage environment.
 *
 * Handles:
 *  - Keycloak client_credentials token auto-refresh
 *  - All required headers (Authorization, x-auth-type)
 *  - Allocate, Confirm, Dashboard, Delivery Users
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LmApiProxyService {

    private final RestTemplate restTemplate;

    // ── Stage config (from application.properties) ────────────────────────────
    @Value("${lm.api.base-url:https://lastmileallocationpod-apistage.xbees.in}")
    private String lmBaseUrl;

    @Value("${lm.api.xbkey:$LAST$Mile%#$}")
    private String xbKey;

    @Value("${lm.api.hub-id:614}")
    private int hubId;

    @Value("${lm.api.delay-ms:200}")
    private long delayBetweenCalls;

    // ── Keycloak config ───────────────────────────────────────────────────────
    @Value("${lm.keycloak.auth-url:https://stage-auth.xbees.in/auth/realms/xpressbees/protocol/openid-connect/token}")
    private String keycloakTokenUrl;

    @Value("${lm.keycloak.client-id:lastmile-service}")
    private String keycloakClientId;

    @Value("${lm.keycloak.client-secret:nU1SYrqp75SrCKuo7wnNgzokBd7YNVUN}")
    private String keycloakClientSecret;

    // ── Token cache ───────────────────────────────────────────────────────────
    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    // =========================================================================
    // Keycloak Token Management
    // =========================================================================

    /**
     * Get a valid Bearer token. Auto-refreshes when expired.
     * If a manual token is provided (non-blank), use that instead.
     */
    public synchronized String getToken(String manualToken) {
        if (manualToken != null && !manualToken.isBlank()) {
            return manualToken.startsWith("Bearer ") ? manualToken : "Bearer " + manualToken;
        }
        // Auto-fetch via client_credentials
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry)) {
            refreshToken();
        }
        return "Bearer " + cachedToken;
    }

    private void refreshToken() {
        log.info("Refreshing Keycloak token from {}", keycloakTokenUrl);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", keycloakClientId);
            form.add("client_secret", keycloakClientSecret);
            form.add("grant_type", "client_credentials");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    keycloakTokenUrl, HttpMethod.POST,
                    new HttpEntity<>(form, headers), Map.class);

            Map body = resp.getBody();
            if (body != null && body.containsKey("access_token")) {
                cachedToken = (String) body.get("access_token");
                int expiresIn = body.containsKey("expires_in")
                        ? ((Number) body.get("expires_in")).intValue() : 300;
                // Refresh 30s before actual expiry
                tokenExpiry = Instant.now().plusSeconds(Math.max(expiresIn - 30, 10));
                log.info("Keycloak token refreshed, expires in {}s", expiresIn);
            } else {
                log.error("Keycloak token response missing access_token: {}", body);
                throw new RuntimeException("Failed to get Keycloak token");
            }
        } catch (Exception e) {
            log.error("Keycloak token refresh failed: {}", e.getMessage());
            throw new RuntimeException("Keycloak auth failed: " + e.getMessage(), e);
        }
    }

    /** Build standard headers for LM API calls. */
    private HttpHeaders buildHeaders(String manualToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", getToken(manualToken));
        headers.set("x-auth-type", "lm-kavach-web");
        return headers;
    }

    // =========================================================================
    // Allocate a single shipment
    // =========================================================================

    public Map<String, Object> allocateShipment(String shippingId, String deliveryUserId,
                                                 String deliveryUserName, String empType,
                                                 String allocationType, String manualToken) {
        String url = lmBaseUrl + "/expose/shipment/allocate";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("XBkey", xbKey);
        body.put("HubId", String.valueOf(hubId));
        body.put("DeliveryUserId", deliveryUserId);
        body.put("ShippingID", shippingId);
        body.put("LastModifiedBy", "v3-optimizer@xpressbees.com");
        body.put("IsConfirmAllocation", false);
        body.put("AllocationType", allocationType != null ? allocationType : "Delivery");
        body.put("EmpType", empType != null ? empType : "ownsr");
        body.put("MarkedFrom", "From Web");
        body.put("CourierCompanyID", null);
        body.put("IsRSVCSr", 0);
        body.put("MobileNo", "");
        body.put("OldDeliveryUserOrUserId", "");
        body.put("DeliveryUserName", deliveryUserName);
        body.put("isTripAllowedForSR", true);
        body.put("vehicleno", "");
        body.put("isReassign", false);
        body.put("IsLongPinCodeFlag", false);
        body.put("IsManualInScan", false);
        body.put("isAllocationFrom", "New");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);
            Map<String, Object> result = response.getBody() != null
                    ? new LinkedHashMap<>(response.getBody())
                    : new LinkedHashMap<>(Map.of("code", response.getStatusCode().value()));
            log.info("LM allocate {} → {} : code={}, msg={}", shippingId, deliveryUserId,
                    result.get("code"), result.get("message"));
            return result;
        } catch (Exception e) {
            log.error("LM allocate failed for {}: {}", shippingId, e.getMessage());
            return Map.of("code", 500, "message", "Error: " + e.getMessage(), "shippingId", shippingId);
        }
    }

    // =========================================================================
    // Bulk allocate: all shipments for one SR with delay between calls
    // =========================================================================

    public Map<String, Object> bulkAllocateForSr(List<String> shippingIds, String deliveryUserId,
                                                  String deliveryUserName, String empType,
                                                  String allocationType, String manualToken) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0, failed = 0;

        for (int i = 0; i < shippingIds.size(); i++) {
            String sid = shippingIds.get(i);
            Map<String, Object> result = allocateShipment(sid, deliveryUserId,
                    deliveryUserName, empType, allocationType, manualToken);

            Object code = result.get("code");
            boolean ok = code != null && (code.equals(200) || code.equals("200"));
            if (ok) success++;
            else failed++;

            result.put("shippingId", sid);
            results.add(result);

            // Delay between calls (except last)
            if (i < shippingIds.size() - 1 && delayBetweenCalls > 0) {
                try { Thread.sleep(delayBetweenCalls); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", shippingIds.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("deliveryUserId", deliveryUserId);
        summary.put("deliveryUserName", deliveryUserName);
        summary.put("results", results);
        return summary;
    }

    // =========================================================================
    // Confirm allocation (mark OFD + create trip)
    // =========================================================================

    public Map<String, Object> confirmAllocation(String deliveryUserId, String srName,
                                                  String vehicleNo, String vehicleType,
                                                  String tripType, String manualToken) {
        String url = lmBaseUrl + "/expose/shipment/confirm";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("XBkey", xbKey);
        body.put("sruserid", deliveryUserId);
        body.put("hubid", hubId);
        body.put("isRoutePlanner", false);
        body.put("isHandoverDeliveryUser", 0);
        body.put("km", 0);
        body.put("tripstate", "created");
        body.put("vehicleno", vehicleNo != null ? vehicleNo : "");
        body.put("vehicletype", vehicleType != null ? vehicleType : "bike");
        body.put("triptype", tripType != null ? tripType : "Delivery");
        body.put("srname", srName);
        body.put("isvehiclereplaced", false);
        body.put("isvehicleupdated", false);
        body.put("vehiclevendorid", 0);
        body.put("MarkedFrom", "From Web");
        body.put("LastModifiedBy", "v3-optimizer@xpressbees.com");
        body.put("CourierCompanyID", null);
        body.put("operationType", "Delivery");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);
            Map<String, Object> result = response.getBody() != null
                    ? new LinkedHashMap<>(response.getBody())
                    : new LinkedHashMap<>(Map.of("code", response.getStatusCode().value()));
            log.info("LM confirm {} : code={}, msg={}", deliveryUserId, result.get("code"), result.get("message"));
            return result;
        } catch (Exception e) {
            log.error("LM confirm failed for {}: {}", deliveryUserId, e.getMessage());
            return Map.of("code", 500, "message", "Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Fetch hub delivery users (SRs)
    // =========================================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchDeliveryUsers(String manualToken) {
        String url = lmBaseUrl + "/expose/users/HubDeliveryUsers/get/List";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usertype", 3);
        body.put("hubid", List.of(hubId));

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);

            Map respBody = response.getBody();
            if (respBody != null) {
                // Response: { data: { code: 200, results: [...] } }
                Object dataObj = respBody.get("data");
                if (dataObj instanceof Map) {
                    Map dataMap = (Map) dataObj;
                    Object results = dataMap.get("results");
                    if (results instanceof List) {
                        return (List<Map<String, Object>>) results;
                    }
                }
                // Fallback: data is directly a list
                if (dataObj instanceof List) {
                    return (List<Map<String, Object>>) dataObj;
                }
            }
            return List.of();
        } catch (Exception e) {
            log.error("LM fetchDeliveryUsers failed: {}", e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // Dashboard counts (pending/allocated)
    // =========================================================================

    public Map<String, Object> getDashboardCounts(String manualToken) {
        String url = lmBaseUrl + "/allocation/dashboardcounts";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hubId", hubId);
        body.put("srId", 0);
        body.put("shipmentType", "Delivery");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);
            return response.getBody() != null
                    ? new LinkedHashMap<>(response.getBody())
                    : Map.of();
        } catch (Exception e) {
            log.error("LM dashboardCounts failed: {}", e.getMessage());
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    // =========================================================================
    // Get pending shipment list
    // =========================================================================

    public Map<String, Object> getPendingShipments(int srId, String manualToken) {
        String url = lmBaseUrl + "/allocation/shipmentlist";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hubId", hubId);
        body.put("srId", srId);
        body.put("type", "pending");
        body.put("shipmentType", "Delivery");
        body.put("requestFrom", "web");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);
            return response.getBody() != null
                    ? new LinkedHashMap<>(response.getBody())
                    : Map.of();
        } catch (Exception e) {
            log.error("LM pendingShipments failed: {}", e.getMessage());
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    // =========================================================================
    // Get allocated shipment list
    // =========================================================================

    public Map<String, Object> getAllocatedShipments(int srId, String manualToken) {
        String url = lmBaseUrl + "/allocation/shipmentlist";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hubId", hubId);
        body.put("srId", srId);
        body.put("type", "allocated");
        body.put("shipmentType", "Delivery");
        body.put("requestFrom", "web");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders(manualToken)), Map.class);
            return response.getBody() != null
                    ? new LinkedHashMap<>(response.getBody())
                    : Map.of();
        } catch (Exception e) {
            log.error("LM allocatedShipments failed: {}", e.getMessage());
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    // =========================================================================
    // Expose token status for frontend
    // =========================================================================

    public Map<String, Object> getTokenStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("hasToken", cachedToken != null);
        status.put("expired", Instant.now().isAfter(tokenExpiry));
        status.put("expiresAt", tokenExpiry.toString());
        status.put("baseUrl", lmBaseUrl);
        status.put("hubId", hubId);
        return status;
    }
}
