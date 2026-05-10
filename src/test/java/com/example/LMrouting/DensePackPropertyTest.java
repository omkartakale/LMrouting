package com.example.LMrouting;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotEmpty;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for AffinityShiftAllocationService.densePackRegion().
 *
 * Feature: affinity-shift-allocation
 * Property 8: every SR's workload after dense packing ≤ shiftDurationMinutes
 */
class DensePackPropertyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double LAT_MIN = 18.40;
    private static final double LAT_MAX = 18.60;
    private static final double LNG_MIN = 73.75;
    private static final double LNG_MAX = 74.00;

    private AffinityShiftAllocationService buildService(int shiftDurationMinutes) throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService workloadCalculator = new ShiftWorkloadCalculatorService(cache);
        setField(workloadCalculator, "hubLat", HUB_LAT);
        setField(workloadCalculator, "hubLng", HUB_LNG);
        setField(workloadCalculator, "handlingTimeCod", 3.0);
        setField(workloadCalculator, "handlingTimePrepaid", 3.0);
        setField(workloadCalculator, "handlingTimeDefault", 3.0);

        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.getAllPincodes()).thenReturn(java.util.Collections.emptySet());
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);

        AffinityShiftAllocationService service = new AffinityShiftAllocationService(
                mock(com.example.LMrouting.store.InMemoryStore.class),
                mock(AffinityConfigStorageService.class),
                cache,
                workloadCalculator,
                mock(RouteOptimizerService.class),
                mock(HubBoundaryService.class),
                pincodeBoundaryService
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "shiftDurationMinutes", shiftDurationMinutes);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        return service;
    }

    // ── Property 8: dense packing respects shift duration ─────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 8:
     * For any list of shipments and any list of SRs,
     * after dense packing, every SR's assigned workload SHALL be ≤ shiftDurationMinutes.
     */
    @Property(tries = 100)
    void densePackingRespectsShiftDuration(
            @ForAll("shipmentScenarios") ShipmentScenario scenario) throws Exception {

        int shiftDuration = scenario.shiftDurationMinutes();
        AffinityShiftAllocationService service = buildService(shiftDuration);

        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(scenario.shipments(), scenario.srNames());

        // Verify each SR's workload is within shift duration
        for (Map.Entry<String, List<Shipment>> entry : result.assignments().entrySet()) {
            String sr = entry.getKey();
            List<Shipment> assigned = entry.getValue();

            if (assigned.isEmpty()) continue;

            // Compute workload for this SR's assignment
            OpenRouteService ors = mock(OpenRouteService.class);
            when(ors.isConfigured()).thenReturn(false);
            GoogleMapsService gms = mock(GoogleMapsService.class);
            when(gms.isApiKeyConfigured()).thenReturn(false);

            TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
            setField(cache, "hubLat", HUB_LAT);
            setField(cache, "hubLng", HUB_LNG);
            setField(cache, "avgSpeedKmh", 20.0);

            ShiftWorkloadCalculatorService calculator = new ShiftWorkloadCalculatorService(cache);
            setField(calculator, "hubLat", HUB_LAT);
            setField(calculator, "hubLng", HUB_LNG);
            setField(calculator, "handlingTimeCod", 3.0);
            setField(calculator, "handlingTimePrepaid", 3.0);
            setField(calculator, "handlingTimeDefault", 3.0);

            ShiftWorkloadCalculatorService.WorkloadResult workload = calculator.computeWorkload(assigned);

            assertThat(workload.totalMinutes())
                    .as("SR '%s' workload (%.2f min) must be ≤ shiftDuration (%d min)",
                            sr, workload.totalMinutes(), shiftDuration)
                    .isLessThanOrEqualTo(shiftDuration + 1e-9);
        }
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<ShipmentScenario> shipmentScenarios() {
        Arbitrary<Integer> shiftArb = Arbitraries.integers().between(60, 600); // 1-10 hours
        Arbitrary<Integer> srCountArb = Arbitraries.integers().between(1, 4);
        Arbitrary<Integer> shipmentCountArb = Arbitraries.integers().between(0, 15);

        return Combinators.combine(shiftArb, srCountArb, shipmentCountArb)
                .as((shift, srCount, shipCount) -> {
                    List<String> srNames = new ArrayList<>();
                    for (int i = 1; i <= srCount; i++) srNames.add("SR-" + String.format("%03d", i));

                    List<Shipment> shipments = new ArrayList<>();
                    for (int i = 0; i < shipCount; i++) {
                        double angle = 2 * Math.PI * i / Math.max(shipCount, 1);
                        shipments.add(Shipment.builder()
                                .shippingId("S" + i)
                                .dropLatitude(HUB_LAT + 0.03 * Math.cos(angle))
                                .dropLongitude(HUB_LNG + 0.03 * Math.sin(angle))
                                .orderType(i % 3 == 0 ? "COD" : "Prepaid")
                                .build());
                    }
                    return new ShipmentScenario(shipments, srNames, shift);
                });
    }

    record ShipmentScenario(List<Shipment> shipments, List<String> srNames, int shiftDurationMinutes) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
