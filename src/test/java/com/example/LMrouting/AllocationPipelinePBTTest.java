package com.example.LMrouting;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.*;
import com.example.LMrouting.store.InMemoryStore;
import net.jqwik.api.*;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for AllocationEngineService phases 5–9 and summary methods.
 */
class AllocationPipelinePBTTest {

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

    private RouteOptimizerService buildRouteOptimizer() {
        GoogleMapsService googleMapsService = new GoogleMapsService();
        OpenRouteService openRouteService = new OpenRouteService();
        RouteOptimizerService routeOptimizer = new RouteOptimizerService(googleMapsService, openRouteService);
        setField(routeOptimizer, "hubLat", HUB_LAT);
        setField(routeOptimizer, "hubLng", HUB_LNG);
        return routeOptimizer;
    }

    // Feature: shipment-allocation-optimizer, Property 7
    @Property(tries = 100)
    void allocationReducesFairnessVarianceVsRoundRobin(
            @ForAll("sufficientForwardShipmentScenarios") ShipmentScenario scenario) {

        AllocationEngineService service = buildService();
        ScoreWeights weights = new ScoreWeights(1.0, 0.0, 0.0, 0.0);

        Map<String, List<Shipment>> roundRobin = roundRobinAssign(scenario.shipments(), scenario.srNames());
        Map<String, Double> rrScores = computeScores(roundRobin, weights);
        double rrVariance = CompositeLoadScoreCalculator.variance(rrScores);

        Map<String, List<Shipment>> assignment = invokeAngularSectorPartition(service, scenario.shipments(), scenario.srNames());
        assignment = invokeRebalance(service, assignment);

        Map<String, Double> pipelineScores = computeScores(assignment, weights);
        double pipelineVariance = CompositeLoadScoreCalculator.variance(pipelineScores);

        assertThat(pipelineVariance)
                .as("Pipeline variance (%.4f) should be <= round-robin variance (%.4f)", pipelineVariance, rrVariance)
                .isLessThanOrEqualTo(rrVariance + 1e-9);
    }

    // Feature: shipment-allocation-optimizer, Property 15
    @Property(tries = 100)
    void allocationResponseIsComplete(@ForAll("completeShipmentScenarios") ShipmentScenario scenario) {
        AllocationEngineService service = buildService();
        ScoreWeights weights = new ScoreWeights(1.0, 0.0, 0.0, 0.0);
        RouteOptimizerService routeOptimizer = buildRouteOptimizer();

        Map<String, List<Shipment>> assignment = invokeAngularSectorPartition(service, scenario.shipments(), scenario.srNames());
        assignment = invokeRebalance(service, assignment);

        Map<String, List<Shipment>> orderedAssignment = new LinkedHashMap<>();
        Map<String, Double> distancesBySr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();
            List<Shipment> ordered = srShipments.size() >= 2
                    ? routeOptimizer.optimizeRoute(sr, srShipments) : new ArrayList<>(srShipments);
            if (!ordered.isEmpty() && ordered.size() == 1) ordered.get(0).setRouteSequence(1);
            orderedAssignment.put(sr, ordered);
            distancesBySr.put(sr, routeOptimizer.estimateDistanceKm(ordered));
        }

        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        orderedAssignment.forEach((sr, list) -> {
            double dist = distancesBySr.getOrDefault(sr, 0.0);
            scoresBySr.put(sr, CompositeLoadScoreCalculator.compute(list, dist, weights));
        });

        List<SrSummaryDto> srSummaries = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> list = entry.getValue();
            int heavyCount = (int) list.stream().filter(s -> s.getIsHeavy() == 1).count();
            double dist = distancesBySr.getOrDefault(sr, 0.0);
            List<String> pincodes = list.stream().map(Shipment::getDropPincode)
                    .filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
            srSummaries.add(new SrSummaryDto(sr, list.size(), heavyCount, scoresBySr.getOrDefault(sr, 0.0), dist, pincodes));
        }

        for (SrSummaryDto srDto : srSummaries) {
            assertThat(srDto.srName()).isNotNull();
            assertThat(srDto.pincodesCovered()).isNotNull();
        }

        for (Map.Entry<String, List<Shipment>> entry : orderedAssignment.entrySet()) {
            for (Shipment s : entry.getValue()) {
                assertThat(s.getShippingId()).isNotNull();
                assertThat(s.getShipmentFlow()).isNotNull();
            }
        }
    }

    @Provide
    Arbitrary<ShipmentScenario> sufficientForwardShipmentScenarios() {
        return Arbitraries.integers().between(2, 5).flatMap(k -> {
            List<String> srNames = new ArrayList<>();
            for (int i = 1; i <= k; i++) srNames.add("SR-" + String.format("%03d", i));
            return shipmentArbitrary("Forward").list().ofMinSize(k).ofMaxSize(k * 6)
                    .map(shipments -> new ShipmentScenario(shipments, srNames));
        });
    }

    @Provide
    Arbitrary<ShipmentScenario> completeShipmentScenarios() {
        return Arbitraries.integers().between(2, 4).flatMap(k -> {
            List<String> srNames = new ArrayList<>();
            for (int i = 1; i <= k; i++) srNames.add("SR-" + String.format("%03d", i));
            return shipmentArbitrary("Forward").list().ofMinSize(k).ofMaxSize(k * 5)
                    .map(shipments -> new ShipmentScenario(shipments, srNames));
        });
    }

    private Arbitrary<Shipment> shipmentArbitrary(String flow) {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(HUB_LAT - 0.09, HUB_LAT + 0.09).ofScale(7);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(HUB_LNG - 0.09, HUB_LNG + 0.09).ofScale(7);
        Arbitrary<Double> weightArb = Arbitraries.doubles().between(0.1, 50.0).ofScale(2);
        Arbitrary<Integer> heavyArb = Arbitraries.integers().between(0, 1);
        Arbitrary<String> pincodeArb = Arbitraries.strings().alpha().ofLength(6);
        Arbitrary<String> orderTypeArb = Arbitraries.of("Prepaid", "COD", "Reverse");

        return net.jqwik.api.Combinators.combine(latArb, lngArb, weightArb, heavyArb, pincodeArb, orderTypeArb)
                .as((lat, lng, weight, heavy, pincode, orderType) -> Shipment.builder()
                        .shippingId(UUID.randomUUID().toString().substring(0, 8))
                        .shipmentFlow(flow).dropLatitude(lat).dropLongitude(lng)
                        .phyWeight(weight).isHeavy(heavy).dropPincode(pincode).orderType(orderType).build());
    }

    private Map<String, List<Shipment>> roundRobinAssign(List<Shipment> shipments, List<String> srNames) {
        Map<String, List<Shipment>> result = new LinkedHashMap<>();
        srNames.forEach(sr -> result.put(sr, new ArrayList<>()));
        for (int i = 0; i < shipments.size(); i++) result.get(srNames.get(i % srNames.size())).add(shipments.get(i));
        return result;
    }

    private Map<String, Double> computeScores(Map<String, List<Shipment>> assignment, ScoreWeights weights) {
        Map<String, Double> scores = new LinkedHashMap<>();
        assignment.forEach((sr, list) -> scores.put(sr, CompositeLoadScoreCalculator.compute(list, 0.0, weights)));
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
