package com.example.LMrouting.service;

import com.example.LMrouting.dto.RouteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Integrates with Google Maps Directions API to get optimized routes.
 * Falls back to straight-line (Haversine) distance when API key is not configured.
 */
@Service
@Slf4j
public class GoogleMapsService {

    @Value("${google.maps.api.key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_GOOGLE_MAPS_API_KEY");
    }

    /**
     * Get per-leg travel durations (minutes) for a sequence of waypoints.
     * Returns one duration per leg: [hub→stop1, stop1→stop2, ..., stopN→hub].
     * Falls back to Haversine-based estimate (20 km/h) if API unavailable.
     *
     * @param originLat  hub latitude
     * @param originLng  hub longitude
     * @param waypoints  ordered list of [lat, lng] pairs (already sequenced)
     * @return list of travel durations in minutes, size = waypoints.size() + 1 (includes return leg)
     */
    public List<Double> getLegDurationsMinutes(double originLat, double originLng,
                                                List<double[]> waypoints) {
        List<Double> durations = new ArrayList<>();
        if (waypoints.isEmpty()) return durations;

        if (!isApiKeyConfigured()) {
            return fallbackLegDurations(originLat, originLng, waypoints);
        }

        try {
            // Build a route with the waypoints in the given order (no optimization)
            String origin = originLat + "," + originLng;

            // Google Directions API: max 25 waypoints
            List<double[]> limited = waypoints.size() > 23 ? waypoints.subList(0, 23) : waypoints;

            StringBuilder waypointStr = new StringBuilder();
            for (int i = 0; i < limited.size() - 1; i++) {
                if (waypointStr.length() > 0) waypointStr.append("|");
                waypointStr.append(limited.get(i)[0]).append(",").append(limited.get(i)[1]);
            }

            String destination = limited.get(limited.size() - 1)[0] + "," + limited.get(limited.size() - 1)[1];

            String url = "https://maps.googleapis.com/maps/api/directions/json"
                    + "?origin=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(destination, StandardCharsets.UTF_8)
                    + (waypointStr.length() > 0
                        ? "&waypoints=" + URLEncoder.encode(waypointStr.toString(), StandardCharsets.UTF_8)
                        : "")
                    + "&key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("Google Directions API (legs) error: {}", root.path("status").asText());
                return fallbackLegDurations(originLat, originLng, waypoints);
            }

            JsonNode legs = root.path("routes").get(0).path("legs");
            for (JsonNode leg : legs) {
                durations.add(leg.path("duration").path("value").asDouble() / 60.0);
            }

            // Add return leg: last stop → hub
            String lastStop = limited.get(limited.size() - 1)[0] + "," + limited.get(limited.size() - 1)[1];
            String returnUrl = "https://maps.googleapis.com/maps/api/directions/json"
                    + "?origin=" + URLEncoder.encode(lastStop, StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&key=" + apiKey;

            HttpRequest returnReq = HttpRequest.newBuilder().uri(URI.create(returnUrl)).GET().build();
            HttpResponse<String> returnResp = httpClient.send(returnReq, HttpResponse.BodyHandlers.ofString());
            JsonNode returnRoot = objectMapper.readTree(returnResp.body());
            if ("OK".equals(returnRoot.path("status").asText())) {
                JsonNode returnLegs = returnRoot.path("routes").get(0).path("legs");
                double returnDur = 0;
                for (JsonNode leg : returnLegs) returnDur += leg.path("duration").path("value").asDouble() / 60.0;
                durations.add(returnDur);
            } else {
                // Fallback for return leg
                double[] last = limited.get(limited.size() - 1);
                durations.add(haversine(last[0], last[1], originLat, originLng) / 20.0 * 60.0);
            }

            return durations;

        } catch (Exception e) {
            log.warn("Error getting leg durations from Google Maps: {}", e.getMessage());
            return fallbackLegDurations(originLat, originLng, waypoints);
        }
    }

    /** Fallback leg durations using Haversine + 20 km/h city speed. */
    private List<Double> fallbackLegDurations(double originLat, double originLng, List<double[]> waypoints) {
        List<Double> durations = new ArrayList<>();
        double prevLat = originLat, prevLng = originLng;
        for (double[] wp : waypoints) {
            double distKm = haversine(prevLat, prevLng, wp[0], wp[1]);
            durations.add(distKm / 20.0 * 60.0); // 20 km/h city speed
            prevLat = wp[0]; prevLng = wp[1];
        }
        // Return to hub
        durations.add(haversine(prevLat, prevLng, originLat, originLng) / 20.0 * 60.0);
        return durations;
    }

    /**
     * Get directions from origin through waypoints.
     * Uses Google Directions API with waypoint optimization.
     *
     * @param originLat  hub latitude
     * @param originLng  hub longitude
     * @param waypoints  list of [lat, lng] pairs
     * @return DirectionsResult with optimized order, distance, duration, polyline
     */
    public DirectionsResult getOptimizedRoute(double originLat, double originLng,
                                               List<double[]> waypoints) {
        if (!isApiKeyConfigured()) {
            return fallbackDirections(originLat, originLng, waypoints);
        }

        try {
            String origin = originLat + "," + originLng;

            // Google Directions API supports max 25 waypoints
            List<double[]> limitedWaypoints = waypoints.size() > 25
                    ? waypoints.subList(0, 25) : waypoints;

            StringBuilder waypointStr = new StringBuilder("optimize:true");
            for (double[] wp : limitedWaypoints) {
                waypointStr.append("|").append(wp[0]).append(",").append(wp[1]);
            }

            String url = "https://maps.googleapis.com/maps/api/directions/json"
                    + "?origin=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&waypoints=" + URLEncoder.encode(waypointStr.toString(), StandardCharsets.UTF_8)
                    + "&key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("Google Directions API error: {}", root.path("status").asText());
                return fallbackDirections(originLat, originLng, waypoints);
            }

            JsonNode route = root.path("routes").get(0);
            JsonNode legs = route.path("legs");

            // Get optimized waypoint order
            List<Integer> waypointOrder = new ArrayList<>();
            JsonNode orderNode = route.path("waypoint_order");
            if (orderNode.isArray()) {
                for (JsonNode n : orderNode) {
                    waypointOrder.add(n.asInt());
                }
            }

            // Calculate total distance and duration
            double totalDistanceM = 0;
            double totalDurationS = 0;
            for (JsonNode leg : legs) {
                totalDistanceM += leg.path("distance").path("value").asDouble();
                totalDurationS += leg.path("duration").path("value").asDouble();
            }

            // Decode polyline
            String encodedPolyline = route.path("overview_polyline").path("points").asText();
            List<RouteResponse.LatLng> polylinePoints = decodePolyline(encodedPolyline);

            return new DirectionsResult(
                    waypointOrder,
                    totalDistanceM / 1000.0,
                    totalDurationS / 60.0,
                    polylinePoints
            );

        } catch (Exception e) {
            log.error("Error calling Google Directions API", e);
            return fallbackDirections(originLat, originLng, waypoints);
        }
    }

    /**
     * Fallback: use nearest-neighbor ordering with Haversine distances.
     */
    private DirectionsResult fallbackDirections(double originLat, double originLng,
                                                 List<double[]> waypoints) {
        if (waypoints.isEmpty()) {
            return new DirectionsResult(List.of(), 0, 0, List.of());
        }

        // Nearest-neighbor ordering
        List<Integer> order = new ArrayList<>();
        boolean[] visited = new boolean[waypoints.size()];
        double currentLat = originLat;
        double currentLng = originLng;
        double totalDist = 0;

        for (int i = 0; i < waypoints.size(); i++) {
            double minDist = Double.MAX_VALUE;
            int nearest = -1;
            for (int j = 0; j < waypoints.size(); j++) {
                if (!visited[j]) {
                    double dist = haversine(currentLat, currentLng,
                            waypoints.get(j)[0], waypoints.get(j)[1]);
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = j;
                    }
                }
            }
            if (nearest >= 0) {
                visited[nearest] = true;
                order.add(nearest);
                totalDist += minDist;
                currentLat = waypoints.get(nearest)[0];
                currentLng = waypoints.get(nearest)[1];
            }
        }

        // Add return to hub
        totalDist += haversine(currentLat, currentLng, originLat, originLng);

        // Estimate duration: assume 20 km/h average in city + 2 min per stop
        double durationMin = (totalDist / 20.0) * 60 + waypoints.size() * 2;

        // Build simple polyline from ordered points
        List<RouteResponse.LatLng> polyline = new ArrayList<>();
        polyline.add(new RouteResponse.LatLng(originLat, originLng));
        for (int idx : order) {
            polyline.add(new RouteResponse.LatLng(waypoints.get(idx)[0], waypoints.get(idx)[1]));
        }
        polyline.add(new RouteResponse.LatLng(originLat, originLng));

        return new DirectionsResult(order, totalDist, durationMin, polyline);
    }

    /**
     * Haversine distance in km between two lat/lng points.
     */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Decode Google Maps encoded polyline string.
     */
    private List<RouteResponse.LatLng> decodePolyline(String encoded) {
        List<RouteResponse.LatLng> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return points;

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

            points.add(new RouteResponse.LatLng(lat / 1E5, lng / 1E5));
        }
        return points;
    }

    /**
     * Result from directions calculation.
     */
    public record DirectionsResult(
            List<Integer> waypointOrder,
            double totalDistanceKm,
            double totalDurationMinutes,
            List<RouteResponse.LatLng> polylinePoints
    ) {}
}
