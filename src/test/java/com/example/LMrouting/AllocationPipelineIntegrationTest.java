package com.example.LMrouting;

import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AllocationEngineService;
import com.example.LMrouting.service.AttendanceManagerService;
import com.example.LMrouting.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full allocation pipeline end-to-end.
 * Uses in-memory store — no database required.
 */
@SpringBootTest
class AllocationPipelineIntegrationTest {

    private static final String TEST_DATE_STR = "2026-01-15";
    private static final LocalDate TEST_DATE = LocalDate.parse(TEST_DATE_STR);
    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    @Autowired private AllocationEngineService allocationEngineService;
    @Autowired private AttendanceManagerService attendanceManagerService;
    @Autowired private InMemoryStore store;

    @BeforeEach
    void seedTestData() {
        store.clearDate(TEST_DATE_STR);

        List<Shipment> shipments = new java.util.ArrayList<>();

        // 10 Forward shipments
        for (int i = 1; i <= 10; i++) {
            double latOffset = (i - 5) * 0.005;
            double lngOffset = (i - 5) * 0.004;
            shipments.add(Shipment.builder()
                    .shippingId("FWD-" + String.format("%03d", i))
                    .allocationDate(TEST_DATE_STR).hubName("PNQ HDP")
                    .dropPincode("41100" + i).shipmentFlow("Forward").isHeavy(0)
                    .phyWeight(1.5 + i * 0.3).volWeight(2.0).orderType("Prepaid")
                    .dropLatitude(HUB_LAT + latOffset).dropLongitude(HUB_LNG + lngOffset)
                    .clientId("CLIENT-01").runNumber(1).build());
        }
        // 2 Reverse shipments
        for (int i = 1; i <= 2; i++) {
            shipments.add(Shipment.builder()
                    .shippingId("REV-" + String.format("%03d", i))
                    .allocationDate(TEST_DATE_STR).hubName("PNQ HDP")
                    .dropPincode("41200" + i).shipmentFlow("Reverse").isHeavy(0)
                    .phyWeight(2.0).volWeight(2.5).orderType("Reverse")
                    .dropLatitude(HUB_LAT + i * 0.003).dropLongitude(HUB_LNG + i * 0.002)
                    .clientId("CLIENT-02").runNumber(1).build());
        }
        // 2 Heavy shipments
        for (int i = 1; i <= 2; i++) {
            shipments.add(Shipment.builder()
                    .shippingId("HVY-" + String.format("%03d", i))
                    .allocationDate(TEST_DATE_STR).hubName("PNQ HDP")
                    .dropPincode("41300" + i).shipmentFlow("Forward").isHeavy(1)
                    .phyWeight(15.0 + i * 2.0).volWeight(20.0).orderType("Prepaid")
                    .dropLatitude(HUB_LAT + i * 0.006).dropLongitude(HUB_LNG + i * 0.005)
                    .clientId("CLIENT-03").runNumber(1).build());
        }

        store.saveShipments(TEST_DATE_STR, shipments);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-001", true);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-002", true);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-003", true);
    }

    @Test
    void fullAllocationPipeline_assignsAllShipmentsAndPersistsCompletedRun() {
        allocationEngineService.allocate(TEST_DATE);

        List<Shipment> shipments = store.findShipmentsByDate(TEST_DATE_STR);
        assertThat(shipments).hasSize(14);

        for (Shipment s : shipments) {
            assertThat(s.getAssignedSr()).as("Shipment %s must have assignedSr", s.getShippingId()).isNotNull().isNotBlank();
            assertThat(s.getRouteSequence()).as("Shipment %s must have routeSequence > 0", s.getShippingId()).isGreaterThan(0);
        }

        Optional<AllocationRun> runOpt = store.findAllocationRun(TEST_DATE);
        assertThat(runOpt).isPresent();
        assertThat(runOpt.get().getStatus()).isEqualTo(AllocationStatus.COMPLETED);
        assertThat(runOpt.get().getTotalShipments()).isEqualTo(14);
        assertThat(runOpt.get().getTotalSrs()).isEqualTo(3);
    }

    @Test
    void fullAllocationPipeline_everyPresentSrReceivesAtLeastOneShipment() {
        allocationEngineService.allocate(TEST_DATE);

        List<Shipment> shipments = store.findShipmentsByDate(TEST_DATE_STR);
        Map<String, Long> countBySr = new java.util.HashMap<>();
        for (Shipment s : shipments) {
            countBySr.merge(s.getAssignedSr(), 1L, Long::sum);
        }

        assertThat(countBySr.getOrDefault("SR-001", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(countBySr.getOrDefault("SR-002", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(countBySr.getOrDefault("SR-003", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(countBySr.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(14);
    }

    @Test
    void fullAllocationPipeline_routeSequencesAreContiguousPerSr() {
        allocationEngineService.allocate(TEST_DATE);

        List<Shipment> shipments = store.findShipmentsByDate(TEST_DATE_STR);
        Map<String, List<Integer>> seqBySr = new java.util.LinkedHashMap<>();
        for (Shipment s : shipments) {
            seqBySr.computeIfAbsent(s.getAssignedSr(), k -> new java.util.ArrayList<>())
                    .add(s.getRouteSequence());
        }

        for (Map.Entry<String, List<Integer>> entry : seqBySr.entrySet()) {
            List<Integer> sequences = entry.getValue().stream().sorted().toList();
            for (int i = 0; i < sequences.size(); i++) {
                assertThat(sequences.get(i)).as("SR %s: sequence at %d should be %d", entry.getKey(), i, i + 1).isEqualTo(i + 1);
            }
        }
    }
}
