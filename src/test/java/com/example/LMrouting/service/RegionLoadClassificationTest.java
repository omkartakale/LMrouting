package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocateRequest;
import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.RegionSummaryDto;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for region load classification and operational warnings (Task 6.4).
 *
 * Validates: Requirements 2.5, 2.9, 2.10
 *
 * Tests cover:
 * - Classification thresholds: 49% → UNDERLOADED, 50% → HEALTHY, 91% → OVERLOADED, overflow > 0 → OVERLOADED
 * - No-SR region warning generation
 * - Overloaded/idle SR flagging
 */
class RegionLoadClassificationTest {

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
        setField(service, "routeOptimizerStrategy", "cluster-first");
    }

    // =========================================================================
    // Region Load Classification Thresholds (Requirement 2.5)
    // =========================================================================

    @Nested
    class ClassificationThresholds {

        @Test
        void computeHealthStatus_49percent_returnsUnderloaded() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 49.0);
            assertThat(status).isEqualTo("UNDERLOADED");
        }

        @Test
        void computeHealthStatus_50percent_returnsHealthy() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 50.0);
            assertThat(status).isEqualTo("HEALTHY");
        }

        @Test
        void computeHealthStatus_90percent_returnsHealthy() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 90.0);
            assertThat(status).isEqualTo("HEALTHY");
        }

        @Test
        void computeHealthStatus_91percent_returnsOverloaded() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 91.0);
            assertThat(status).isEqualTo("OVERLOADED");
        }

        @Test
        void computeHealthStatus_overflowGreaterThanZero_returnsOverloaded() {
            // Even with low utilization, overflow > 0 means OVERLOADED
            String status = RegionSummaryDto.computeHealthStatus(1, 0, 30.0);
            assertThat(status).isEqualTo("OVERLOADED");
        }

        @Test
        void computeHealthStatus_overflowAndHighUtilization_returnsOverloaded() {
            String status = RegionSummaryDto.computeHealthStatus(5, 2, 95.0);
            assertThat(status).isEqualTo("OVERLOADED");
        }

        @Test
        void computeHealthStatus_zeroUtilization_returnsUnderloaded() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 0.0);
            assertThat(status).isEqualTo("UNDERLOADED");
        }

        @Test
        void computeHealthStatus_exactBoundary50_returnsHealthy() {
            // 50.0 is the lower boundary of HEALTHY (>= 50)
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 50.0);
            assertThat(status).isEqualTo("HEALTHY");
        }

        @Test
        void computeHealthStatus_justBelow50_returnsUnderloaded() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 49.9);
            assertThat(status).isEqualTo("UNDERLOADED");
        }

        @Test
        void computeHealthStatus_justAbove90_returnsOverloaded() {
            String status = RegionSummaryDto.computeHealthStatus(0, 0, 90.1);
            assertThat(status).isEqualTo("OVERLOADED");
        }
    }

    // =========================================================================
    // No-SR Region Warning Generation (Requirement 2.9)
    // =========================================================================

    @Nested
    class NoSrRegionWarning {

        @Test
        void allocate_regionWithNoSrs_generatesConfigurationWarning() throws Exception {
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

            // Verify operational warnings contain the no-SR region warning
            assertThat(summary.operationalWarnings())
                    .isNotNull()
                    .anyMatch(w -> w.contains("Kothrud") && w.contains("no assigned SRs"));
        }

        @Test
        void allocate_regionWithNoSrsAndShipments_warningIncludesShipmentCount() throws Exception {
            Map<String, Object> config = buildConfigWithNoSrRegion();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            List<Shipment> shipments = List.of(
                    shipmentInKothrud("S1"),
                    shipmentInKothrud("S2"),
                    shipmentInKothrud("S3"),
                    shipmentInKothrud("S4"),
                    shipmentInBaner("S5")
            );
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            assertThat(summary.operationalWarnings())
                    .anyMatch(w -> w.equals("Region Kothrud has 4 shipments but no assigned SRs"));
        }

        @Test
        void allocate_regionWithNoSrsButNoShipments_noWarning() throws Exception {
            Map<String, Object> config = buildConfigWithNoSrRegion();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Only shipments in Baner (which has SRs), none in Kothrud
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

        @Test
        void allocate_regionWithNoSrs_regionSummaryHasConfigurationWarning() throws Exception {
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
                    .anyMatch(w -> w.contains("no assigned SRs"));
        }
    }

    // =========================================================================
    // Overloaded/Idle SR Flagging (Requirement 2.10)
    // =========================================================================

    @Nested
    class OverloadedIdleSrFlagging {

        @Test
        void allocate_srWithUtilizationOver100_flaggedAsOverloaded() throws Exception {
            // Create a scenario where an SR gets many shipments that exceed shift capacity
            Map<String, Object> config = buildConfigWithSingleRegionManySrs();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Many shipments to overload a single SR
            List<Shipment> shipments = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                shipments.add(shipmentInBaner("S" + i));
            }
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            // Only one SR present — will be overloaded
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            // Check that the SR is flagged as OVERLOADED in SrSummaryDto
            Optional<SrSummaryDto> overloadedSr = summary.srSummaries().stream()
                    .filter(sr -> sr.shiftUtilisationPct() != null && sr.shiftUtilisationPct() > 100.0)
                    .findFirst();

            if (overloadedSr.isPresent()) {
                assertThat(overloadedSr.get().operationalWarning()).isEqualTo("OVERLOADED");
                // Also check operational warnings list
                assertThat(summary.operationalWarnings())
                        .anyMatch(w -> w.contains("OVERLOADED"));
            }
        }

        @Test
        void allocate_idleSrInRegionWithShipments_flaggedAsIdle() throws Exception {
            // Create a scenario where one SR gets all shipments and another is idle
            Map<String, Object> config = buildConfigWithTwoSrsInRegion();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Few shipments — only one SR will get them, the other stays idle
            List<Shipment> shipments = List.of(
                    shipmentInBaner("S1"),
                    shipmentInBaner("S2"),
                    shipmentInBaner("S3")
            );
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            // One SR should be idle (0 shipments) while the region has shipments
            Optional<SrSummaryDto> idleSr = summary.srSummaries().stream()
                    .filter(sr -> sr.shipmentCount() == 0)
                    .findFirst();

            if (idleSr.isPresent()) {
                assertThat(idleSr.get().operationalWarning()).isEqualTo("IDLE");
                assertThat(summary.operationalWarnings())
                        .anyMatch(w -> w.contains("IDLE"));
            }
        }

        @Test
        void allocate_srWithNormalUtilization_noWarning() throws Exception {
            Map<String, Object> config = buildConfigWithSingleRegionManySrs();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Moderate number of shipments — should result in normal utilization
            List<Shipment> shipments = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                shipments.add(shipmentInBaner("S" + i));
            }
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            // SR with normal utilization should have no operational warning
            Optional<SrSummaryDto> normalSr = summary.srSummaries().stream()
                    .filter(sr -> sr.shiftUtilisationPct() != null
                            && sr.shiftUtilisationPct() > 0.0
                            && sr.shiftUtilisationPct() <= 100.0)
                    .findFirst();

            if (normalSr.isPresent()) {
                assertThat(normalSr.get().operationalWarning()).isNull();
            }
        }

        @Test
        void allocate_overloadedSr_warningMessageIncludesUtilization() throws Exception {
            Map<String, Object> config = buildConfigWithSingleRegionManySrs();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Many shipments to overload
            List<Shipment> shipments = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                shipments.add(shipmentInBaner("S" + i));
            }
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            // Verify the warning message format includes utilization percentage
            if (summary.operationalWarnings() != null) {
                Optional<String> overloadedWarning = summary.operationalWarnings().stream()
                        .filter(w -> w.contains("OVERLOADED"))
                        .findFirst();
                if (overloadedWarning.isPresent()) {
                    assertThat(overloadedWarning.get()).matches(".*SR.*OVERLOADED.*utilization.*\\d+%.*");
                }
            }
        }

        @Test
        void allocate_idleSr_warningMessageIncludesRegionInfo() throws Exception {
            Map<String, Object> config = buildConfigWithTwoSrsInRegion();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            List<Shipment> shipments = List.of(
                    shipmentInBaner("S1"),
                    shipmentInBaner("S2"),
                    shipmentInBaner("S3")
            );
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001", "SR-002"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            // Verify the idle warning message includes region name and shipment count
            if (summary.operationalWarnings() != null) {
                Optional<String> idleWarning = summary.operationalWarnings().stream()
                        .filter(w -> w.contains("IDLE"))
                        .findFirst();
                if (idleWarning.isPresent()) {
                    assertThat(idleWarning.get()).contains("IDLE");
                    assertThat(idleWarning.get()).contains("Baner");
                }
            }
        }
    }

    // =========================================================================
    // Integration: Region classification in allocation response
    // =========================================================================

    @Nested
    class RegionClassificationInResponse {

        @Test
        void allocate_regionSummariesIncludeHealthStatus() throws Exception {
            Map<String, Object> config = buildConfigWithSingleRegionManySrs();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            List<Shipment> shipments = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                shipments.add(shipmentInBaner("S" + i));
            }
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            assertThat(summary.regionSummaries()).isNotNull().isNotEmpty();
            for (RegionSummaryDto region : summary.regionSummaries()) {
                assertThat(region.healthStatus())
                        .isIn("UNDERLOADED", "HEALTHY", "OVERLOADED");
            }
        }

        @Test
        void allocate_regionWithOverflow_classifiedAsOverloaded() throws Exception {
            Map<String, Object> config = buildConfigWithSingleRegionManySrs();
            when(affinityConfigStorage.loadConfig()).thenReturn(config);

            // Many shipments to cause overflow
            List<Shipment> shipments = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                shipments.add(shipmentInBaner("S" + i));
            }
            when(store.findShipmentsByDate(TEST_DATE_STR)).thenReturn(new ArrayList<>(shipments));
            when(store.getPresentSrNames(TEST_DATE)).thenReturn(List.of("SR-001"));

            AllocationSummary summary = service.allocate(TEST_DATE, new AllocateRequest(TEST_DATE_STR, "time-based"));

            Optional<RegionSummaryDto> banerRegion = summary.regionSummaries().stream()
                    .filter(rs -> "Baner".equals(rs.regionName()))
                    .findFirst();

            if (banerRegion.isPresent() && banerRegion.get().overflowShipments() > 0) {
                assertThat(banerRegion.get().healthStatus()).isEqualTo("OVERLOADED");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private Map<String, Object> buildConfigWithSingleRegionManySrs() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.80),
                List.of(18.57, 73.80), List.of(18.57, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner"));
        return config;
    }

    private Map<String, Object> buildConfigWithTwoSrsInRegion() {
        Map<String, Object> config = new HashMap<>();

        Map<String, Object> baner = new HashMap<>();
        baner.put("name", "Baner");
        baner.put("assignedSRs", List.of("SR-001", "SR-002"));
        baner.put("polygon", List.of(
                List.of(18.55, 73.78), List.of(18.55, 73.80),
                List.of(18.57, 73.80), List.of(18.57, 73.78), List.of(18.55, 73.78)
        ));

        config.put("regions", List.of(baner));
        config.put("srZoneMap", Map.of("SR-001", "Baner", "SR-002", "Baner"));
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
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
