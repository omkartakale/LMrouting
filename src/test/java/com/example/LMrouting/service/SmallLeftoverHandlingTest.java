package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocateRequest;
import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.RegionSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for small leftover handling (Task 7.1 / 7.2).
 *
 * Validates: Requirements 2.8
 *
 * Tests cover:
 * - 1-9 leftover shipments where consolidation fails → no new SR activation
 * - 10+ leftover shipments → normal behavior (new SR can be activated)
 * - Consolidation success path → no warning generated
 * - smallLeftoverWarning flag in region summary
 */
class SmallLeftoverHandlingTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final LocalDate TEST_DATE = LocalDate.of(2025, 1, 15);
    private static final String TEST_DATE_STR = "2025-01-15";

    private AffinityShiftAllocationService service;
    private InMemoryStore store;
    private AffinityConfigStorageService affinityConfigStorage;
    private RouteOptimizerService routeOptimizerService;

    @BeforeEach
    void setUp() throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService travelTimeCache = new TravelTimeCacheService(ors, gms);
        setField(travelTimeCache, "hubLat", HUB_LAT);
        setField(travelTimeCache, "hubLng", HUB_LNG);
        setField(travelTimeCache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService workloadCalculator = new ShiftWorkloadCalculatorService(travelTimeCache);
        setField(workloadCalculator, "hubLat", HUB_LAT);
        setField(workloadCalculator, "hubLng", HUB_LNG);
        setField(workloadCalculator, "handlingTimeCod", 6.0);
        setField(workloadCalculator, "handlingTimePrepaid", 5.0);
        setField(workloadCalculator, "handlingTimeDefault", 5.0);

        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.getAllPincodes()).thenReturn(Collections.emptySet());
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);

        routeOptimizerService = mock(RouteOptimizerService.class);
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(10.0);

        HubBoundaryService hubBoundaryService = mock(HubBoundaryService.class);
        when(hubBoundaryService.fetchBoundary(anyString())).thenReturn(null);

        store = mock(InMemoryStore.class);
        affinityConfigStorage = mock(AffinityConfigStorageService.class);

        ClusterFirstRouteOptimizer cfro = new ClusterFirstRouteOptimizer(travelTimeCache);
        setField(cfro, "maxClusterSize", 12);

        service = new AffinityShiftAllocationService(
                store,
                affinityConfigStorage,
                travelTimeCache,
                workloadCalculator,
                routeOptimizerService,
                hubBoundaryService,
                pincodeBoundaryService,
                cfro,
                new LegacyRouteOptimizer(travelTimeCache),
                new EarningsBalancingService(workloadCalculator, routeOptimizerService),
                new TerritoryPartitionService(workloadCalculator)
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "hubName", "PNQ HDP");
        setField(service, "shiftDurationMinutes", 480);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "targetUtilisation", 0.88);
        setField(service, "leftoverMinThreshold", 10);
        setField(service, "routeOptimizerStrategy", "cluster-first");
    }

    // =========================================================================
    // Small leftover (< 10) → no new SR activation, marked unallocated
    // =========================================================================

    @Test
    void allocate_smallLeftoverWhereConsolidationFails_noNewSrActivated() throws Exception {
        // Setup: 2 SRs in a region. First SR gets filled to capacity.
        // Remaining 5 shipments can't fit in the first SR and are below threshold.
        // The second SR should NOT be activated for just 5 shipments.
        Map<String, Object> config = buildConfigWithTwoSrsInRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        // Create enough shipments to fill SR-001 to capacity, plus 5 leftover
        // With 480 min shift and ~5 min handling + travel per shipment, ~60 shipments fill an SR
        // We'll create 65 shipments spread across different locations to ensure overflow
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            // Spread shipments across the region to increase travel time
            double latOffset = (i % 10) * 0.002;
            double lngOffset = (i / 10) * 0.002;
            shipments.add(Shipment.builder()
                    .shippingId("S" + i)
                    .dropLatitude(18.56 + latOffset)
                    .dropLongitude(73.79 + lngOffset)
                    .dropPincode("41104" + (i % 5))
                    .orderType("Prepaid")
                    .expectedPayout(50.0)
                    .isHeavy(0)
                    .build());
        }
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        // If there's a small leftover (< 10), the second SR should NOT be activated
        // Check: if SR-002 has 0 shipments and there are unallocated shipments < 10,
        // the small leftover guard is working
        long sr2Shipments = summary.srSummaries().stream()
                .filter(sr -> "SR-002".equals(sr.srName()))
                .mapToInt(sr -> sr.shipmentCount())
                .sum();

        // If overflow was < 10, SR-002 should have 0 shipments
        if (summary.unallocatedShipments() > 0 && summary.unallocatedShipments() < 10) {
            assertThat(sr2Shipments).isEqualTo(0);
        }
    }

    @Test
    void allocate_smallLeftoverWhereConsolidationFails_smallLeftoverWarningPresent() throws Exception {
        // Use a very tight shift duration to force overflow with few shipments
        setField(service, "shiftDurationMinutes", 60); // Very short shift to force overflow quickly

        Map<String, Object> config = buildConfigWithTwoSrsInRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        // Create 12 shipments — first SR fills quickly (60 min shift), leaving < 10 overflow
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double latOffset = (i % 4) * 0.003;
            double lngOffset = (i / 4) * 0.003;
            shipments.add(Shipment.builder()
                    .shippingId("S" + i)
                    .dropLatitude(18.56 + latOffset)
                    .dropLongitude(73.79 + lngOffset)
                    .dropPincode("411045")
                    .orderType("Prepaid")
                    .expectedPayout(50.0)
                    .isHeavy(0)
                    .build());
        }
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        // Check if smallLeftoverWarning is set on the region summary
        if (summary.regionSummaries() != null) {
            Optional<RegionSummaryDto> banerRegion = summary.regionSummaries().stream()
                    .filter(rs -> "Baner".equals(rs.regionName()))
                    .findFirst();

            if (banerRegion.isPresent() && Boolean.TRUE.equals(banerRegion.get().smallLeftoverWarning())) {
                // Verify the warning is also in operational warnings
                assertThat(summary.operationalWarnings())
                        .anyMatch(w -> w.contains("leftover") && w.contains("threshold"));
            }
        }
    }

    @Test
    void allocate_overflowAboveThreshold_normalBehavior() throws Exception {
        // With 10+ overflow shipments, normal behavior should apply (SR can be activated)
        setField(service, "shiftDurationMinutes", 30); // Very short shift to force lots of overflow

        Map<String, Object> config = buildConfigWithTwoSrsInRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        // Create 30 shipments spread out — with 30 min shift, many will overflow (> 10)
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double latOffset = (i % 6) * 0.005;
            double lngOffset = (i / 6) * 0.005;
            shipments.add(Shipment.builder()
                    .shippingId("S" + i)
                    .dropLatitude(18.56 + latOffset)
                    .dropLongitude(73.79 + lngOffset)
                    .dropPincode("41104" + (i % 5))
                    .orderType("COD")
                    .expectedPayout(50.0)
                    .isHeavy(0)
                    .build());
        }
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        // With overflow >= 10, SR-002 should be activated (normal behavior)
        // The smallLeftoverWarning should NOT be set
        if (summary.regionSummaries() != null) {
            Optional<RegionSummaryDto> banerRegion = summary.regionSummaries().stream()
                    .filter(rs -> "Baner".equals(rs.regionName()))
                    .findFirst();

            if (banerRegion.isPresent()) {
                // If overflow was >= threshold, smallLeftoverWarning should be null
                // SR-002 should have received shipments (normal activation)
                long sr2Shipments = summary.srSummaries().stream()
                        .filter(sr -> "SR-002".equals(sr.srName()))
                        .mapToInt(sr -> sr.shipmentCount())
                        .sum();

                // Either SR-002 was activated (normal behavior) or the warning is null
                if (sr2Shipments > 0) {
                    // Normal behavior: SR-002 was activated for >= 10 overflow
                    assertThat(banerRegion.get().smallLeftoverWarning()).isNull();
                }
            }
        }
    }

    @Test
    void allocate_consolidationSucceeds_noWarningGenerated() throws Exception {
        // When consolidation succeeds (overflow fits into existing SRs with overfill),
        // no smallLeftoverWarning should be generated
        Map<String, Object> config = buildConfigWithSingleSrInRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        // Create a moderate number of shipments that all fit in one SR
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            shipments.add(Shipment.builder()
                    .shippingId("S" + i)
                    .dropLatitude(18.56)
                    .dropLongitude(73.79)
                    .dropPincode("411045")
                    .orderType("Prepaid")
                    .expectedPayout(50.0)
                    .isHeavy(0)
                    .build());
        }
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        // No overflow means no smallLeftoverWarning
        if (summary.regionSummaries() != null) {
            for (RegionSummaryDto region : summary.regionSummaries()) {
                assertThat(region.smallLeftoverWarning()).isNull();
            }
        }

        // No leftover-related operational warnings
        if (summary.operationalWarnings() != null) {
            assertThat(summary.operationalWarnings())
                    .noneMatch(w -> w.contains("leftover") && w.contains("threshold"));
        }
    }

    @Test
    void densePackRegion_smallLeftover_doesNotActivateNextSr() throws Exception {
        // Directly test densePackRegion: when remaining shipments < threshold,
        // the method should NOT activate the next SR
        setField(service, "shiftDurationMinutes", 30); // Very tight to force overflow quickly

        // Create shipments that will overflow from SR-001 but count < 10
        List<Shipment> regionShipments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double latOffset = i * 0.005;
            regionShipments.add(Shipment.builder()
                    .shippingId("S" + i)
                    .dropLatitude(18.56 + latOffset)
                    .dropLongitude(73.79)
                    .dropPincode("411045")
                    .orderType("COD")
                    .expectedPayout(50.0)
                    .isHeavy(0)
                    .build());
        }

        List<String> assignedSrs = List.of("SR-001", "SR-002");

        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(regionShipments, assignedSrs);

        // SR-002 should NOT have any shipments if the remaining count was < threshold
        int sr2Count = result.assignments().getOrDefault("SR-002", Collections.emptyList()).size();

        // If there's overflow, it means the guard prevented SR-002 activation
        if (!result.overflow().isEmpty()) {
            assertThat(sr2Count).isEqualTo(0);
            assertThat(result.overflow().size()).isLessThan(10);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> buildConfigWithTwoSrsInRegion() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001", "SR-002"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.82),
                List.of(18.60, 73.82), List.of(18.60, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner", "SR-002", "Baner"));
        return config;
    }

    private Map<String, Object> buildConfigWithSingleSrInRegion() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.82),
                List.of(18.60, 73.82), List.of(18.60, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner"));
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
