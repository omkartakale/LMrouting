package com.example.LMrouting;

import com.example.LMrouting.dto.ScoreWeights;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.*;
import com.example.LMrouting.store.InMemoryStore;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for AllocationEngineService phases 1 and 2.
 * Uses jqwik directly (no Spring context) with a manually wired service instance.
 */
class AllocationEngineServicePBTTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double THRESHOLD = 0.5;
    private static final int MAX_ITERATIONS = 50;

    private AllocationEngineService buildService() {
        ScoreWeights weights = new ScoreWeights(1.0, 0.0, 0.0, 0.0);
        GoogleMapsService googleMapsService = new GoogleMapsService();
        OpenRouteService openRouteService = new OpenRouteService();
        RouteOptimizerService routeOptimizer = new RouteOptimizerService(googleMapsService, openRouteService);
        setField(routeOptimizer, "hubLat", HUB_LAT);
        setField(routeOptimizer, "hubLng", HUB_LNG);

        InMemoryStore storeMock = Mockito.mock(InMemoryStore.class);
        AllocationEngineService service = new AllocationEngineService(storeMock, routeOptimizer, weights);
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "rebalancingThreshold", THRESHOLD);
        setField(service, "maxIterations", MAX_ITERATIONS);
        return service;
    }

    // Feature: shipment-allocation-optimizer, Property 9
    @Property(tries = 100)
    void rebalancingReducesOrMaintainsVariance(
            @ForAll("forwardShipmentLists") List<Shipment> shipments,
            @ForAll("srNameLists") List<String> srNames) {

        AllocationEngineService service = buildService();
        Map<String, List<Shipment>> assignment = invokeAngularSectorPartition(service, shipments, srNames);
        Map<String, Double> preScores = computeScores(assignment);
        double preVariance = CompositeLoadScoreCalculator.variance(preScores);

        Map<String, List<Shipment>> rebalanced = invokeRebalance(service, assignment);
        Map<String, Double> postScores = computeScores(rebalanced);
        double postVariance = CompositeLoadScoreCalculator.variance(postScores);

        assertThat(postVariance)
                .as("Rebalancing should not increase variance (pre=%.4f, post=%.4f)", preVariance, postVariance)
                .isLessThanOrEqualTo(preVariance + 1e-9);
    }

    // Feature: shipment-allocation-optimizer, Property 8
    @Property(tries = 100)
    void everyPresentSrGetsAtLeastOneShipmentWhenSupplySufficient(
            @ForAll("sufficientShipmentScenarios") ShipmentScenario scenario) {

        AllocationEngineService service = buildService();
        assertThat(scenario.shipments().size()).isGreaterThanOrEqualTo(scenario.srNames().size());

        Map<String, List<Shipment>> assignment = invokeAngularSectorPartition(service, scenario.shipments(), scenario.srNames());
        Map<String, List<Shipment>> rebalanced = invokeRebalance(service, assignment);

        for (String sr : scenario.srNames()) {
            assertThat(rebalanced.getOrDefault(sr, Collections.emptyList()))
                    .as("SR '%s' should have at least 1 shipment", sr)
                    .isNotEmpty();
        }
    }

    @Provide
    Arbitrary<List<Shipment>> forwardShipmentLists() {
        return shipmentArbitrary("Forward").list().ofMinSize(2).ofMaxSize(30);
    }

    @Provide
    Arbitrary<List<String>> srNameLists() {
        return Arbitraries.integers().between(2, 8)
                .flatMap(k -> Arbitraries.integers().between(1, 20)
                        .list().ofSize(k)
                        .map(ids -> ids.stream().distinct().limit(k)
                                .map(id -> "SR-" + String.format("%03d", id))
                                .collect(Collectors.toList()))
                        .filter(list -> list.size() == k));
    }

    @Provide
    Arbitrary<ShipmentScenario> sufficientShipmentScenarios() {
        return Arbitraries.integers().between(2, 5).flatMap(k -> {
            List<String> srNames = new ArrayList<>();
            for (int i = 1; i <= k; i++) srNames.add("SR-" + String.format("%03d", i));
            return shipmentArbitrary("Forward").list().ofMinSize(k).ofMaxSize(k * 6)
                    .map(shipments -> new ShipmentScenario(shipments, srNames));
        });
    }

    private Arbitrary<Shipment> shipmentArbitrary(String flow) {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(HUB_LAT - 0.09, HUB_LAT + 0.09).ofScale(7);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(HUB_LNG - 0.09, HUB_LNG + 0.09).ofScale(7);
        Arbitrary<Double> weightArb = Arbitraries.doubles().between(0.1, 50.0).ofScale(2);
        Arbitrary<Integer> heavyArb = Arbitraries.integers().between(0, 1);

        return net.jqwik.api.Combinators.combine(latArb, lngArb, weightArb, heavyArb)
                .as((lat, lng, weight, heavy) -> Shipment.builder()
                        .shippingId(UUID.randomUUID().toString().substring(0, 8))
                        .shipmentFlow(flow).dropLatitude(lat).dropLongitude(lng)
                        .phyWeight(weight).isHeavy(heavy).build());
    }

    private Map<String, Double> computeScores(Map<String, List<Shipment>> assignment) {
        ScoreWeights weights = new ScoreWeights(1.0, 0.0, 0.0, 0.0);
        Map<String, Double> scores = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                scores.put(sr, CompositeLoadScoreCalculator.compute(list, 0.0, weights)));
        return scores;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Shipment>> invokeAngularSectorPartition(AllocationEngineService service,
                                                                       List<Shipment> shipments, List<String> srNames) {
        try {
            Method m = AllocationEngineService.class.getDeclaredMethod("angularSectorPartition", List.class, List.class);
            m.setAccessible(true);
            return (Map<String, List<Shipment>>) m.invoke(service, shipments, srNames);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Shipment>> invokeRebalance(AllocationEngineService service,
                                                         Map<String, List<Shipment>> assignment) {
        try {
            Method m = AllocationEngineService.class.getDeclaredMethod("rebalance", Map.class);
            m.setAccessible(true);
            return (Map<String, List<Shipment>>) m.invoke(service, assignment);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) { throw new RuntimeException("Failed to set field " + fieldName, e); }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException e) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz);
    }

    record ShipmentScenario(List<Shipment> shipments, List<String> srNames) {}
}
