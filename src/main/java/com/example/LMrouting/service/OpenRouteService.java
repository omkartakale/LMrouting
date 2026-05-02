package com.example.LMrouting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * Client for OpenRouteService (ORS) Directions API.
 *
 * ORS is free (2000 req/day on free tier), open-source, and uses OpenStreetMap data
 * which has good coverage for Indian cities.
 *
 * Sign up at: https://openrouteservice.org/dev/#/signup
 * Free tier: 2000 requests/day, 40 requests/minute
 *
 * The API returns actual road geometry (polyline coordinates) so routes follow
 * real roads instead of straight lines.
 */
@Service
@Slf4j
public class OpenRouteService {

    private static final String ORS_BASE_URL = "https://api.openrouteservice.org/v2/directions/driving-car";
    private static final int TIMEOUT_SECONDS = 8;
    private static final int MAX_WAYPOINTS = 50; // ORS free tier supports up to 50 waypoints

    @Value("${ors.api.key:}")
    private String orsApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    public boolean isConfigured() {
        return orsApiKey != null && !orsApiKey.isBlank() && !orsApiKey.equals("YOUR_ORS_API_KEY");
    }

    /**
     * Get optimized route from ORS with actual road geometry.
     *
     * @param coordinates list of [lng, lat] pairs (ORS uses lng,lat order)
     * @return RouteResult with road geometry polyline and total distance
     */
    public RouteResult getRoute(List<double[]> coordinates) {
        if (!isConfigured()) {
            return RouteResult.fallback();
        }

        // Cap at max waypoints
        List<double[]> limited = coordinates.size() > MAX_WAYPOINTS
                ? coordinates.subList(0, MAX_WAYPOINTS) : coordinates;

        try {
            // Build JSON body
            StringBuilder coordsJson = new StringBuilder("[");
            for (int i = 0; i < limited.size(); i++) {
                if (i > 0) coordsJson.append(",");
                coordsJson.append("[").append(limited.get(i)[0]).append(",").append(limited.get(i)[1]).append("]");
            }
            coordsJson.append("]");

            String body = "{\"coordinates\":" + coordsJson + "," +
                    "\"instructions\":false," +
                    "\"geometry\":true," +
                    "\"units\":\"km\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ORS_BASE_URL))
                    .header("Authorization", orsApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, application/geo+json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("ORS API returned status {}: {}", response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                return RouteResult.fallback();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                log.warn("ORS returned no routes");
                return RouteResult.fallback();
            }

            JsonNode route = routes.get(0);
            double totalDistanceKm = route.path("summary").path("distance").asDouble(0.0);
            double totalDurationMin = route.path("summary").path("duration").asDouble(0.0) / 60.0;

            // Decode the encoded polyline geometry
            String encodedGeometry = route.path("geometry").asText("");
            List<double[]> polylinePoints = decodePolyline(encodedGeometry);

            return new RouteResult(polylinePoints, totalDistanceKm, totalDurationMin, true);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ORS request interrupted");
            return RouteResult.fallback();
        } catch (Exception e) {
            log.warn("ORS API error: {}", e.getMessage());
            return RouteResult.fallback();
        }
    }

    /**
     * Decode Google-style encoded polyline (used by ORS).
     * Returns list of [lat, lng] pairs.
     */
    private List<double[]> decodePolyline(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return points;

        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));

            points.add(new double[]{lat / 1E5, lng / 1E5});
        }
        return points;
    }

    public record RouteResult(
            List<double[]> polylinePoints,  // [lat, lng] pairs
            double totalDistanceKm,
            double totalDurationMinutes,
            boolean fromApi
    ) {
        static RouteResult fallback() {
            return new RouteResult(Collections.emptyList(), 0.0, 0.0, false);
        }
    }
}
