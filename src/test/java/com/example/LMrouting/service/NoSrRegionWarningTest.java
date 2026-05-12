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
import static org.mockito.Mockito.*;

/**
 * Unit tests for no-SR region configuration warning (Task 6.2).
 *
 * Validates: Requirements 2.9
 * When no SR is assigned to a region that contains shipments, the system SHALL flag
 * this as a configuration warning visible in the region load panel and
 * AllocationSummary.operationalWarnings.
 */
class NoSrRegionWarningTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final LocalDate TEST_DATE = LocalDate.of(2025, 1, 15);
    private static final String TEST_DATE_STR = "2025-01-15";

    private AffinityShiftAllocationService service;
    private InMemoryStore store;
    private AffinityConfigStorageService affinityConfigStorage;

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

        RouteOptimizerService routeOptimizerService = mock(RouteOptimizerService.class);
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
                new EarningsBalancingService(workloadCalculator, routeOptimizerService)
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "hubName", "PNQ HDP");
        setField(service, "shiftDurationMinutes", 480);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "targetUtilisation", 0.88);
        setField(service, "routeOptimizerStrategy", "cluster-first");
    }

    @Test
    void allocate_regionWithNoSrs_generatesOperationalWarning() throws Exception {
        Map<String, Object> config = buildConfigWithNoSrRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        List<Shipment> shipments = List.of(
                shipmentInKothrud("S1"),
                shipmentInKothrud("S2"),
                shipmentInKothrud("S3"),
                shipmentInBaner("S4"),
                shipmentInBaner("S5")
        );
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

        AllocateRequest request = new AllocateRequest(TEST_DATE_STR, "time-based");

        AllocationSummary summary = service.allocate(TEST_DATE, request);

        assertThat(summary.operationalWarnings())
                .isNotNull()
                .isNotEmpty()
                .anyMatch(w -> w.contains("Kothrud") && w.contains("no assigned SRs"));

        assertThat(summary.operationalWarnings())
                .anyMatch(w -> w.equals("Region Kothrud has 3 shipments but no assigned SRs"));
    }

    @Test
    void allocate_regionWithNoSrs_surfacesWarningInRegionSummary() throws Exception {
        Map<String, Object> config = buildConfigWithNoSrRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        List<Shipment> shipments = List.of(
                shipmentInKothrud("S1"),
                shipmentInKothrud("S2"),
                shipmentInBaner("S3")
        );
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        assertThat(summary.regionSummaries()).isNotNull();
        Optional<RegionSummaryDto> kothrudSummary = summary.regionSummaries().stream()
                .filter(rs -> "Kothrud".equals(rs.regionName()))
                .findFirst();

        assertThat(kothrudSummary).isPresent();
        assertThat(kothrudSummary.get().configurationWarnings())
                .isNotEmpty()
                .anyMatch(w -> w.contains("Kothrud") && w.contains("no assigned SRs"));
    }

    @Test
    void allocate_regionWithSrs_noWarningGenerated() throws Exception {
        Map<String, Object> config = buildConfigWithAllSrsAssigned();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        List<Shipment> shipments = List.of(
                shipmentInKothrud("S1"),
                shipmentInKothrud("S2"),
                shipmentInBaner("S3")
        );
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        if (summary.operationalWarnings() != null) {
            assertThat(summary.operationalWarnings())
                    .noneMatch(w -> w.contains("no assigned SRs"));
        }
    }

    @Test
    void allocate_regionWithNoSrsAndNoShipments_noWarningGenerated() throws Exception {
        Map<String, Object> config = buildConfigWithNoSrRegion();
        when(affinityConfigStorage.loadConfig()).thenReturn(config);

        List<Shipment> shipments = List.of(
                shipmentInBaner("S1"),
                shipmentInBaner("S2")
        );
        when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
        when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

        AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

        if (summary.operationalWarnings() != null) {
            assertThat(summary.operationalWarnings())
                    .noneMatch(w -> w.contains("Kothrud"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildConfigWithNoSrRegion() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> kothrud = new HashMap<>();
        kothrud.put("name", "Kothrud");
        kothrud.put("assignedSRs", Collections.emptyList());
        kothrud.put("polygon", List.of(
                List.of(18.50, 73.82), List.of(18.50, 73.83),
                List.of(18.51, 73.83), List.of(18.51, 73.82), List.of(18.50, 73.82)
        ));

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.80),
                List.of(18.57, 73.80), List.of(18.57, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(kothrud, baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner"));
        return config;
    }

    private Map<String, Object> buildConfigWithAllSrsAssigned() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> kothrud = new HashMap<>();
        kothrud.put("name", "Kothrud");
        kothrud.put("assignedSRs", List.of("SR-002"));
        kothrud.put("polygon", List.of(
                List.of(18.50, 73.82), List.of(18.50, 73.83),
                List.of(18.51, 73.83), List.of(18.51, 73.82), List.of(18.50, 73.82)
        ));

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.80),
                List.of(18.57, 73.80), List.of(18.57, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(kothrud, baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner", "SR-002", "Kothrud"));
        return config;
    }

    private Shipment shipmentInKothrud(String id) {
        return Shipment.builder()
                .shippingId(id)
                .dropLatitude(18.505)
                .dropLongitude(73.825)
                .dropPincode("411038")
                .orderType("Prepaid")
                .expectedPayout(50.0)
                .isHeavy(0)
                .build();
    }

    private Shipment shipmentInBaner(String id) {
        return Shipment.builder()
                .shippingId(id)
                .dropLatitude(18.56)
                .dropLongitude(73.79)
                .dropPincode("411045")
                .orderType("Prepaid")
                .expectedPayout(50.0)
                .isHeavy(0)
                .build();
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
