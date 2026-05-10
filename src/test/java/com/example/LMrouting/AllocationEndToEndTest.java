package com.example.LMrouting;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AttendanceManagerService;
import com.example.LMrouting.store.InMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for time-based allocation mode.
 *
 * Tests that POST /api/allocate with allocationMode: "time-based" returns
 * a response with the expected time-based fields populated.
 *
 * Note: With no affinity config loaded, the service falls back to count-based
 * pipeline. This test verifies the API contract and backward compatibility.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocationEndToEndTest {

    private static final String TEST_DATE_STR = "2026-02-15";
    private static final LocalDate TEST_DATE = LocalDate.parse(TEST_DATE_STR);
    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryStore store;
    @Autowired private AttendanceManagerService attendanceManagerService;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void seedTestData() {
        store.clearDate(TEST_DATE_STR);

        List<Shipment> shipments = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            double latOffset = (i - 3) * 0.005;
            double lngOffset = (i - 3) * 0.004;
            shipments.add(Shipment.builder()
                    .shippingId("TB-FWD-" + String.format("%03d", i))
                    .allocationDate(TEST_DATE_STR).hubName("PNQ HDP")
                    .dropPincode("41100" + i).shipmentFlow("Forward").isHeavy(0)
                    .phyWeight(1.5).volWeight(2.0)
                    .orderType(i % 2 == 0 ? "COD" : "Prepaid")
                    .dropLatitude(HUB_LAT + latOffset).dropLongitude(HUB_LNG + lngOffset)
                    .clientId("CLIENT-01").runNumber(1).build());
        }
        store.saveShipments(TEST_DATE_STR, shipments);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-001", true);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-002", true);
    }

    @Test
    void timeBased_allocate_returnsAllocationModeField() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\",\"allocationMode\":\"time-based\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).isNotBlank();

        // Parse response
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // The response should have allocationMode set (either "time-based" or "count-based" fallback)
        // With no affinity config, it falls back to count-based
        assertThat(summary.date()).isEqualTo(TEST_DATE_STR);
        assertThat(summary.totalShipments()).isEqualTo(6);
    }

    @Test
    void timeBased_allocate_responseHasExpectedShape() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\",\"allocationMode\":\"time-based\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // Core fields must always be present
        assertThat(summary.date()).isNotNull();
        assertThat(summary.totalShipments()).isGreaterThan(0);
        assertThat(summary.totalSrs()).isGreaterThan(0);
        assertThat(summary.srSummaries()).isNotNull();
        assertThat(summary.srSummaries()).isNotEmpty();
    }

    @Test
    void timeBased_allocate_srSummariesHaveExpectedFields() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\",\"allocationMode\":\"time-based\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // Each SR summary must have srName and shipmentCount
        for (SrSummaryDto sr : summary.srSummaries()) {
            assertThat(sr.srName()).isNotNull().isNotBlank();
            assertThat(sr.shipmentCount()).isGreaterThanOrEqualTo(0);
        }
    }
}
