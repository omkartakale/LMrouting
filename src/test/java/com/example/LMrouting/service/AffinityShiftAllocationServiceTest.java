package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AffinityShiftAllocationService helper methods.
 * Tests the package-private phase helpers directly without a Spring context.
 */
class AffinityShiftAllocationServiceTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    private AffinityShiftAllocationService service;
    private ShiftWorkloadCalculatorService workloadCalculator;
    private TravelTimeCacheService travelTimeCache;

    @BeforeEach
    void setUp() throws Exception {
        // Build a real TravelTimeCacheService backed by Haversine
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        travelTimeCache = new TravelTimeCacheService(ors, gms);
        setField(travelTimeCache, "hubLat", HUB_LAT);
        setField(travelTimeCache, "hubLng", HUB_LNG);
        setField(travelTimeCache, "avgSpeedKmh", 20.0);

        workloadCalculator = new ShiftWorkloadCalculatorService(travelTimeCache);
        setField(workloadCalculator, "hubLat", HUB_LAT);
        setField(workloadCalculator, "hubLng", HUB_LNG);
        setField(workloadCalculator, "handlingTimeCod", 3.0);
        setField(workloadCalculator, "handlingTimePrepaid", 3.0);
        setField(workloadCalculator, "handlingTimeDefault", 3.0);

        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.getAllPincodes()).thenReturn(Collections.emptySet());
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);

        ClusterFirstRouteOptimizer cfro = new ClusterFirstRouteOptimizer(travelTimeCache);
        setField(cfro, "maxClusterSize", 12);

        RouteOptimizerService routeOptimizerService = mock(RouteOptimizerService.class);

        EarningsBalancingService earningsBalancingService = new EarningsBalancingService(
                workloadCalculator, routeOptimizerService);
        setField(earningsBalancingService, "balanceTarget", 0.50);
        setField(earningsBalancingService, "maxIterations", 50);

        service = new AffinityShiftAllocationService(
                mock(com.example.LMrouting.store.InMemoryStore.class),
                mock(AffinityConfigStorageService.class),
                travelTimeCache,
                workloadCalculator,
                routeOptimizerService,
                mock(HubBoundaryService.class),
                pincodeBoundaryService,
                cfro,
                new LegacyRouteOptimizer(travelTimeCache),
                earningsBalancingService
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "shiftDurationMinutes", 480);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "routeOptimizerStrategy", "cluster-first");
    }

    // ── buildSrAffinityStatus ─────────────────────────────────────────────────

    @Test
    void buildSrAffinityStatus_assignedSrGetsAffinityAssigned() {
        Map<String, Object> config = buildConfig(
                List.of(buildRegion("Kothrud", List.of("SR-001", "SR-002")))
        );
        Map<String, String> status = service.buildSrAffinityStatus(
                List.of("SR-001", "SR-002", "SR-003"), config);

        assertThat(status.get("SR-001")).isEqualTo("AFFINITY_ASSIGNED");
        assertThat(status.get("SR-002")).isEqualTo("AFFINITY_ASSIGNED");
        assertThat(status.get("SR-003")).isEqualTo("NON_AFFINITY");
    }

    @Test
    void buildSrAffinityStatus_emptyConfigAllNonAffinity() {
        Map<String, String> status = service.buildSrAffinityStatus(
                List.of("SR-001", "SR-002"), new HashMap<>());

        assertThat(status.get("SR-001")).isEqualTo("NON_AFFINITY");
        assertThat(status.get("SR-002")).isEqualTo("NON_AFFINITY");
    }

    @Test
    void buildSrAffinityStatus_srNotInAnyRegionIsNonAffinity() {
        Map<String, Object> config = buildConfig(
                List.of(buildRegion("Kothrud", List.of("SR-001")))
        );
        Map<String, String> status = service.buildSrAffinityStatus(
                List.of("SR-001", "SR-005"), config);

        assertThat(status.get("SR-005")).isEqualTo("NON_AFFINITY");
    }

    // ── partitionByRegion ─────────────────────────────────────────────────────

    @Test
    void partitionByRegion_shipmentsMatchingRegionPincodeAreAssigned() {
        Map<String, Set<String>> regionPincodes = new LinkedHashMap<>();
        regionPincodes.put("Kothrud", new HashSet<>(Set.of("411038", "411029")));
        regionPincodes.put("Baner", new HashSet<>(Set.of("411045")));

        List<Shipment> shipments = List.of(
                shipmentWithPincode("S1", "411038"),
                shipmentWithPincode("S2", "411045"),
                shipmentWithPincode("S3", "999999") // no region
        );

        Map<String, List<Shipment>> result = service.partitionByRegion(shipments, regionPincodes);

        assertThat(result.get("Kothrud")).hasSize(1);
        assertThat(result.get("Kothrud").get(0).getShippingId()).isEqualTo("S1");
        assertThat(result.get("Baner")).hasSize(1);
        assertThat(result.get("Baner").get(0).getShippingId()).isEqualTo("S2");
        assertThat(result.get("__NO_REGION__")).hasSize(1);
        assertThat(result.get("__NO_REGION__").get(0).getShippingId()).isEqualTo("S3");
    }

    @Test
    void partitionByRegion_allShipmentsNoRegionWhenMapEmpty() {
        List<Shipment> shipments = List.of(
                shipmentWithPincode("S1", "411038"),
                shipmentWithPincode("S2", "411045")
        );
        Map<String, List<Shipment>> result = service.partitionByRegion(shipments, new LinkedHashMap<>());

        assertThat(result.get("__NO_REGION__")).hasSize(2);
    }

    @Test
    void partitionByRegion_emptyShipmentsReturnsEmptyPartitions() {
        Map<String, Set<String>> regionPincodes = Map.of("Kothrud", Set.of("411038"));
        Map<String, List<Shipment>> result = service.partitionByRegion(List.of(), regionPincodes);

        assertThat(result.get("__NO_REGION__")).isEmpty();
        assertThat(result.getOrDefault("Kothrud", List.of())).isEmpty();
    }

    // ── densePackRegion ───────────────────────────────────────────────────────

    @Test
    void densePackRegion_allShipmentsFitInOneSr_noOverflow() throws Exception {
        // With a large shift duration, all shipments should fit in SR-001
        setField(service, "shiftDurationMinutes", 9999);

        List<Shipment> shipments = buildShipments(3);
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(shipments, List.of("SR-001", "SR-002"));

        assertThat(result.overflow()).isEmpty();
        int totalAssigned = result.assignments().values().stream().mapToInt(List::size).sum();
        assertThat(totalAssigned).isEqualTo(3);
    }

    @Test
    void densePackRegion_overflowWhenShiftTooSmall() throws Exception {
        // With a tiny shift duration (1 minute), most shipments should overflow
        setField(service, "shiftDurationMinutes", 1);

        List<Shipment> shipments = buildShipments(5);
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(shipments, List.of("SR-001"));

        // At 1 minute shift, even a single shipment (5 min handling + travel) won't fit
        // All should overflow
        assertThat(result.overflow()).isNotEmpty();
    }

    @Test
    void densePackRegion_noShipmentsReturnsEmptyAssignments() {
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(List.of(), List.of("SR-001", "SR-002"));

        assertThat(result.overflow()).isEmpty();
        assertThat(result.assignments().get("SR-001")).isEmpty();
        assertThat(result.assignments().get("SR-002")).isEmpty();
    }

    @Test
    void densePackRegion_assignmentsContainAllSrs() {
        List<Shipment> shipments = buildShipments(2);
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(shipments, List.of("SR-001", "SR-002", "SR-003"));

        assertThat(result.assignments()).containsKeys("SR-001", "SR-002", "SR-003");
    }

    // ── nearestNeighbourOrder ─────────────────────────────────────────────────

    @Test
    void nearestNeighbourOrder_emptyListReturnsEmpty() {
        assertThat(service.nearestNeighbourOrder(List.of())).isEmpty();
    }

    @Test
    void nearestNeighbourOrder_singleShipmentReturnsSingle() {
        List<Shipment> result = service.nearestNeighbourOrder(
                List.of(shipmentAt("S1", HUB_LAT + 0.01, HUB_LNG + 0.01)));
        assertThat(result).hasSize(1);
    }

    @Test
    void nearestNeighbourOrder_preservesAllShipments() {
        List<Shipment> shipments = buildShipments(5);
        List<Shipment> ordered = service.nearestNeighbourOrder(shipments);

        Set<String> inputIds = new HashSet<>();
        shipments.forEach(s -> inputIds.add(s.getShippingId()));
        Set<String> outputIds = new HashSet<>();
        ordered.forEach(s -> outputIds.add(s.getShippingId()));

        assertThat(outputIds).isEqualTo(inputIds);
    }

    @Test
    void nearestNeighbourOrder_firstShipmentIsClosestToHub() {
        // S_near is very close to hub, S_far is far away
        Shipment near = shipmentAt("S_near", HUB_LAT + 0.001, HUB_LNG + 0.001);
        Shipment far  = shipmentAt("S_far",  HUB_LAT + 0.10,  HUB_LNG + 0.10);

        List<Shipment> ordered = service.nearestNeighbourOrder(List.of(far, near));
        assertThat(ordered.get(0).getShippingId()).isEqualTo("S_near");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> buildConfig(List<Map<String, Object>> regions) {
        Map<String, Object> config = new HashMap<>();
        config.put("regions", regions);
        return config;
    }

    private static Map<String, Object> buildRegion(String name, List<String> assignedSRs) {
        Map<String, Object> region = new HashMap<>();
        region.put("name", name);
        region.put("assignedSRs", assignedSRs);
        region.put("polygon", List.of(
                List.of(18.50, 73.82), List.of(18.51, 73.83),
                List.of(18.52, 73.82), List.of(18.50, 73.82)
        ));
        return region;
    }

    private static Shipment shipmentWithPincode(String id, String pincode) {
        return Shipment.builder()
                .shippingId(id)
                .dropPincode(pincode)
                .dropLatitude(HUB_LAT + 0.01)
                .dropLongitude(HUB_LNG + 0.01)
                .orderType("Prepaid")
                .build();
    }

    private static Shipment shipmentAt(String id, double lat, double lng) {
        return Shipment.builder()
                .shippingId(id)
                .dropLatitude(lat)
                .dropLongitude(lng)
                .orderType("Prepaid")
                .build();
    }

    private static List<Shipment> buildShipments(int n) {
        List<Shipment> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            list.add(shipmentAt("S" + i,
                    HUB_LAT + 0.03 * Math.cos(angle),
                    HUB_LNG + 0.03 * Math.sin(angle)));
        }
        return list;
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
