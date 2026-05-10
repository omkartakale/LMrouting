package com.example.LMrouting;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.AttendanceManagerService;
import com.example.LMrouting.store.InMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for count-based allocation mode.
 *
 * Verifies that POST /api/allocate with no allocationMode (or count-based)
 * returns the same response shape as before the time-based feature was added.
 * This ensures backward compatibility.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CountBasedRegressionTest {

    private static final String TEST_DATE_STR = "2026-03-15";
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
        for (int i = 1; i <= 8; i++) {
            double latOffset = (i - 4) * 0.005;
            double lngOffset = (i - 4) * 0.004;
            shipments.add(Shipment.builder()
                    .shippingId("CB-FWD-" + String.format("%03d", i))
                    .allocationDate(TEST_DATE_STR).hubName("PNQ HDP")
                    .dropPincode("41100" + i).shipmentFlow("Forward").isHeavy(0)
                    .phyWeight(1.5).volWeight(2.0).orderType("Prepaid")
                    .dropLatitude(HUB_LAT + latOffset).dropLongitude(HUB_LNG + lngOffset)
                    .clientId("CLIENT-01").runNumber(1).build());
        }
        store.saveShipments(TEST_DATE_STR, shipments);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-001", true);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-002", true);
        attendanceManagerService.setAttendance(TEST_DATE, "SR-003", true);
    }

    @Test
    void countBased_noAllocationMode_returnsExistingResponseShape() throws Exception {
        // Request with no allocationMode field (backward-compatible)
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).isNotBlank();

        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // All existing fields must be present
        assertThat(summary.date()).isEqualTo(TEST_DATE_STR);
        assertThat(summary.totalShipments()).isEqualTo(8);
        assertThat(summary.totalSrs()).isEqualTo(3);
        assertThat(summary.srSummaries()).isNotNull();
        assertThat(summary.srSummaries()).isNotEmpty();
        assertThat(summary.fairnessVariance()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void countBased_explicitCountBasedMode_returnsExistingResponseShape() throws Exception {
        // Request with explicit count-based mode
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\",\"allocationMode\":\"count-based\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        assertThat(summary.date()).isEqualTo(TEST_DATE_STR);
        assertThat(summary.totalShipments()).isEqualTo(8);
        assertThat(summary.srSummaries()).isNotEmpty();
    }

    @Test
    void countBased_srSummaries_haveAllExistingFields() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // Verify each SR summary has all existing fields
        for (SrSummaryDto sr : summary.srSummaries()) {
            assertThat(sr.srName()).isNotNull().isNotBlank();
            assertThat(sr.shipmentCount()).isGreaterThanOrEqualTo(0);
            assertThat(sr.estimatedDistanceKm()).isGreaterThanOrEqualTo(0.0);
            assertThat(sr.pincodesCovered()).isNotNull();
            // Time-based fields should be null in count-based mode
            assertThat(sr.affinityStatus()).isNull();
            assertThat(sr.estimatedWorkloadMinutes()).isNull();
            assertThat(sr.shiftUtilisationPct()).isNull();
        }
    }

    @Test
    void countBased_allocationSummary_timeBasedFieldsAreNull() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        AllocationSummary summary = objectMapper.readValue(responseBody, AllocationSummary.class);

        // Time-based fields should be null in count-based mode (backward compatibility)
        assertThat(summary.shiftDurationMinutes()).isNull();
        assertThat(summary.overflowShipments()).isNull();
        assertThat(summary.noRegionShipments()).isNull();
    }

    @Test
    void countBased_responseJson_containsCapacityRangeFields() throws Exception {
        String requestBody = "{\"date\":\"" + TEST_DATE_STR + "\"}";

        MvcResult result = mockMvc.perform(post("/api/allocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        // Verify JSON structure has all expected fields
        assertThat(json.has("date")).isTrue();
        assertThat(json.has("totalShipments")).isTrue();
        assertThat(json.has("allocatedShipments")).isTrue();
        assertThat(json.has("unallocatedShipments")).isTrue();
        assertThat(json.has("capacityRangeMin")).isTrue();
        assertThat(json.has("capacityRangeMax")).isTrue();
        assertThat(json.has("totalSrs")).isTrue();
        assertThat(json.has("srSummaries")).isTrue();
        assertThat(json.has("fairnessVariance")).isTrue();
        assertThat(json.has("earningsVariance")).isTrue();
        assertThat(json.has("earningsRange")).isTrue();
        assertThat(json.has("meanNetEarnings")).isTrue();
    }
}
