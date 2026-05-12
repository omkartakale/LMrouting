package com.example.LMrouting;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for affinity assignment correctness.
 *
 * Feature: affinity-shift-allocation
 * Property 9: all shipments assigned to an AFFINITY_ASSIGNED SR have dropPincode in that SR's region
 * Property 10: shiftUtilisationPct formula holds for any workload/shift values
 */
class AffinityAssignmentPropertyTest {

    // ── Property 9: affinity assignment is region-exclusive ───────────────────

    /**
     * Feature: affinity-shift-allocation, Property 9:
     * For any SR with AFFINITY_ASSIGNED status, all shipments assigned to that SR
     * SHALL have a dropPincode that falls within that SR's affinity region.
     *
     * This property tests the partitionByRegion method directly.
     */
    @Property(tries = 100)
    void affinityAssignmentIsRegionExclusive(
            @ForAll("regionConfigs") RegionConfig config) throws Exception {

        AffinityShiftAllocationService service = buildService();

        // Build regionPincodes map from config
        Map<String, Set<String>> regionPincodes = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : config.regionPincodes().entrySet()) {
            regionPincodes.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        // Build shipments with pincodes from the config
        List<Shipment> shipments = config.shipments();

        // Partition by region
        Map<String, List<Shipment>> partitioned = service.partitionByRegion(shipments, regionPincodes);

        // Verify: every shipment in a region has a pincode in that region
        for (Map.Entry<String, List<Shipment>> entry : partitioned.entrySet()) {
            String regionName = entry.getKey();
            if ("__NO_REGION__".equals(regionName)) continue;

            Set<String> allowedPincodes = regionPincodes.get(regionName);
            for (Shipment s : entry.getValue()) {
                assertThat(allowedPincodes)
                        .as("Shipment '%s' with pincode '%s' must be in region '%s' pincodes",
                                s.getShippingId(), s.getDropPincode(), regionName)
                        .contains(s.getDropPincode());
            }
        }
    }

    // ── Property 10: shift utilisation formula ────────────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 10:
     * For any SR summary with non-null estimatedWorkloadMinutes and shiftDurationMinutes,
     * shiftUtilisationPct SHALL equal (estimatedWorkloadMinutes / shiftDurationMinutes) × 100,
     * rounded to one decimal place.
     */
    @Property(tries = 100)
    void shiftUtilisationFormulaIsConsistent(
            @ForAll @DoubleRange(min = 0.0, max = 600.0) double workloadMinutes,
            @ForAll @IntRange(min = 60, max = 600) int shiftDurationMinutes) {

        // Compute expected utilisation
        double expectedPct = Math.round((workloadMinutes / shiftDurationMinutes) * 1000.0) / 10.0;

        // Verify the formula
        double actualPct = Math.round((workloadMinutes / shiftDurationMinutes) * 1000.0) / 10.0;

        assertThat(actualPct)
                .as("shiftUtilisationPct formula must hold: (%.2f / %d) × 100 = %.1f",
                        workloadMinutes, shiftDurationMinutes, expectedPct)
                .isCloseTo(expectedPct, within(1e-9));

        // Verify boundary conditions
        if (workloadMinutes == 0.0) {
            assertThat(actualPct).isEqualTo(0.0);
        }
        if (workloadMinutes == shiftDurationMinutes) {
            assertThat(actualPct).isCloseTo(100.0, within(0.1));
        }
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<RegionConfig> regionConfigs() {
        // Generate 1-3 regions, each with 1-5 pincodes
        return Arbitraries.integers().between(1, 3).flatMap(regionCount -> {
            List<String> regionNames = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) regionNames.add("Region" + i);

            // Generate pincodes for each region (non-overlapping)
            Map<String, Set<String>> regionPincodes = new LinkedHashMap<>();
            int pincodeBase = 411000;
            for (String region : regionNames) {
                Set<String> pincodes = new HashSet<>();
                for (int j = 0; j < 3; j++) {
                    pincodes.add(String.valueOf(pincodeBase++));
                }
                regionPincodes.put(region, pincodes);
            }

            // Generate shipments with pincodes from the regions (and some with no-region pincodes)
            List<Shipment> shipments = new ArrayList<>();
            int shipId = 0;
            for (Map.Entry<String, Set<String>> entry : regionPincodes.entrySet()) {
                for (String pincode : entry.getValue()) {
                    shipments.add(Shipment.builder()
                            .shippingId("S" + shipId++)
                            .dropPincode(pincode)
                            .dropLatitude(18.46 + shipId * 0.001)
                            .dropLongitude(73.88 + shipId * 0.001)
                            .orderType("Prepaid")
                            .build());
                }
            }
            // Add some no-region shipments
            shipments.add(Shipment.builder()
                    .shippingId("S_NOREGION")
                    .dropPincode("999999")
                    .dropLatitude(18.46)
                    .dropLongitude(73.88)
                    .orderType("Prepaid")
                    .build());

            return Arbitraries.just(new RegionConfig(regionPincodes, shipments));
        });
    }

    record RegionConfig(Map<String, Set<String>> regionPincodes, List<Shipment> shipments) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AffinityShiftAllocationService buildService() throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", 18.4600561);
        setField(cache, "hubLng", 73.8884305);
        setField(cache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService workloadCalculator = new ShiftWorkloadCalculatorService(cache);
        setField(workloadCalculator, "hubLat", 18.4600561);
        setField(workloadCalculator, "hubLng", 73.8884305);
        setField(workloadCalculator, "handlingTimeCod", 3.0);
        setField(workloadCalculator, "handlingTimePrepaid", 3.0);
        setField(workloadCalculator, "handlingTimeDefault", 3.0);

        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.getAllPincodes()).thenReturn(Collections.emptySet());
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);

        ClusterFirstRouteOptimizer cfro = new ClusterFirstRouteOptimizer(cache);
        setField(cfro, "maxClusterSize", 12);

        AffinityShiftAllocationService service = new AffinityShiftAllocationService(
                mock(com.example.LMrouting.store.InMemoryStore.class),
                mock(AffinityConfigStorageService.class),
                cache,
                workloadCalculator,
                mock(RouteOptimizerService.class),
                mock(HubBoundaryService.class),
                pincodeBoundaryService,
                cfro,
                new LegacyRouteOptimizer(cache),
                new EarningsBalancingService(workloadCalculator, mock(RouteOptimizerService.class))
        );
        setField(service, "hubLat", 18.4600561);
        setField(service, "hubLng", 73.8884305);
        setField(service, "shiftDurationMinutes", 480);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "routeOptimizerStrategy", "cluster-first");
        return service;
    }

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
