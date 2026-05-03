package com.example.LMrouting.service;

import com.example.LMrouting.dto.HubBoundaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Fetches hub boundary polygon from 3 sequential Xpressbees internal APIs.
 *
 * Flow:
 *   1. POST auth token (openid-connect)
 *   2. GET verticals → find layerId for "Forward_Delivery"
 *   3. POST search with hub city → find facility by hub name → extract boundary coordinates
 *
 * All calls have an 8-second timeout (configured on the RestTemplate bean).
 * Each call is retried once on failure before giving up.
 */
@Service
@Slf4j
public class HubBoundaryService {

    // ── External API endpoints ────────────────────────────────────────────────
    private static final String AUTH_URL =
            "https://stage-auth.xbees.in/realms/xpressbees/protocol/openid-connect/token";
    private static final String VERTICALS_URL =
            "https://preprod-orchestration-service.xbees.in/orchestration/api/v1/verticals";
    private static final String SEARCH_URL_TEMPLATE =
            "https://preprod-orchestration-service.xbees.in/orchestration/api/v1/search?layerId=%s";

    // ── Auth credentials ──────────────────────────────────────────────────────
    private static final String AUTH_USERNAME  = "sreenivasulu.tallapalem@xpressbees.com";
    private static final String AUTH_PASSWORD  = "Sreeni@54621918#";
    private static final String AUTH_CLIENT_ID = "geo_intelligence_ui";

    // ── Layer context to match ────────────────────────────────────────────────
    private static final String TARGET_LAYER_CONTEXT = "Forward_Delivery";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HubBoundaryService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Main entry point. Returns null if any step fails (caller handles gracefully).
     *
     * @param hubName e.g. "PNQ HDP"
     * @return HubBoundaryResponse with [lat,lng] coordinate list, or null on failure
     */
    public HubBoundaryResponse fetchBoundary(String hubName) {
        try {
            // Step 1: Get auth token
            String bearerToken = withRetry(() -> fetchAuthToken());
            if (bearerToken == null) {
                log.warn("HubBoundary: auth token fetch failed, skipping boundary display");
                return null;
            }

            // Step 2: Get layer ID for Forward_Delivery
            String layerId = withRetry(() -> fetchLayerId());
            if (layerId == null) {
                log.warn("HubBoundary: could not find Forward_Delivery layer, skipping boundary display");
                return null;
            }

            // Step 3: Search for hub boundary (both boundary + originalBoundary)
            String city = deriveCity(hubName);
            FacilityBoundaries fb = withRetry(() -> fetchFacilityBoundaries(bearerToken, layerId, city, hubName));
            if (fb == null || fb.boundary() == null) {
                log.warn("HubBoundary: no boundary coordinates found for hub '{}' in city '{}'", hubName, city);
                return null;
            }

            log.info("HubBoundary: fetched {} boundary points (+ {} originalBoundary points) for hub '{}'",
                     fb.boundary().size(),
                     fb.originalBoundary() != null ? fb.originalBoundary().size() : 0,
                     hubName);

            return new HubBoundaryResponse(hubName, fb.facilityName(), fb.boundary(), fb.originalBoundary());

        } catch (Exception e) {
            log.warn("HubBoundary: unexpected error fetching boundary for '{}': {}", hubName, e.getMessage());
            return null;
        }
    }

    // ── Step 1: Auth token ────────────────────────────────────────────────────

    private String fetchAuthToken() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "password");
            body.add("client_id", AUTH_CLIENT_ID);
            body.add("username", AUTH_USERNAME);
            body.add("password", AUTH_PASSWORD);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(AUTH_URL, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("HubBoundary: auth returned status {}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String token = root.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                log.debug("HubBoundary: access_token missing in auth response");
                return null;
            }
            return "Bearer " + token;

        } catch (Exception e) {
            log.debug("HubBoundary: auth call failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Step 2: Get layer ID ──────────────────────────────────────────────────

    private String fetchLayerId() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(VERTICALS_URL, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("HubBoundary: verticals returned status {}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());

            // Response may be an array directly or wrapped in a data field
            JsonNode items = root.isArray() ? root : root.path("data");
            if (!items.isArray()) {
                log.debug("HubBoundary: unexpected verticals response structure");
                return null;
            }

            for (JsonNode item : items) {
                String layerContext = item.path("layerContext").asText("");
                if (TARGET_LAYER_CONTEXT.equals(layerContext)) {
                    String id = item.path("id").asText(null);
                    if (id != null && !id.isBlank()) {
                        log.debug("HubBoundary: found layerId={} for context={}", id, TARGET_LAYER_CONTEXT);
                        return id;
                    }
                }
            }

            log.debug("HubBoundary: no entry with layerContext='{}' found", TARGET_LAYER_CONTEXT);
            return null;

        } catch (Exception e) {
            log.debug("HubBoundary: verticals call failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Step 3: Search for boundary ───────────────────────────────────────────

    /**
     * Holds both boundary geometries extracted from a single facility node.
     */
    private record FacilityBoundaries(
            String facilityName,
            List<double[]> boundary,
            List<double[]> originalBoundary
    ) {}

    /**
     * Fetches both boundary and originalBoundary for the best-matching facility.
     *
     * Strategy:
     *   Attempt 1 — search directly by hub name (searchKey = "PNQ HDP", searchType = "facility")
     *               This returns a narrow result set, making exact matching trivial.
     *   Attempt 2 — fall back to city search (searchKey = "PUNE", searchType = "city")
     *               and apply strict ALL-TOKEN matching so "PNQ/VSW" never beats "PNQ HDP".
     *
     * Logs all facility names returned so mismatches are visible in the Spring Boot console.
     */
    FacilityBoundaries fetchFacilityBoundaries(String bearerToken, String layerId, String city, String hubName) {
        // ── Attempt 1: search by hub name directly ────────────────────────────
        FacilityBoundaries direct = searchAndMatch(bearerToken, layerId, hubName, "facility", hubName);
        if (direct != null) {
            log.info("HubBoundary: found facility via direct hub-name search: '{}'", direct.facilityName());
            return direct;
        }

        // ── Attempt 2: fall back to city search ───────────────────────────────
        log.info("HubBoundary: direct search returned nothing, falling back to city search for '{}'", city);
        return searchAndMatch(bearerToken, layerId, city, "city", hubName);
    }

    /**
     * Calls the search API with the given searchKey/searchType, then picks the best
     * matching facility for hubName using strict ALL-TOKEN matching.
     */
    private FacilityBoundaries searchAndMatch(String bearerToken, String layerId,
                                               String searchKey, String searchType, String hubName) {
        try {
            String url = String.format(SEARCH_URL_TEMPLATE, layerId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", bearerToken);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("autocomplete", false);
            body.put("searchKey", searchKey);
            body.put("searchType", searchType);
            body.put("isSaveAsDraft", true);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("HubBoundary: search ({}) returned status {}", searchKey, response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode facilities = root.path("data").path("facilities");
            if (!facilities.isArray()) facilities = root.path("facilities");
            if (!facilities.isArray() || facilities.isEmpty()) {
                log.debug("HubBoundary: no facilities for searchKey='{}'", searchKey);
                return null;
            }

            // Log all names for diagnostics
            List<String> allNames = new ArrayList<>();
            for (JsonNode f : facilities) {
                allNames.add(f.path("name").asText("?") + " [id=" + f.path("id").asText("?") + "]");
            }
            log.info("HubBoundary: searchKey='{}' returned {} facilities: {}", searchKey, allNames.size(), allNames);

            JsonNode best = pickBestFacility(facilities, hubName);
            if (best == null) {
                log.warn("HubBoundary: no acceptable match for hub '{}' in results for searchKey='{}'", hubName, searchKey);
                return null;
            }

            String facilityName = best.path("name").asText(hubName);
            log.info("HubBoundary: matched facility '{}' for hub '{}'", facilityName, hubName);

            List<double[]> boundary         = extractRing(best.path("boundary"),         facilityName, "boundary");
            List<double[]> originalBoundary = extractRing(best.path("originalBoundary"), facilityName, "originalBoundary");

            if (boundary == null && originalBoundary == null) {
                log.warn("HubBoundary: facility '{}' has neither boundary nor originalBoundary", facilityName);
                return null;
            }
            if (boundary == null) {
                log.info("HubBoundary: primary boundary missing for '{}', promoting originalBoundary", facilityName);
                boundary = originalBoundary;
                originalBoundary = null;
            }

            return new FacilityBoundaries(facilityName, boundary, originalBoundary);

        } catch (Exception e) {
            log.debug("HubBoundary: search call failed for searchKey='{}': {}", searchKey, e.getMessage());
            return null;
        }
    }

    /**
     * Picks the best facility from a list for the given hubName.
     *
     * Matching priority (all case-insensitive, normalised — slashes/hyphens treated as spaces):
     *   1. Exact match after normalisation          "PNQ HDP" == "PNQ HDP"
     *   2. All tokens of hubName present in apiName "PNQ" ∈ name AND "HDP" ∈ name
     *   3. All tokens of apiName present in hubName (only if apiName has ≥2 tokens)
     *
     * Deliberately does NOT fall back to prefix-only matching (which caused PNQ/VSW to win).
     * Returns null if no match passes any of the 3 passes — caller decides what to do.
     */
    private JsonNode pickBestFacility(JsonNode facilities, String hubName) {
        // Normalise: lowercase, replace / and - with space, collapse whitespace
        String normHub = normalise(hubName);
        String[] hubTokens = normHub.split("\\s+");

        // Pass 1: exact normalised match
        for (JsonNode f : facilities) {
            if (normalise(f.path("name").asText("")).equals(normHub)) return f;
        }

        // Pass 2: every token of hubName appears in the API facility name
        // e.g. hubTokens = ["pnq","hdp"] — both must be present in the API name
        for (JsonNode f : facilities) {
            String normApi = normalise(f.path("name").asText(""));
            if (allTokensPresent(hubTokens, normApi)) return f;
        }

        // Pass 3: every token of the API name appears in hubName (only for multi-token API names)
        // This handles cases where the API name is a subset of our hub name
        for (JsonNode f : facilities) {
            String normApi = normalise(f.path("name").asText(""));
            String[] apiTokens = normApi.split("\\s+");
            if (apiTokens.length >= 2 && allTokensPresent(apiTokens, normHub)) return f;
        }

        return null; // no match — do not fall back to first-in-list
    }

    /** Lowercase, replace / and - with space, collapse whitespace. */
    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('/', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Returns true if every token in needles[] appears as a word in haystack. */
    private boolean allTokensPresent(String[] needles, String haystack) {
        for (String token : needles) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    /**
     * Extracts a GeoJSON ring from a geometry node and swaps [lng,lat] → [lat,lng].
     * Handles both Polygon (coordinates[0]) and direct array structures.
     * Returns null if the geometry is absent or malformed.
     */
    private List<double[]> extractRing(JsonNode geometryParent, String facilityName, String fieldName) {
        if (geometryParent == null || geometryParent.isMissingNode() || geometryParent.isNull()) {
            return null;
        }

        JsonNode coordsNode = geometryParent.path("geometry").path("coordinates");
        if (coordsNode.isMissingNode() || coordsNode.isNull()) {
            // Try direct coordinates field (some APIs omit the geometry wrapper)
            coordsNode = geometryParent.path("coordinates");
        }
        if (coordsNode.isMissingNode() || coordsNode.isNull() || !coordsNode.isArray()) {
            log.debug("HubBoundary: '{}' field missing or not array for facility '{}'", fieldName, facilityName);
            return null;
        }

        // GeoJSON Polygon: coordinates[0] is the outer ring
        // If the first element is itself an array of arrays, it's the ring wrapper
        JsonNode ring;
        if (coordsNode.size() > 0 && coordsNode.get(0).isArray() && coordsNode.get(0).size() > 0
                && coordsNode.get(0).get(0).isArray()) {
            ring = coordsNode.get(0); // standard GeoJSON Polygon outer ring
        } else {
            ring = coordsNode; // already a flat array of [lng,lat] points
        }

        if (!ring.isArray() || ring.isEmpty()) {
            log.debug("HubBoundary: empty ring in '{}' for facility '{}'", fieldName, facilityName);
            return null;
        }

        List<double[]> result = new ArrayList<>();
        for (JsonNode point : ring) {
            if (point.isArray() && point.size() >= 2) {
                double lng = point.get(0).asDouble();
                double lat = point.get(1).asDouble();
                result.add(new double[]{lat, lng}); // swap: Leaflet wants [lat, lng]
            }
        }

        log.debug("HubBoundary: extracted {} points from '{}' for facility '{}'",
                  result.size(), fieldName, facilityName);
        return result.isEmpty() ? null : result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Derives a city search key from a hub name.
     * Examples: "PNQ HDP" → "PUNE", "BLR HDP" → "BANGALORE", "DEL HDP" → "DELHI"
     */
    private String deriveCity(String hubName) {
        if (hubName == null) return "";
        String prefix = hubName.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
        return switch (prefix) {
            case "PNQ" -> "PUNE";
            case "BLR" -> "BANGALORE";
            case "DEL", "DLH" -> "DELHI";
            case "MUM", "BOM" -> "MUMBAI";
            case "HYD" -> "HYDERABAD";
            case "CHN", "MAA" -> "CHENNAI";
            case "KOL", "CCU" -> "KOLKATA";
            case "AMD" -> "AHMEDABAD";
            case "JAI" -> "JAIPUR";
            case "LKO" -> "LUCKNOW";
            case "SUR" -> "SURAT";
            case "NGP" -> "NAGPUR";
            case "IND" -> "INDORE";
            case "BHO" -> "BHOPAL";
            case "PAT" -> "PATNA";
            case "VNS" -> "VARANASI";
            case "AGR" -> "AGRA";
            case "VIZ", "VTZ" -> "VISAKHAPATNAM";
            case "COK", "COC" -> "KOCHI";
            default -> prefix; // fallback: use the prefix itself as city
        };
    }

    /**
     * Executes a supplier with one retry on null/exception result.
     */
    private <T> T withRetry(ThrowingSupplier<T> supplier) {
        try {
            T result = supplier.get();
            if (result != null) return result;
        } catch (Exception e) {
            log.debug("HubBoundary: first attempt failed: {}", e.getMessage());
        }
        // One retry
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("HubBoundary: retry also failed: {}", e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
