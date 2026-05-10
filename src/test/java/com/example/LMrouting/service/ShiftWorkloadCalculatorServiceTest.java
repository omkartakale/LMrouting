package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShiftWorkloadCalculatorService.
 */
class ShiftWorkloadCalculatorServiceTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    private TravelTimeCacheService travelTimeCache;
    private ShiftWorkloadCalculatorService calculator;

    @BeforeEach
    void setUp() throws Exception {
        travelTimeCache = mock(TravelTimeCacheService.class);
        calculator = new ShiftWorkloadCalculatorService(travelTimeCache);
        setField(calculator, "hubLat", HUB_LAT);
        setField(calculator, "hubLng", HUB_LNG);
        setField(calculator, "handlingTimeCod", 3.0);
        setField(calculator, "handlingTimePrepaid", 3.0);
        setField(calculator, "handlingTimeDefault", 3.0);
    }

    // ── Handling time classification ──────────────────────────────────────────

    @Test
    void getHandlingTime_codShipmentReturns3ByDefault() throws Exception {
        // Default handling time is 3.0 min (configurable via allocation.handling.time.cod)
        Shipment s = shipment("COD");
        assertThat(calculator.getHandlingTime(s)).isEqualTo(3.0);
    }

    @Test
    void getHandlingTime_prepaidShipmentReturns3ByDefault() throws Exception {
        Shipment s = shipment("Prepaid");
        assertThat(calculator.getHandlingTime(s)).isEqualTo(3.0);
    }

    @Test
    void getHandlingTime_unknownTypeReturns3ByDefault() throws Exception {
        assertThat(calculator.getHandlingTime(shipment("Reverse"))).isEqualTo(3.0);
        assertThat(calculator.getHandlingTime(shipment("Express"))).isEqualTo(3.0);
        assertThat(calculator.getHandlingTime(shipment(""))).isEqualTo(3.0);
    }

    @Test
    void getHandlingTime_nullOrderTypeReturns5() {
        Shipment s = Shipment.builder().orderType(null).build();
        assertThat(calculator.getHandlingTime(s)).isEqualTo(5.0);
    }

    @Test
    void getHandlingTime_nullShipmentReturns5() {
        assertThat(calculator.getHandlingTime(null)).isEqualTo(5.0);
    }

    // ── Zero-shipment edge case ───────────────────────────────────────────────

    @Test
    void computeWorkload_emptyListReturnsZeroWorkload() {
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(List.of());
        assertThat(result.totalMinutes()).isEqualTo(0.0);
        assertThat(result.handlingMinutes()).isEqualTo(0.0);
        assertThat(result.travelMinutes()).isEqualTo(0.0);
        assertThat(result.returnToHubMinutes()).isEqualTo(0.0);
    }

    @Test
    void computeWorkload_nullListReturnsZeroWorkload() {
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(null);
        assertThat(result.totalMinutes()).isEqualTo(0.0);
    }

    // ── Positive workload for non-empty list ──────────────────────────────────

    @Test
    void computeWorkload_singleCodShipmentHasPositiveWorkload() {
        when(travelTimeCache.getRouteTime(any())).thenReturn(10.0);
        when(travelTimeCache.getReturnToHubTime(anyDouble(), anyDouble())).thenReturn(8.0);

        Shipment s = shipmentAt("COD", 18.51, 73.85);
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(List.of(s));

        assertThat(result.totalMinutes()).isGreaterThan(0.0);
        assertThat(result.handlingMinutes()).isEqualTo(3.0); // default 3.0 min
        assertThat(result.travelMinutes()).isEqualTo(10.0);
        assertThat(result.returnToHubMinutes()).isEqualTo(8.0);
    }

    @Test
    void computeWorkload_singlePrepaidShipmentHasPositiveWorkload() {
        when(travelTimeCache.getRouteTime(any())).thenReturn(5.0);
        when(travelTimeCache.getReturnToHubTime(anyDouble(), anyDouble())).thenReturn(5.0);

        Shipment s = shipmentAt("Prepaid", 18.51, 73.85);
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(List.of(s));

        assertThat(result.totalMinutes()).isGreaterThan(0.0);
        assertThat(result.handlingMinutes()).isEqualTo(5.0);
    }

    // ── Component sum invariant ───────────────────────────────────────────────

    @Test
    void computeWorkload_totalEqualsComponentSum() {
        when(travelTimeCache.getRouteTime(any())).thenReturn(15.0);
        when(travelTimeCache.getReturnToHubTime(anyDouble(), anyDouble())).thenReturn(12.0);

        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),
                shipmentAt("Prepaid", 18.52, 73.86),
                shipmentAt("Reverse", 18.53, 73.87)
        );
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(shipments);

        double expectedTotal = result.handlingMinutes() + result.travelMinutes() + result.returnToHubMinutes();
        assertThat(result.totalMinutes()).isCloseTo(expectedTotal, within(1e-9));
    }

    @Test
    void computeWorkload_handlingMinutesMatchesManualSum() {
        when(travelTimeCache.getRouteTime(any())).thenReturn(0.0);
        when(travelTimeCache.getReturnToHubTime(anyDouble(), anyDouble())).thenReturn(0.0);

        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),     // 3.0
                shipmentAt("Prepaid", 18.52, 73.86), // 3.0
                shipmentAt("Reverse", 18.53, 73.87)  // 3.0
        );
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(shipments);

        assertThat(result.handlingMinutes()).isEqualTo(9.0); // 3 + 3 + 3
    }

    @Test
    void computeWorkload_multipleShipmentsCallsTravelCacheOnce() {
        when(travelTimeCache.getRouteTime(any())).thenReturn(20.0);
        when(travelTimeCache.getReturnToHubTime(anyDouble(), anyDouble())).thenReturn(10.0);

        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),
                shipmentAt("Prepaid", 18.52, 73.86)
        );
        calculator.computeWorkload(shipments);

        verify(travelTimeCache, times(1)).getRouteTime(any());
        verify(travelTimeCache, times(1)).getReturnToHubTime(anyDouble(), anyDouble());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Shipment shipment(String orderType) {
        return Shipment.builder()
                .shippingId("TEST-001")
                .orderType(orderType)
                .dropLatitude(18.51)
                .dropLongitude(73.85)
                .build();
    }

    private static Shipment shipmentAt(String orderType, double lat, double lng) {
        return Shipment.builder()
                .shippingId("TEST-" + lat)
                .orderType(orderType)
                .dropLatitude(lat)
                .dropLongitude(lng)
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
