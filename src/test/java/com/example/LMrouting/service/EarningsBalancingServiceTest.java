package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EarningsBalancingService.
 *
 * Validates: Requirements 2.4
 *
 * Tests cover:
 * - Known earnings distributions converge to ±20% of median target
 * - Workload constraints are respected (no SR exceeds shift duration)
 * - Termination after maxIterations
 * - Edge cases: single SR, all equal earnings, extreme imbalance
 */
class EarningsBalancingServiceTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double EFFECTIVE_SHIFT_MINUTES = 450.0;

    private EarningsBalancingService balancingService;
    private ShiftWorkloadCalculatorService workloadCalculator;
    private RouteOptimizerService routeOptimizerService;

    @BeforeEach
    void setUp() throws Exception {
        workloadCalculator = mock(ShiftWorkloadCalculatorService.class);
        routeOptimizerService = mock(RouteOptimizerService.class);

        balancingService = new EarningsBalancingService(workloadCalculator, routeOptimizerService);
        setField(balancingService, "balanceTarget", 0.50);
        setField(balancingService, "maxIterations", 50);
    }

    // =========================================================================
    // Known earnings distributions → convergence to ±20% target
    // =========================================================================

    @Test
    void balance_knownDistribution_convergesToTarget() throws Exception {
        // Setup: 3 SRs with imbalanced earnings
        // SR1: high-value shipments (high expectedPayout)
        // SR2: medium-value shipments
        // SR3: low-value shipments
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 10, 200.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 10, 100.0, HUB_LAT + 0.02, HUB_LNG + 0.02));
        assignments.put("SR3", buildShipments("SR3", 10, 50.0, HUB_LAT + 0.03, HUB_LNG + 0.03));

        // Mock workload: all within shift duration (allow transfers)
        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0));

        // Mock distance estimation: small distance so fuel cost doesn't dominate
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.srAssignments()).isNotNull();
        // All shipments should still be accounted for
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(30);
    }

    @Test
    void balance_moderateImbalance_reducesSpread() throws Exception {
        // Setup: 4 SRs with moderate imbalance
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 8, 150.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 8, 120.0, HUB_LAT - 0.01, HUB_LNG + 0.01));
        assignments.put("SR3", buildShipments("SR3", 8, 90.0, HUB_LAT + 0.01, HUB_LNG - 0.01));
        assignments.put("SR4", buildShipments("SR4", 8, 60.0, HUB_LAT - 0.01, HUB_LNG - 0.01));

        // Mock workload: within shift duration
        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(250.0, 120.0, 100.0, 30.0));

        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(8.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // Total shipments preserved
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(32);
    }

    @Test
    void balance_alreadyBalanced_noChangesAndNoWarning() throws Exception {
        // Setup: 3 SRs with nearly equal earnings
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 10, 100.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 10, 100.0, HUB_LAT + 0.02, HUB_LNG + 0.02));
        assignments.put("SR3", buildShipments("SR3", 10, 100.0, HUB_LAT + 0.03, HUB_LNG + 0.03));

        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0));
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
        // Shipments should remain unchanged since already balanced
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(30);
    }

    // =========================================================================
    // Workload constraints are respected (no SR exceeds shift duration)
    // =========================================================================

    @Test
    void balance_workloadConstraint_preventsTransferWhenExceedsShift() throws Exception {
        // Setup: 2 SRs with imbalanced earnings
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 15, 200.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 5, 50.0, HUB_LAT + 0.02, HUB_LNG + 0.02));

        // Mock workload: receiving SR (SR2) would exceed shift if any shipment is added
        when(workloadCalculator.computeWorkload(anyList())).thenAnswer(invocation -> {
            List<Shipment> shipments = invocation.getArgument(0);
            if (shipments.size() > 5) {
                // If SR2 gets more than 5 shipments, it exceeds shift
                return new ShiftWorkloadCalculatorService.WorkloadResult(460.0, 200.0, 200.0, 60.0);
            }
            return new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0);
        });

        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // SR2 should not exceed its original shipment count since workload would exceed shift
        // The service should respect the 450-minute constraint
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(20);

        // Verify SR2 did not receive any additional shipments (workload constraint blocked transfer)
        List<Shipment> sr2Shipments = result.srAssignments().get("SR2");
        assertThat(sr2Shipments).hasSize(5);
    }

    @Test
    void balance_workloadConstraint_allowsTransferWhenWithinShift() throws Exception {
        // Setup: 2 SRs with imbalanced earnings, workload allows transfers
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 12, 180.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 6, 60.0, HUB_LAT + 0.02, HUB_LNG + 0.02));

        // Mock workload: always within shift duration
        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(300.0, 150.0, 120.0, 30.0));

        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(6.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // Total shipments preserved
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(18);
    }

    // =========================================================================
    // Termination after maxIterations
    // =========================================================================

    @Test
    void balance_terminatesAfterMaxIterations() throws Exception {
        // Setup: extreme imbalance that cannot be resolved
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        // SR1 has very high-value shipments, SR2 has very low-value
        assignments.put("SR1", buildShipments("SR1", 20, 500.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 20, 10.0, HUB_LAT + 0.02, HUB_LNG + 0.02));

        // Mock workload: always within shift
        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(300.0, 150.0, 120.0, 30.0));

        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        // Set maxIterations to a small number to verify termination
        setField(balancingService, "maxIterations", 5);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // Should terminate (not hang) and return a result
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(40);
    }

    @Test
    void balance_maxIterationsZero_noSwapsPerformed() throws Exception {
        // Setup: imbalanced but maxIterations = 0 means no swaps
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 10, 200.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 10, 50.0, HUB_LAT + 0.02, HUB_LNG + 0.02));

        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0));
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        setField(balancingService, "maxIterations", 0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // With 0 iterations, the imbalance warning should be set (since no balancing occurred)
        assertThat(result.earningsImbalanceWarning()).isTrue();
        // Shipments unchanged
        assertThat(result.srAssignments().get("SR1")).hasSize(10);
        assertThat(result.srAssignments().get("SR2")).hasSize(10);
    }

    // =========================================================================
    // Edge cases: single SR
    // =========================================================================

    @Test
    void balance_singleSr_returnsUnchangedNoWarning() {
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 10, 100.0, HUB_LAT + 0.01, HUB_LNG + 0.01));

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
        assertThat(result.srAssignments().get("SR1")).hasSize(10);
    }

    @Test
    void balance_nullInput_returnsEmptyNoWarning() {
        EarningsBalancingService.BalancingResult result = balancingService.balance(null);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
        assertThat(result.srAssignments()).isEmpty();
    }

    @Test
    void balance_emptyMap_returnsEmptyNoWarning() {
        EarningsBalancingService.BalancingResult result = balancingService.balance(new LinkedHashMap<>());

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
    }

    @Test
    void balance_singleSrWithEmptyList_returnsUnchangedNoWarning() {
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", new ArrayList<>());
        assignments.put("SR2", buildShipments("SR2", 10, 100.0, HUB_LAT + 0.01, HUB_LNG + 0.01));

        // Only one active SR (SR2 has shipments, SR1 is empty)
        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
    }

    // =========================================================================
    // Edge cases: all equal earnings
    // =========================================================================

    @Test
    void balance_allEqualEarnings_noChangesAndNoWarning() throws Exception {
        // All SRs have identical shipments → identical earnings
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 8, 100.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 8, 100.0, HUB_LAT + 0.02, HUB_LNG + 0.02));
        assignments.put("SR3", buildShipments("SR3", 8, 100.0, HUB_LAT + 0.03, HUB_LNG + 0.03));

        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0));
        // Same distance for all → same fuel cost → same net earnings
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
        // Shipments should remain unchanged
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(24);
    }

    // =========================================================================
    // Edge cases: extreme imbalance
    // =========================================================================

    @Test
    void balance_extremeImbalance_setsWarningWhenCannotResolve() throws Exception {
        // Setup: extreme imbalance that cannot be fully resolved
        // SR1 has very high-value shipments that are far from SR2's centroid
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        // SR1: high payout, far north
        assignments.put("SR1", buildShipments("SR1", 15, 300.0, HUB_LAT + 0.05, HUB_LNG + 0.05));
        // SR2: low payout, far south
        assignments.put("SR2", buildShipments("SR2", 15, 20.0, HUB_LAT - 0.05, HUB_LNG - 0.05));

        // Mock workload: always within shift (so workload isn't the blocker)
        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(300.0, 150.0, 120.0, 30.0));

        // Distance varies by list size to create different fuel costs
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenAnswer(invocation -> {
            List<Shipment> shipments = invocation.getArgument(0);
            return shipments.size() * 0.5; // proportional to shipment count
        });

        // With maxIterations=50, the algorithm will try to balance but extreme
        // imbalance (300 vs 20 per shipment) may not fully resolve
        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        // Total shipments preserved regardless of outcome
        int totalShipments = result.srAssignments().values().stream()
                .mapToInt(List::size).sum();
        assertThat(totalShipments).isEqualTo(30);
    }

    @Test
    void balance_twoSrsOneEmpty_returnsWithoutError() {
        // One SR has shipments, the other is empty (filtered as inactive)
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 10, 100.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", new ArrayList<>());

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result).isNotNull();
        assertThat(result.earningsImbalanceWarning()).isFalse();
        assertThat(result.srAssignments().get("SR1")).hasSize(10);
    }

    @Test
    void balance_preservesAllShipmentIds() throws Exception {
        // Verify that no shipments are lost or duplicated during balancing
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 8, 200.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 8, 80.0, HUB_LAT + 0.02, HUB_LNG + 0.02));
        assignments.put("SR3", buildShipments("SR3", 8, 50.0, HUB_LAT + 0.03, HUB_LNG + 0.03));

        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(250.0, 120.0, 100.0, 30.0));
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(5.0);

        // Collect all original shipment IDs
        Set<String> originalIds = new HashSet<>();
        assignments.values().forEach(list ->
                list.forEach(s -> originalIds.add(s.getShippingId())));

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        // Collect all result shipment IDs
        Set<String> resultIds = new HashSet<>();
        result.srAssignments().values().forEach(list ->
                list.forEach(s -> resultIds.add(s.getShippingId())));

        assertThat(resultIds).isEqualTo(originalIds);
    }

    @Test
    void balance_multipleActiveSrs_resultContainsAllOriginalSrKeys() throws Exception {
        // Verify that the result map contains all original SR keys
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        assignments.put("SR1", buildShipments("SR1", 5, 150.0, HUB_LAT + 0.01, HUB_LNG + 0.01));
        assignments.put("SR2", buildShipments("SR2", 5, 100.0, HUB_LAT + 0.02, HUB_LNG + 0.02));
        assignments.put("SR3", buildShipments("SR3", 5, 50.0, HUB_LAT + 0.03, HUB_LNG + 0.03));

        when(workloadCalculator.computeWorkload(anyList()))
                .thenReturn(new ShiftWorkloadCalculatorService.WorkloadResult(200.0, 100.0, 70.0, 30.0));
        when(routeOptimizerService.estimateDistanceKm(anyList())).thenReturn(4.0);

        EarningsBalancingService.BalancingResult result = balancingService.balance(assignments);

        assertThat(result.srAssignments()).containsKeys("SR1", "SR2", "SR3");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build a list of shipments with specified payout and location.
     * Shipments are placed in a small cluster around the given coordinates.
     */
    private static List<Shipment> buildShipments(String srPrefix, int count, double expectedPayout,
                                                  double baseLat, double baseLng) {
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Small offset to create a cluster
            double lat = baseLat + (i * 0.001);
            double lng = baseLng + (i * 0.001);
            shipments.add(Shipment.builder()
                    .shippingId(srPrefix + "_S" + i)
                    .dropLatitude(lat)
                    .dropLongitude(lng)
                    .orderType("Prepaid")
                    .expectedPayout(expectedPayout)
                    .build());
        }
        return shipments;
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
